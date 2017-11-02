(ns keechma.toolbox.forms-test
  (:require [cljs.test :refer-macros [deftest testing is async]]
            [keechma.toolbox.forms.core :as forms-core]
            [keechma.toolbox.forms.controller :as forms-controller]
            [keechma.app-state :as app-state]
            [keechma.controller :as controller]
            [keechma.toolbox.pipeline.core :as pp :refer-macros [pipeline!]]
            [cljs.core.async :refer (timeout <!)])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defrecord Form [])

(defmethod forms-core/get-data Form [this app-db form-props]
  {:inited true})

(defmethod forms-core/on-mount Form [this app-db form-props]
  (pipeline! [value app-db]
    (pp/commit! (assoc-in app-db [:kv :on-mount] form-props))))

(defmethod forms-core/on-unmount Form [this app-db form-props]
  (pipeline! [value app-db]
    (pp/commit! (assoc-in app-db [:kv :on-unmount] form-props))))

(defmethod forms-core/call Form [this app-db form-props args]
  (pipeline! [value app-db]
    (pp/commit! (assoc-in app-db [:kv :call form-props] args))))

(defn add-mount-target-el []
  (let [div (.createElement js/document "div")]
    (.appendChild (.-body js/document) div)
    div))

(deftest form-flow []
  (async done
         (let [target-el (add-mount-target-el)
               app {:controllers (forms-controller/register {:form (->Form)})
                    :components  {:main {:renderer (fn [ctx])}}
                    :html-element target-el}
               app-state (app-state/start! app)
               app-db (:app-db app-state)
               form-controller (get-in @app-db [:internal :running-controllers forms-core/id-key])]
           (go
             (controller/execute form-controller :mount-form [:form :id])
             (<! (timeout 1))
             (is (= {:keechma.toolbox.forms.core/forms
                     {:order [[:form :id]]
                      :states {[:form :id] {:submit-attempted? false
                                            :dirty-paths #{}
                                            :cached-dirty-paths #{}
                                            :data {:inited true}
                                            :initial-data {:inited true}
                                            :errors {}
                                            :state {:type :mounted}}}}
                     :on-mount [:form :id]}
                    (:kv @app-db)))
             (controller/execute form-controller :call [[:form :id] {:foo :bar}])
             (<! (timeout 1))
             (is (= {:keechma.toolbox.forms.core/forms
                     {:order [[:form :id]]
                      :states {[:form :id] {:submit-attempted? false
                                            :dirty-paths #{}
                                            :cached-dirty-paths #{}
                                            :data {:inited true}
                                            :initial-data {:inited true}
                                            :errors {}
                                            :state {:type :mounted}}}}
                     :call {[:form :id] {:foo :bar}}
                     :on-mount [:form :id]}
                    (:kv @app-db)))
             (controller/execute form-controller :unmount-form [:form :id])
             (<! (timeout 1))
             (is (= {:keechma.toolbox.forms.core/forms {:order []
                                                        :states {}}
                     :call {[:form :id] {:foo :bar}}
                     :on-mount [:form :id]
                     :on-unmount [:form :id]}
                    (:kv @app-db)))
             (done)))))
