(ns keechma.toolbox.forms-test
  (:require [cljs.test :refer-macros [deftest testing is async]]
            [keechma.toolbox.forms.core :as forms-core]
            [keechma.toolbox.forms.controller :as forms-controller]
            [keechma.toolbox.forms.mount-controller :as forms-mount-controller]
            [keechma.app-state :as app-state]
            [keechma.controller :as controller]
            [keechma.toolbox.pipeline.core :as pp :refer-macros [pipeline!]]
            [cljs.core.async :refer (timeout <!)]
            [keechma.toolbox.forms.app :refer [install]]
            [keechma.ui-component :as ui]
            [keechma.toolbox.test-util :refer [make-container]])
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

(deftest forms-for-route
  (is (= [[:foo :bar] [:foo :baz] [:qux :foo-1]]
         (forms-mount-controller/forms-for-route {} {} {:foo (fn [_ _] [:bar :baz])
                                                        :qux (fn [_ _] :foo-1)}))))


(defrecord Form1 [])

(def forms-install-forms {:form1 (->Form1)})
(def forms-install-forms-mount {:form1 (fn [_ _] :form)})

(deftest forms-install
  (let [[c unmount] (make-container)
        app (-> {:html-element c
                 :components {:main (ui/constructor {:renderer (fn [ctx] [:div])})}}
                (install forms-install-forms forms-install-forms-mount))]
    (is (= (keys (:controllers app))
           [:keechma.toolbox.forms.core/forms :keechma.toolbox.forms.mount-controller/id]))
    (is (= (keys (:subscriptions app))
           [:keechma.toolbox.forms.core/forms]))
    (doseq [[_ c] (:components app)]
      (is (= [:keechma.toolbox.forms.core/forms]
             (:subscription-deps c))))))


(deftest forms-install-without-mount-controller
  (let [[c unmount] (make-container)
        app (-> {:html-element c
                 :components {:main (ui/constructor {:renderer (fn [ctx] [:div])})}}
                (install forms-install-forms))]
    (is (= (keys (:controllers app))
           [:keechma.toolbox.forms.core/forms]))
    (is (= (keys (:subscriptions app))
           [:keechma.toolbox.forms.core/forms]))
    (doseq [[_ c] (:components app)]
      (is (= [:keechma.toolbox.forms.core/forms]
             (:subscription-deps c))))))
