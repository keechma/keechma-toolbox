(ns keechma.toolbox.pipeline.core
  (:require [cljs.core.async :refer [<! chan put!]]
            [promesa.core :as p]
            [promesa.impl.promise :refer [Promise]]
            [keechma.controller :as controller])
  (:require-macros [cljs.core.async.macros :refer [go-loop]]))

(defrecord Error [type message payload cause])

(defn error! [type payload]
  (->Error type nil payload nil))

(defprotocol ISideffect
  (call! [this controller app-db-atom]))

(defrecord CommitSideffect [value cb]
  ISideffect
  (call! [this _ app-db-atom]
    (let [cb (:cb this)]
      (reset! app-db-atom (:value this))
      (when cb (cb)))))

(defrecord SendCommandSideffect [command payload]
  ISideffect
  (call! [this controller _]
    (controller/send-command controller (:command this) (:payload this))))

(defrecord ExecuteSideffect [command payload]
  ISideffect
  (call! [this controller _]
    (controller/execute controller (:command this) (:payload this))))

(defrecord RedirectSideffect [params]
  ISideffect
  (call! [this controller _]
    (controller/redirect controller (:params this))))

(defrecord DoSideffect [sideffects]
  ISideffect
  (call! [this controller app-db-atom]
    (let [sideffects (:sideffects this)]
      (doseq [s sideffects]
        (call! s controller app-db-atom)))))

(defn commit!
  ([value] (commit! value nil))
  ([value cb]
   (->CommitSideffect value cb)))

(defn execute! [command payload]
  (->ExecuteSideffect command payload))

(defn send-command! [command payload]
  (->SendCommandSideffect command payload))

(defn redirect! [params]
  (->RedirectSideffect params))

(defn do! [& sideffects]
  (->DoSideffect sideffects))

(defn process-error [err]
  (cond
    (instance? Error err) err
    :else (->Error :default nil err nil)))

(defn is-promise? [val]
  (= Promise (type val)))

(defn promise->chan [promise]
  (let [promise-chan (chan)]
    (->> promise
         (p/map (fn [v] (put! promise-chan (if (nil? v) ::nil v))))
         (p/error (fn [e] (put! promise-chan (process-error e)))))
    promise-chan))

(def pipeline-errors
  {:async-sideffect "Returning sideffects from promises is not permitted. It is possible that application state was modified in the meantime"})

(declare run-pipeline)

(defn action-ret-val [action ctrl app-db-atom value error]
  (try
    (let [ret-val (if (nil? error) (action value @app-db-atom) (action value @app-db-atom error))]
      (if (:pipeline? (meta ret-val))
        {:value (ret-val ctrl app-db-atom value)
         :promise? true}
        {:value ret-val
         :promise? (is-promise? ret-val)}))
    (catch :default err
      (if (= ::pipeline-error (:type (.-data err)))
        (throw err)
        {:value (process-error err)
         :promise? false}))))

(defn extract-nil [value]
  (if (= ::nil value) nil value))

(defn run-pipeline [pipeline ctrl app-db-atom value]
  (let [{:keys [begin rescue]} pipeline
        current-promise (atom nil) ]
    (p/promise
     (fn [resolve reject on-cancel]
       (on-cancel (fn []
                    (let [c @current-promise]
                      (when (p/pending? c)
                        (p/cancel! c)))))
       (go-loop [block :begin
                 actions begin
                 prev-value value
                 error nil]
         (if (not (seq actions))
           (resolve prev-value)
           (let [next (first actions)
                 {:keys [value promise?]} (action-ret-val next ctrl app-db-atom prev-value error)]
             (when promise?
               (reset! current-promise value))
             (let [sideffect? (satisfies? ISideffect value)
                   resolved-value (if promise? (extract-nil (<! (promise->chan value))) value)
                   error? (instance? Error resolved-value)]
               (when (and promise? sideffect?)
                 (throw (ex-info (:async-sideffect pipeline-errors) {:type ::pipeline-error})))
               (when sideffect?
                 (call! resolved-value ctrl app-db-atom))
               (cond
                 (and error? (= block :begin))
                 (if (seq rescue)
                   (recur :rescue rescue prev-value resolved-value)
                   (reject resolved-value))

                 (and error? (= block :rescue))
                 (reject error)

                 sideffect?
                 (recur block (rest actions) prev-value error)

                 :else
                 (recur block
                        (rest actions)
                        (if (nil? resolved-value) prev-value resolved-value)
                        error))))))))))

(defn make-pipeline [pipeline]
  (with-meta (partial run-pipeline pipeline) {:pipeline? true}))

(defn exclusive [pipeline]
  (let [current (atom nil)]
    (fn [ctrl app-db-atom value]
      (let [c @current]
        (when (and c (p/pending? c))
          (p/cancel! c))
        (reset! current (pipeline ctrl app-db-atom value))))))
