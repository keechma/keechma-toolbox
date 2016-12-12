(ns keechma.toolbox.pipeline.controller
  (:require [keechma.controller :as controller]
            [cljs.core.async :refer [<!]]
            [keechma.toolbox.pipeline.core :refer [run-pipeline]])
  (:require-macros [cljs.core.async.macros :refer [go-loop]]))

(defrecord PipelineController [params-fn pipelines]
  controller/IController
  (params [this route-params]
    ((:params-fn this) route-params))
  (start [this params app-db]
    (controller/execute this :start params)
    app-db)
  (stop [this params app-db]
    (controller/execute this :stop params)
    app-db)
  (handler [this app-db-atom in-chan _]
    (go-loop []
      (let [[command args] (<! in-chan)]
        (when-let [pipeline (get-in this [:pipelines command])]
          (pipeline this app-db-atom args))
        (when command (recur))))))

(defn constructor [params-fn pipelines]
  (->PipelineController params-fn pipelines))
