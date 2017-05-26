(ns keechma.toolbox.pipeline-test
  (:require [cljs.test :refer-macros [deftest testing is async]]
            [keechma.toolbox.pipeline.controller :as pp-controller]
            [keechma.toolbox.pipeline.core :as pp :refer-macros [pipeline!]]
            [keechma.app-state :as app-state]
            [promesa.core :as p]))

(def app-components
  {:main {:renderer (fn [_] [:div])}})

(defn delay-pipeline
  ([] (delay-pipeline 10))
  ([msec] (p/promise (fn [resolve _] (js/setTimeout resolve msec)))))

(defn fn-returning-value []
  {:baz :qux})

(defn fn-returning-nil []
  nil)

(defn add-mount-target-el []
  (let [div (.createElement js/document "div")]
    (.appendChild (.-body js/document) div)
    div))

(defn make-basic-pipeline-controller [done]
  (pp-controller/constructor
   (fn [_] true)
   {:start (pipeline! [value app-db]
             (pp/commit! (assoc-in app-db [:kv :count] 1))
             (is (= 1 (get-in app-db [:kv :count])))
             (.getTime (js/Date.))
             (delay-pipeline)
             (is (<= 10 (- (.getTime (js/Date.)) value)) 20)
             (pp/commit! (assoc-in app-db [:kv :count] (inc (get-in app-db [:kv :count]))))
             (is (= 2 (get-in app-db [:kv :count])))
             {:foo :bar}
             (= {:foo :bar} value)
             nil
             (= {:foo :bar} value)
             (fn-returning-value)
             (= {:baz :qux} value)
             (fn-returning-nil)
             (= {:baz :qux} value)
             (pipeline! [value app-db]
               (= {:baz :qux} value)
               (.getTime (js/Date.))
               (delay-pipeline))
             (is (<= 10 (- (.getTime (js/Date.)) value)) 20)
             (when true
               (pipeline! [value app-db]
                 {:inner :pipeline}))
             (is (= value {:inner :pipeline}))
             (pp/execute! :some-action :some-payload)
             (delay-pipeline 10)
             (is (= (get-in app-db [:kv :some-action-payload]) :some-payload))
             (pp/do!
              (pp/commit! (assoc-in app-db [:kv :count] 3))
              (pp/redirect! {:foo "bar"}))
             (is (= (get-in app-db [:kv :count] 3)))
             (is (= "#!?foo=bar" (.-hash js/location)))
             (pp/send-command! [:receiver :receive] :receiver-payload)
             (delay-pipeline 1)
             (is (= :receiver-payload (get-in app-db [:kv :receiver-payload])))
             (done))
    :some-action (pipeline! [value app-db]
                   (pp/commit! (assoc-in app-db [:kv :some-action-payload] value)))}))

(def message-receiver-controller
  (pp-controller/constructor
   (fn [_] true)
   {:receive (pipeline! [value app-db]
               (pp/commit! (assoc-in app-db [:kv :receiver-payload] value)))}))

(deftest basic-pipeline
  (async done
         (let [target-el (add-mount-target-el)
               app {:controllers {:basic    (make-basic-pipeline-controller done)
                                  :receiver message-receiver-controller}
                    :components  app-components
                    :html-element target-el}]
           (app-state/start! app))))

(defn fn-throwing []
  (throw (ex-info "ERROR" {:ex :info})))

(defn make-fn-throwing-pipeline-controller [done]
  (pp-controller/constructor
   (fn [_] true)
   {:start (pipeline! [value app-db]
             {:foo :bar}
             (pp/commit! (assoc-in app-db [:kv :value] :some-value))
             (fn-throwing)
             (done)
             (rescue! [error]
               (is (= {:foo :bar} value))
               (is (= :some-value (get-in app-db [:kv :value])))
               (is (= "ERROR" (.-message (:payload error))))
               (is (= {:ex :info} (.-data (:payload error))))
               (done)))}))

(deftest fn-throwing-pipeline
  (async done
         (let [target-el (add-mount-target-el)
               app {:controllers {:basic (make-fn-throwing-pipeline-controller done)}
                    :components  app-components
                    :html-element target-el}]
           (app-state/start! app))))


(defn fn-promise-rejecting []
  (p/promise (fn [_ reject]
               (reject {:promise :rejected}))))

(defn make-fn-promise-rejecting-pipeline-controller [done]
  (pp-controller/constructor
   (fn [_] true)
   {:start (pipeline! [value app-db]
             {:foo :bar}
             (pp/commit! (assoc-in app-db [:kv :value] :some-value))
             (fn-promise-rejecting)
             (done)
             (rescue! [error]
               (is (= {:foo :bar} value))
               (is (= :some-value (get-in app-db [:kv :value])))
               (is (= (:payload error) {:promise :rejected}))
               (done)))}))

(deftest promise-rejecting-pipeline
  (async done
         (let [target-el (add-mount-target-el)
               app {:controllers {:basic (make-fn-promise-rejecting-pipeline-controller done)}
                    :components  app-components
                    :html-element target-el}]
           (app-state/start! app))))
