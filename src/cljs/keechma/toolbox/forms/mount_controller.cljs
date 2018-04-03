(ns keechma.toolbox.forms.mount-controller
  (:require [keechma.controller :as controller]
            [keechma.toolbox.forms.core :refer [id-key]]
            [cljs.core.async :refer (<! put!)]
            [clojure.set :as set])
  (:require-macros [cljs.core.async.macros :refer [go-loop]]))

(defrecord Controller [forms-params])

(defn forms-for-route [route app-db forms-params]
  (reduce (fn [acc [form params-fn]]
         (if-let [id (params-fn route app-db)]
           (reduce (fn [inner-acc inner-id]
                     (conj inner-acc [form inner-id]))
                   acc (flatten [id])) 
           acc))
       [] forms-params))

(defn mount-forms [controller app-db-atom route mounted-forms]
  (let [forms-params (:forms-params controller)
        should-be-mounted-forms (set (remove nil? (forms-for-route route @app-db-atom forms-params)))
        forms-to-unmount (set/difference mounted-forms should-be-mounted-forms)
        forms-to-mount (set/difference should-be-mounted-forms mounted-forms)]
    
    (doseq [f forms-to-unmount]
      (controller/send-command controller [id-key :unmount-form] f))
    (doseq [f forms-to-mount]
      (controller/send-command controller [id-key :mount-form] f))))

(defn get-mounted-forms [app-db]
  (set (get-in app-db [:kv id-key :order])))

(defmethod controller/params Controller [this route]
  (:data route))

(defmethod controller/handler Controller [this app-db-atom in-chan out-chan]
  (mount-forms this app-db-atom (:params this) (get-mounted-forms @app-db-atom))
  (go-loop []
    (let [[command args] (<! in-chan)]
      (case command
        :mount-forms (mount-forms this app-db-atom args (get-mounted-forms @app-db-atom))
        :route-changed (mount-forms this app-db-atom (:data args) (get-mounted-forms @app-db-atom))
        nil)
      (when command
        (recur)))))

(defn constructor [forms-params]
  (->Controller forms-params))

(defn register
  ([forms-params] (register {} forms-params))
  ([controllers forms-params]
   (assoc controllers ::id (constructor forms-params))))
