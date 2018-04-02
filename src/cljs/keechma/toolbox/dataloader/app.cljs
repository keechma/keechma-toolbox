(ns keechma.toolbox.dataloader.app
  (:require [keechma.toolbox.dataloader.controller :as c]
            [keechma.toolbox.dataloader.subscriptions :as s]))

(defn install [app-config datasources edb-schema]
  (let [controllers (or (:controllers app-config) {})
        subscriptions (or (:subscriptions app-config) {})
        dataloader-subscriptions (s/make-subscriptions datasources edb-schema)]
    (-> app-config
        (assoc :controllers (c/register controllers datasources edb-schema))
        (assoc :subscriptions (merge dataloader-subscriptions subscriptions)))))
