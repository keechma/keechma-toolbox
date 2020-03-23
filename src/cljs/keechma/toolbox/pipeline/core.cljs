(ns keechma.toolbox.pipeline.core
  (:require [cljs.core.async :refer [<! chan put!]]
            [promesa.core :as p]
            [keechma.controller :as controller]
            [medley.core :refer [dissoc-in]])
  (:require-macros [cljs.core.async.macros :refer [go-loop]]))

(defrecord Error [type message payload cause])

(defn error! [type payload]
  (->Error type nil payload nil))

(defprotocol ISideffect
  (call! [this controller app-db-atom pipelines$]))

(defrecord CommitSideffect [value cb]
  ISideffect
  (call! [this _ app-db-atom _]
    (let [cb (:cb this)]
      (reset! app-db-atom (:value this))
      (when cb (cb)))))

(defrecord SendCommandSideffect [command payload]
  ISideffect
  (call! [this controller _ _]
    (controller/send-command controller (:command this) (:payload this))))

(defrecord BroadcastSideffect [command payload]
  ISideffect
  (call! [this controller _ _]
    (controller/broadcast controller (:command this) (:payload this))))

(defrecord ExecuteSideffect [command payload]
  ISideffect
  (call! [this controller _ _]
    (controller/execute controller (:command this) (:payload this))))

(defrecord RedirectSideffect [params action]
  ISideffect
  (call! [this controller _ _]
    (let [action (:action this)
          params (:params this)]
      (if action
        (controller/redirect controller params action)
        (controller/redirect controller params)))))

(defrecord DoSideffect [sideffects]
  ISideffect
  (call! [this controller app-db-atom pipelines$]
    (let [sideffects (:sideffects this)]
      (doseq [s sideffects]
        (call! s controller app-db-atom pipelines$)))))

(defrecord RunPipelineSideffect [pipeline-key args]
  ISideffect
  (call! [this controller app-db-atom pipelines$]
    (let [pipeline (get-in controller [:pipelines pipeline-key])]
      (if pipeline
        (pipeline controller app-db-atom args pipelines$)
        (throw (ex-info (str "Pipeline " pipeline-key " doesn't exist") {:pipeline pipeline-key}))))))

(defrecord RerouteSideffect []
  ISideffect
  (call! [_ controller _ _]
    (controller/reroute controller)))

(defn prepare-running-pipelines [pipelines]
  (reduce-kv
   (fn [acc name ps]
     (concat acc (mapv (fn [[id p]] {:id [name id] :args (:args p)}) ps)))
   [] pipelines))

