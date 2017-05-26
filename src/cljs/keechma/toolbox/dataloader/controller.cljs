(ns keechma.toolbox.dataloader.controller
  (:require [keechma.controller :as controller]
            [cljs.core.async :refer [<! close! put! chan]]
            [keechma.toolbox.dataloader.core :as dataloader]
            [keechma.toolbox.pipeline.core :as pp :refer-macros [pipeline!]]
            [promesa.core :as p])
  (:require-macros [cljs.core.async.macros :refer [go-loop go]]))

(defn chan->promise [wait-chan value]
  (p/promise (fn [resolve reject]
               (go
                 (<! wait-chan)
                 (resolve)))))

(defn wait-dataloader-pipeline! []
  (let [wait-chan (chan)]
    (pipeline! [value app-db]
      (pp/send-command! [dataloader/id-key :waits] wait-chan)
      (chan->promise wait-chan value))))

(defrecord Controller [dataloader]
  controller/IController
  (params [this route-params]
    (:data route-params))
  (start [this route-params app-db]
    (controller/execute this :load-data)
    app-db)
  (handler [this app-db-atom in-chan out-chan]
    (go-loop [waits []]
      (let [[command args] (<! in-chan)]
        (when command
          (case command
            :load-data (do (->> (dataloader app-db-atom)
                                (p/map #(controller/execute this :loaded-data)))
                           (recur waits))
            :loaded-data (do
                           (doseq [c waits] (close! c))
                           (recur []))
            :waits (recur (conj waits args))
            (recur waits)))))))


(defn constructor [datasources edb-schema]
  (->Controller (dataloader/make-dataloader datasources edb-schema)))

(defn register
  ([datasources edb-schema] (register {} datasources edb-schema))
  ([controllers datasources edb-schema]
   (assoc controllers dataloader/id-key (constructor datasources edb-schema))))
