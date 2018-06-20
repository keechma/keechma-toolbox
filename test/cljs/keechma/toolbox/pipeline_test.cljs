(ns keechma.toolbox.pipeline-test
  (:require [cljs.test :refer-macros [deftest testing is async]]
            [keechma.toolbox.pipeline.controller :as pp-controller]
            [keechma.toolbox.pipeline.core :as pp :refer-macros [pipeline!]]
            [keechma.app-state :as app-state]
            [promesa.core :as p]
            [cljs.core.async :refer [<! >! chan close! put! alts! timeout]]
            [promesa.impl :refer [Promise]])
  (:require-macros [cljs.core.async.macros :as m :refer [go alt!]]))

(.config Promise #js {:cancellation true})

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

(defn is-returning-nil [check]
  (is check)
  nil)

(defn make-basic-pipeline-controller [done]
  (pp-controller/constructor
   (fn [_] true)
   {:start (pipeline! [value app-db]
             (println "STARTING")
             (pp/commit! (assoc-in app-db [:kv :count] 1))
             (is (= 1 (get-in app-db [:kv :count])))
             (.getTime (js/Date.))
             (delay-pipeline)
             (is (<= 10 (- (.getTime (js/Date.)) value)) 20)
             (pp/commit! (assoc-in app-db [:kv :count] (inc (get-in app-db [:kv :count]))))
             (is (= 2 (get-in app-db [:kv :count])))
             {:foo :bar}
             (is-returning-nil (= {:foo :bar} value))
             nil
             (is-returning-nil (= {:foo :bar} value))
             (fn-returning-value)
             (is-returning-nil (= {:baz :qux} value))
             (fn-returning-nil)
             (is-returning-nil (= {:baz :qux} value))
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
             (pp/reroute!)
             (delay-pipeline 10)
             (is (= (get-in app-db [:kv :some-action-payload]) :some-payload))
             (pp/run-pipeline! :some-action-2 :some-payload-2)
             (is (= (get-in app-db [:kv :some-action-2-payload]) :some-payload-2))
             (pp/do!
              (pp/commit! (assoc-in app-db [:kv :count] 3))
              (pp/redirect! {:foo "bar"}))
             
             (is (= (get-in app-db [:kv :count] 3)))
             (is (= "#!?foo=bar" (.-hash js/location)))

             (pp/redirect! {:bar "baz"})
             (is (= "#!?bar=baz" (.-hash js/location)))

             (pp/redirect! {:baz "qux"})
             (is (= "#!?baz=qux" (.-hash js/location)))

             (pp/redirect! nil :back)
             ;; (is (= "#!?bar=baz" (.-hash js/location))) ;; TODO: Figure out why this is failing (history.length === 1)

             (pp/send-command! [:receiver :receive] :receiver-payload)
             (delay-pipeline 1)
             (is (= :receiver-payload (get-in app-db [:kv :receiver-payload])))
             (done))
    :some-action-2 (pipeline! [value app-db]
                     (pp/commit! (assoc-in app-db [:kv :some-action-2-payload] value)))
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

(def promise-error (js/Error. "Promise Rejected"))

(defn fn-promise-rejecting []
  (p/promise (fn [_ reject]
               (reject promise-error))))

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
               (is (= (:payload error) promise-error))
               (done)))}))

(deftest promise-rejecting-pipeline
  (async done
         (let [target-el (add-mount-target-el)
               app {:controllers {:basic (make-fn-promise-rejecting-pipeline-controller done)}
                    :components  app-components
                    :html-element target-el}]
           (app-state/start! app))))

(defn cancelable-promise [called-atom]
  (p/promise (fn [resolve reject on-cancel]
               (when (fn? on-cancel) (on-cancel #(swap! called-atom inc)))
               (js/setTimeout resolve 50))))

(defn make-exclusive-pipeline-controller [called-atom done]
  (pp-controller/constructor
   (fn [_] true)
   {:start (pipeline! [value app-db]
             (pp/execute! :exclusive true)
             (pp/execute! :exclusive true)
             (pp/execute! :exclusive true))
    :exclusive (pp/exclusive
                (pipeline! [value app-db]
                  (cancelable-promise called-atom)
                  (is (= 2 @called-atom))
                  (pp/commit! (assoc-in app-db [:kv :count] (inc (or (get-in app-db [:kv :count]) 0))))
                  (is (= 1 (get-in app-db [:kv :count])))
                  (done)))}))

(deftest exclusive-pipeline
  (async done
         (let [target-el (add-mount-target-el)
               app {:controllers {:basic (make-exclusive-pipeline-controller (atom 0) done)}
                    :components  app-components
                    :html-element target-el}
               _ (js/setTimeout done 9000)]
           (app-state/start! app))))


(defn run-pipeline-breaks-into-rescue-if-pipeline-doesnt-exist [done]
  (pp-controller/constructor
   (fn [_] true)
   {:start (pipeline! [value app-db]
             (pp/run-pipeline! :foo)
             (is false "This shouldn't run")
             (done)
             (rescue! [error]
               (is true)
               (done)))}))

(deftest run-pipeline-breaks-into-rescue-if-pipeline-doesnt-exist-test
  (async done
         (let [target-el (add-mount-target-el)
               app {:controllers {:basic (run-pipeline-breaks-into-rescue-if-pipeline-doesnt-exist done)}
                    :components  app-components
                    :html-element target-el}]
           (app-state/start! app))))


(defn pipelines-new-api [done]
  (pp-controller/constructor
   {:params (fn [route-params]
              (when (get-in route-params [:data :new-api])
                true))
    :start (fn [_ _ app-db]
             (assoc-in app-db [:kv :log] [:start]))
    :stop (fn [_ _ app-db]
            (update-in app-db [:kv :log] #(conj % :stop)))}
   {:on-start (pipeline! [value app-db]
                (pp/commit! (update-in app-db [:kv :log] #(conj % :on-start))))
    :on-stop (pipeline! [value app-db]
               (pp/commit! (update-in app-db [:kv :log] #(conj % :on-stop)))
               (is (= (get-in app-db [:kv :log])
                      [:start :on-start :on-route-changed :stop :on-stop]))
               (done)
               (rescue! [error]
                 (is false)
                 (done)))
    :on-route-changed (pipeline! [value app-db]
                        (pp/commit! (update-in app-db [:kv :log] #(conj % :on-route-changed))))}))

(deftest pipelines-new-api-test
  (async done
         (set! (.-hash js/location) "#!?new-api=1")
         (let [target-el (add-mount-target-el)
               app {:controllers {:basic (pipelines-new-api done)}
                    :components app-components
                    :html-element target-el}]
           (app-state/start! app)
           (js/setTimeout #(set! (.-hash js/location) "#!?new-api=2") 10)
           (js/setTimeout #(set! (.-hash js/location) "") 20))))

(defn pipelines-broadcast-controllers [log]
  {:broadcaster (pp-controller/constructor
                 (constantly true)
                 {:on-start (pipeline! [value app-db]
                              (pp/broadcast! ::command ::payload)
                              (swap! log inc))})
   :broadcast-client (pp-controller/constructor
                      (constantly true)
                      {::command (pipeline! [value app-db]
                                   (is (= value ::payload))
                                   (swap! log inc))})})

(deftest pipelines-broadcast
  (async done
         (let [log (atom 0)
               target-el (add-mount-target-el)
               app {:controllers (pipelines-broadcast-controllers log) 
                    :components app-components
                    :html-element target-el}]
           (app-state/start! app)
           (go
             (<! (timeout 10))
             (is (= 2 @log))
             (done)))))
