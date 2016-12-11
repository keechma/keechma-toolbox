(ns keechma.toolbox.pipeline.controller
  (:require [keechma.controller :as controller]
            [cljs.core.async :refer [<!]]
            [keechma.toolbox.pipeline.core :refer [run-pipeline]])
  (:require-macros [cljs.core.async.macros :refer [go-loop]]))

(defn pipeline-register! [running-pipelines pipeline id]
  (let [current (or (get @running-pipelines pipeline) #{})]
    (swap! running-pipelines assoc pipeline (conj current id))))
(defn pipeline-running? [running-pipelines pipeline id]
  (let [current (get @running-pipelines pipeline)]
    (contains? current id)))
(defn pipeline-stop! [running-pipelines pipeline id]
  (let [current (get @running-pipelines pipeline)]
    (swap! running-pipelines assoc pipeline (set (remove #{id} current)))))
(defn pipeline-exclusive! [running-pipelines pipeline id]
  (swap! running-pipelines assoc pipeline #{id}))


(defrecord PipelineController [params-fn running-pipelines pipelines]
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
          (let [id (gensym command)]
            (pipeline {:register! #(pipeline-register! running-pipelines command id)
                       :running? #(pipeline-running? running-pipelines command id)
                       :stop! #(pipeline-stop! running-pipelines command id)
                       :exclusive! #(pipeline-exclusive! running-pipelines command id)}
                      this app-db-atom args)))
        (when command (recur))))))


(defn constructor [params-fn pipelines]
  (->PipelineController params-fn (atom {}) pipelines))




(defrecord PipelineController2 [params-fn pipelines]
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

(defn constructor2 [params-fn pipelines]
  (->PipelineController2 params-fn pipelines))
