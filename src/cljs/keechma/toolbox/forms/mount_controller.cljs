(ns keechma.toolbox.forms.mount-controller
  (:require [keechma.controller :as controller]
            [keechma.toolbox.forms.core :refer [id-key]]
            [cljs.core.async :refer (<! put!)])
  (:require-macros [cljs.core.async.macros :refer [go-loop]]))

(defn mount-forms [controller route]
  (let [forms-params (:forms-params controller)]
    (doseq [[form params-fn] forms-params]
      (let [id (params-fn route)]
        (when id
          (controller/send-command controller [id-key :mount-form] [form id]))))))

(defrecord Controller [forms-params]
  controller/IController
  (params [this route]
    (:data route))
  (start [this params app-db]
    (controller/execute this :mount-forms params)
    app-db)
  (handler [this app-db-atom in-chan out-chan]
    (go-loop []
      (let [[command args] (<! in-chan)]
        (case command
          :mount-forms (mount-forms this args)
          :route-changed (mount-forms this (:data args))
          nil)
        (when command
          (recur))))))

(defn constructor [form-params]
  (->Controller form-params))

(defn register
  ([form-params] (register {} form-params))
  ([controllers form-params]
   (assoc controllers ::id (constructor form-params))))