(defrecord WaitPipelinesSideffect [pipeline-filter]
  ISideffect
  (call! [_ _ _ pipelines$]
    (let [running-pipelines @pipelines$
          filtered (pipeline-filter (prepare-running-pipelines running-pipelines))]
      (when (seq filtered)
        (let [promises (map #(get-in running-pipelines (conj (:id %) :promise)) filtered)]
          (->> (p/all promises)
               (p/map (fn [_] nil))
               (p/error (fn [_] nil))))))))

(defrecord CancelPipelinesSideffect [pipeline-filter]
  ISideffect
  (call! [_ _ _ pipelines$]
    (let [running-pipelines @pipelines$
          filtered (pipeline-filter (prepare-running-pipelines running-pipelines))]
      (when (seq filtered)
        (reset! pipelines$
                (reduce (fn [acc v] (dissoc-in acc (:id v))) running-pipelines filtered))
        nil))))

(defn commit!
  "
Commit pipeline sideffect.

Accepts `value` or `value` and `callback` as arguments. Value should be a new version of app-db.

```clojure
(commit! (assoc-in app-db [:kv :user] {:username \"retro\"}))

```

If the callback argument is present, this function will be called immediately after the app-db-atom is updated.
This is useful if you want to force Reagent to re-render the screen.
"
  ([value] (commit! value nil))
  ([value cb]
   (->CommitSideffect value cb)))

(defn execute!
  "
Execute pipeline sideffect.

Accepts `command` and `payload` arguments. Use this if you want to execute a command on the current controller.
"
  ([command] (execute! command nil))
  ([command payload]
   (->ExecuteSideffect command payload)))

(defn send-command!
  "
Send command pipeline sideffect.

Accepts `command` and `payload` arguments. Command should be a vector where first element is the controller topic, and the second
element is the command name. 
"
  ([command] (send-command! command nil))
  ([command payload]
   (->SendCommandSideffect command payload)))

(defn broadcast!
  "
Broadcast pipeline sideffect.

Accepts `command` and `payload` arguments.
"
  [command payload]
  (->BroadcastSideffect command payload))

(defn redirect!
  "
Redirect pipeline sideffect.

Accepts `params` argument. Page will be redirected to a new URL which will be generated from the passed in params argument. If you need to 
access the current route data, it is present in the pipeline `app-db` argument under the `[:route :data]` path.
"
  ([params]
   (redirect! params nil))
  ([params action]
   (->RedirectSideffect params action)))

(defn do!
  "
Runs multiple sideffects sequentially:

```clojure
(do!
  (commit! (assoc-in app-db [:kv :current-user] value))
  (redirect! {:page \"user\" :id (:id user)}))
```
"
  [& sideffects]
  (->DoSideffect sideffects))


(defn run-pipeline!
  "Runs a pipeline in a way that blocks the current pipeline until the current pipeline is done. It behaves same as `execute! but blocks the parent pipeline until it's done. Return value and errors will be ignored by the parent pipeline."
  ([pipeline-key] (run-pipeline! pipeline-key nil))
  ([pipeline-key args]
   (->RunPipelineSideffect pipeline-key args)))

(defn reroute! []
  (->RerouteSideffect))

(defn wait-pipelines! [pipeline-filter]
  (->WaitPipelinesSideffect pipeline-filter))

(defn cancel-pipelines! [pipeline-filter]
  (->CancelPipelinesSideffect pipeline-filter))

(defn process-error [err]
  (cond
    (instance? Error err) err
    :else (->Error :default nil err nil)))

(defn is-promise? [val]
  (if (or (instance? js/Error val) (instance? Error val))
    false
    (= val (p/promise val))))

(defn promise->chan [promise]
  (let [promise-chan (chan)]
    (->> promise
         (p/map (fn [v] (put! promise-chan (if (nil? v) ::nil v))))
         (p/error (fn [e] (put! promise-chan (process-error e)))))
    promise-chan))

(def pipeline-errors
  {:async-sideffect "Returning sideffects from promises is not permitted. It is possible that application state was modified in the meantime"})

(declare run-pipeline)

(defn action-ret-val [action ctrl context app-db-atom value error pipelines$]
  (try
    (let [ret (if (nil? error) (action value @app-db-atom context) (action value @app-db-atom context error))
          ret-val (:val ret)
          ret-repr (:repr ret)]
      (if (:pipeline? (meta ret-val))
        {:value (ret-val ctrl app-db-atom value pipelines$)
         :promise? true}
        {:value ret-val
         :repr ret-repr
         :promise? (is-promise? ret-val)}))
    (catch :default err
      (if (= ::pipeline-error (:type (.-data err)))
        (throw err)
        {:value (process-error err)
         :promise? false}))))

(defn extract-nil [value]
  (if (= ::nil value) nil value))

(defn call-sideffect [sideffect ctrl app-db-atom pipelines$]
  (try
    {:value (call! sideffect ctrl app-db-atom pipelines$)
     :error? false}
    (catch :default err
      {:value err
       :error? true})))

(defn pipeline-running? [pipelines$ running-check-path]
  (if (nil? pipelines$)
    true
    (get-in @pipelines$ running-check-path)))

(defn run-pipeline
  ([pipeline ctrl app-db-atom value]
   (run-pipeline pipeline ctrl app-db-atom value nil))
  ([pipeline ctrl app-db-atom value pipelines$]
   (let [{:keys [begin rescue]} pipeline
         current-promise (atom nil)
         context (controller/context ctrl)
         running-check-path (flatten [(:pipeline/running ctrl) :running?])]
     (p/promise
      (fn [resolve reject]
        (go-loop [block :begin
                  actions begin
                  prev-value value
                  error nil]
          (if (or (not (seq actions))
                  (not (pipeline-running? pipelines$ running-check-path)))
            (resolve prev-value)
            (let [next (first actions)
                  {:keys [value promise? repr]} (action-ret-val next ctrl context app-db-atom prev-value error pipelines$)
                  sideffect? (satisfies? ISideffect value)] 
              ;;(when repr (println "STARTING" repr))
              (let [resolved-value (if promise? (extract-nil (<! (promise->chan value))) value)
                    error? (instance? Error resolved-value)]
                ;;(when repr (println "ENDING" repr))
                (when (and promise? sideffect?)
                  (throw (ex-info (:async-sideffect pipeline-errors) {:type ::pipeline-error})))
                (if sideffect?
                  (let [{:keys [value error?]} (call-sideffect resolved-value ctrl app-db-atom pipelines$)
                        resolved-value (if (is-promise? value) (<! (promise->chan value)) value)]
                    (cond
                      (and error? (= block :begin))
                      (if (seq rescue)
                        (recur :rescue rescue prev-value value)
                        (reject (or (:payload value) value)))
                     
                      (and error? (= block :rescue))
                      (reject (or (:payload value) value))
                      
                      :else
                      (recur block (rest actions) prev-value error)))
                  (cond 
                    (= ::break resolved-value)
                    (resolve ::break)

                    (and error? (= block :begin))
                    (if (seq rescue)
                      (recur :rescue rescue prev-value resolved-value)
                      (reject (or (:payload resolved-value) resolved-value)))
                    
                    (and error? (= block :rescue)
                         (not= error resolved-value))
                    (reject (or (:payload resolved-value) resolved-value))

                    (and error? (= block :rescue)
                         (= error resolved-value))
                    (reject (or (:payload error) error))

                    :else
                    (recur block
                           (rest actions)
                           (if (nil? resolved-value) prev-value resolved-value)
                           error))))))))))))

(defn make-pipeline [pipeline]
  (with-meta (partial run-pipeline pipeline) {:pipeline? true}))

(defn exclusive [pipeline]
  (let [pipeline-meta (meta pipeline)]
    (with-meta
      (fn [ctrl app-db-atom value pipelines$]
        (let [[pipeline-name pipeline-id] (:pipeline/running ctrl)]
          (swap! pipelines$ assoc pipeline-name {})
          (pipeline ctrl app-db-atom value pipelines$)))
      pipeline-meta)))
