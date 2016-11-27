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
  (call! [this controller ops app-db-atom]))

(defrecord CommitSideffect [value cb]
  ISideffect
  (call! [this _ _ app-db-atom]
    (let [cb (:cb this)]
      (reset! app-db-atom (:value this))
      (when cb (cb)))))

(defrecord SendCommandSideffect [command payload]
  ISideffect
  (call! [this controller _ _]
    (controller/send-command controller (:command this) (:payload this))))

(defrecord ExecuteSideffect [command payload]
  ISideffect
  (call! [this controller _ _]
    (controller/execute controller (:command this) (:payload this))))

(defrecord RedirectSideffect [params]
  ISideffect
  (call! [this controller _ _]
    (controller/redirect controller (:params this))))

(defrecord DoSideffect [sideffects]
  ISideffect
  (call! [this controller ops app-db-atom]
    (let [sideffects (:sideffects this)]
      (doseq [s sideffects]
        (call! s controller ops app-db-atom)))))

(defrecord ExclusivePipelineSideffect []
  ISideffect
  (call! [this _ ops _]
    (let [exclusive! (:exclusive! ops)]
      (exclusive!))))

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

(defn exclusive! []
  (->ExclusivePipelineSideffect))

(defn process-error [err]
  (cond
    (instance? Error err) err
    :else (->Error :default nil err nil)))

(defn is-promise? [val]
  (= Promise (type val)))

(defn action-ret-val [action context value error app-db]
  (try
    (let [ret-val (if (nil? error) (action context value app-db) (action context error value app-db))]
      {:value ret-val
       :promise? (is-promise? ret-val)})
    (catch :default err
      (cond
        (or (instance? ExceptionInfo err) (instance? js/Error err)) (throw err)
        :else  {:value (process-error err)
                :promise? false}))))

(defn promise->chan [promise]
  (let [promise-chan (chan)]
    (->> promise
         (p/map (fn [v] (put! promise-chan (if (nil? v) ::nil v))))
         (p/error (fn [e] (put! promise-chan (process-error e)))))
    promise-chan))

(def pipeline-errors
  {:async-sideffect "Returning sideffects from promises is not permitted. It is possible that application state was modified in the meantime"
   :rescue-missing "Unable to proceed with the pipeline. Rescue block is missing."
   :rescue-errors "Unable to proceed with the pipeline. Error was thrown in rescue block"})

(defn run-pipeline [pipeline ops ctrl app-db-atom value]
  (let [{:keys [begin rescue]} pipeline
        pipeline-context (or (:pipeline-context ctrl) {})]
    ((:register! ops))
    (go-loop [actions begin
              val value
              error nil
              running :begin]
      (if (and (pos? (count actions)) ((:running? ops)))
        (let [next (first actions)
              {:keys [value promise?]} (action-ret-val next pipeline-context val error @app-db-atom)
              sideffect? (satisfies? ISideffect value)
              resolved (if sideffect? value (<! (promise->chan (p/promise value))))
              resolved-value (if (= ::nil resolved) nil resolved)
              error? (instance? Error resolved-value)]
          (when (and promise? sideffect?)
            (throw (ex-info (:async-sideffect pipeline-errors) {})))
          (when sideffect?
            (call! resolved-value ctrl ops app-db-atom))
          (cond
            (and error? (= running :begin)) (if (pos? (count rescue))
                                              (recur rescue val resolved-value :rescue)
                                              (throw (ex-info (:rescue-missing pipeline-errors) resolved-value)))
            (and error? (= running :rescue)) (throw (ex-info (:rescue-error pipeline-errors) resolved-value))
            sideffect? (recur (drop 1 actions) val error :begin)
            :else (recur (drop 1 actions) (if (nil? resolved-value) val resolved-value) error :begin)))
        ((:stop! ops))))))

(defn make-pipeline [pipeline]
  (partial run-pipeline pipeline))
