(ns keechma.toolbox.pipeline-test
  (:require [cljs.test :refer-macros [deftest testing is async]]
            [keechma.toolbox.pipeline.controller :as pp-controller]
            [keechma.toolbox.pipeline.core :as pp :refer-macros [pipeline!]]
            [keechma.app-state :as app-state]
            [promesa.core :as p]
            [cljs.core.async :refer [<! >! chan close! put! alts! timeout]]
            [keechma.toolbox.tasks :as t])
  (:require-macros [cljs.core.async.macros :as m :refer [go alt!]]))


(def app-components
  {:main {:renderer (fn [_] [:div])}})

(defn delay-pipeline
  ([] (delay-pipeline 10))
  ([msec] (p/create (fn [resolve _] (js/setTimeout resolve msec)))))

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

             (println "1" (.-hash js/location))
             (pp/redirect! nil :back)
             (println "2" (.-hash js/location))
             ;;(is (= "#!?bar=baz" (.-hash js/location))) ;; TODO: Figure out why this is failing (history.length === 1)

             (pp/send-command! [:receiver :receive] :receiver-payload)
             (delay-pipeline 1)
             (is (= :receiver-payload (get-in app-db [:kv :receiver-payload])))
             
             (pp/execute! :some-action-3 "THIS IS PROXIED THROUGH SOME ACTION 3")
             (delay-pipeline 10)
             
             (is (= "THIS IS PROXIED THROUGH SOME ACTION 3" (get-in app-db [:kv :some-action-4-payload])))
             
             (done))
    :some-action-3 :some-action-4
    :some-action-4 (pipeline! [value app-db]
                     (pp/commit! (assoc-in app-db [:kv :some-action-4-payload] value)))
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
  (p/create (fn [_ reject]
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

(defn make-exclusive-pipeline-controller [called-atom done]
  (pp-controller/constructor
   (fn [_] true)
   {:start (pipeline! [value app-db]
             (pp/execute! :exclusive true)
             (delay-pipeline 1)
             (pp/execute! :exclusive true)
             (delay-pipeline 1)
             (pp/execute! :exclusive true))
    :exclusive (pp/exclusive
                (pipeline! [value app-db]
                  (swap! called-atom inc)
                  (delay-pipeline 50)
                  (is (= 3 @called-atom))
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


(defn make-nested-exclusive-pipeline-controller [called-atom done]
  (pp-controller/constructor
   (fn [_] true)
   {:start (pipeline! [value app-db]
             (pp/execute! :exclusive true)
             (delay-pipeline 10)
             (pp/execute! :exclusive true)
             (delay-pipeline 10)
             (pp/execute! :exclusive true))
    :exclusive (pp/exclusive
                (pipeline! [value app-db]
                  (swap! called-atom inc)
                  (pipeline! [value app-db]
                    (delay-pipeline 50)
                    (is (= 3 @called-atom))
                    (pp/commit! (assoc-in app-db [:kv :count] (inc (or (get-in app-db [:kv :count]) 0))))
                    (is (= 1 (get-in app-db [:kv :count])))
                    (done))))}))

(deftest nested-exclusive-pipeline
  (async done
         (let [target-el (add-mount-target-el)
               app {:controllers {:basic (make-nested-exclusive-pipeline-controller (atom 0) done)}
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
               "-"
               (js/setTimeout #(done) 10)
               ))}))

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

(defn throwing-fn [msg]
  (throw (ex-info msg {})))

(defn nested-pipeline-rescue-block-controller [log]
  (pp-controller/constructor
   (constantly true)
   {:on-start (pipeline! [value app-db]
                (pipeline! [value app-db]
                  (throwing-fn "#1")
                  (rescue! [error]
                    (throwing-fn "#2")))
                (rescue! [error]
                  (swap! log inc)
                  (is (= "#2" (.-message (:payload error))))))}))

(deftest nested-pipeline-rescue-block
  (async done
         (let [log (atom 0)
               target-el (add-mount-target-el)
               app {:controllers {:troublemaker (nested-pipeline-rescue-block-controller log)} 
                    :components app-components
                    :html-element target-el}]
           (app-state/start! app)
           (go
             (<! (timeout 10))
             (is (= 1 @log))
             (done)))))

(defn wait-pipeline-controller []
  (pp-controller/constructor
   (constantly true)
   {:on-start (pipeline! [value app-db]
                (pp/execute! :runner)
                (pp/execute! :waiter))
    :runner (pipeline! [value app-db]
              (delay-pipeline 50)
              (pp/commit! (assoc-in app-db [:kv :runner] true)))
    :waiter (pipeline! [value app-db]
              (pp/wait-pipelines!
               (fn [pipelines]
                 (filter
                  (fn [p]
                    (let [[name _] (:id p)]
                      (= name :runner)))
                  pipelines)))
              (is (true? (get-in app-db [:kv :runner]))))}))

(deftest wait-pipeline
  (async done
         (let [target-el (add-mount-target-el)
               app {:controllers {:waiter (wait-pipeline-controller)} 
                    :components app-components
                    :html-element target-el}]
           (app-state/start! app)
           (go
             (<! (timeout 100))
             (done)))))


(defn cancel-pipeline-controller []
  (pp-controller/constructor
   (constantly true)
   {:on-start (pipeline! [value app-db]
                (pp/execute! :runner)
                (pp/execute! :waiter))
    :runner (pipeline! [value app-db]
              (delay-pipeline 10)
              (pp/commit! (assoc-in app-db [:kv :runner] true)))
    :waiter (pipeline! [value app-db]
              (pp/cancel-pipelines!
               (fn [pipelines]
                 (filter
                  (fn [p]
                    (let [[name _] (:id p)]
                      (= name :runner)))
                  pipelines)))
              (delay-pipeline 50)
              (is (nil? (get-in app-db [:kv :runner]))))}))

(deftest cancel-pipeline
  (async done
         (let [target-el (add-mount-target-el)
               app {:controllers {:canceler (cancel-pipeline-controller)} 
                    :components app-components
                    :html-element target-el}]
           (app-state/start! app)
           (go
             (<! (timeout 100))
             (done)))))

(defn blocking-task-controller []
  (pp-controller/constructor
   (constantly true)
   {:on-start (pipeline! [value app-db]
                (pp/execute! :runner)
                (pp/execute! :waiter))
    :runner (pipeline! [value app-db]
              (t/blocking-task!
               t/app-db-change-producer
               :runner-task
               (fn [{:keys [id]} app-db]
                 (if (get-in app-db [:kv :continue-runner])
                   (t/stop-task (assoc-in app-db [:kv :runner] true) id)
                   app-db)))
              (is false "Shouldn't be here"))
    :waiter (pipeline! [value app-db]
              (delay-pipeline 1)
              (pp/commit! (assoc-in app-db [:kv :something] true))
              (delay-pipeline 1)
              (pp/cancel-pipelines!
               (fn [pipelines]
                 (filter
                  (fn [p]
                    (let [[name _] (:id p)]
                      (= name :runner)))
                  pipelines)))
              (pp/commit! (assoc-in app-db [:kv :continue-runner] true))
              (delay-pipeline 1)
              (is (nil? (get-in app-db [:kv :runner]))))}))

(deftest blocking-task-cancellation-on-pipeline-cancellation
  (async done
         (let [target-el (add-mount-target-el)
               app {:controllers {:blocker (blocking-task-controller)} 
                    :components app-components
                    :html-element target-el}]
           (app-state/start! app)
           (go
             (<! (timeout 100))
             (done)))))


(defn non-blocking-task-controller []
  (pp-controller/constructor
   (constantly true)
   {:on-start (pipeline! [value app-db]
                (pp/execute! :non-blocking-task) 
                (delay-pipeline 10)
                (pp/commit! (assoc-in app-db [:kv :continue-runner] true))
                (delay-pipeline 10)
                (is (true? (get-in app-db [:kv :runner]))))
    :non-blocking-task (pipeline! [value app-db]
                         (t/non-blocking-task!
                          t/app-db-change-producer
                          :runner-task
                          (fn [{:keys [id]} app-db]
                            (if (get-in app-db [:kv :continue-runner])
                              (t/stop-task (assoc-in app-db [:kv :runner] true) id)
                              app-db))))}))

(deftest non-blocking-task
  (async done
         (let [target-el (add-mount-target-el)
               app {:controllers {:blocker (non-blocking-task-controller)} 
                    :components app-components
                    :html-element target-el}]
           (app-state/start! app)
           (go
             (<! (timeout 100))
             (done)))))

(defrecord MinimalPipelineController [])

(def minimal-pipeline
  (pipeline! [value app-db]
    1))

(deftest minimal-pipeline-test-1
  (async done
         (->> (minimal-pipeline (->MinimalPipelineController) (atom nil) nil)
              (p/map (fn [res]
                       (is (= res 1))
                       (done)))
              (p/error (fn [err]
                         (is (= true false))
                         (done))))))

(deftest minimal-pipeline-test-2
  (async done
         (->> (minimal-pipeline (->MinimalPipelineController) (atom nil) nil nil)
              (p/map (fn [res]
                       (is (= res 1))
                       (done)))
              (p/error (fn [err]
                         (is (= true false))
                         (done))))))
