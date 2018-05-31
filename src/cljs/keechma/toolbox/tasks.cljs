(ns keechma.toolbox.tasks
  (:require [cljs.core.async :refer [<! put! take! chan close! timeout]]
            [medley.core :refer [dissoc-in]]
            [promesa.core :as p]
            [keechma.toolbox.pipeline.core :as pp])
  (:import [goog.async AnimationDelay Throttle])
  (:require-macros [cljs.core.async.macros :refer [go-loop]]))

(defrecord TaskStateUpdate [app-db id state])

(defn raf-producer [res-chan _]
  (let [is-running? (atom true)
        wait-delay (fn wait-delay []
                     (.start (AnimationDelay.
                             (fn [val]
                               (put! res-chan val)
                               (when @is-running? (wait-delay))))))]
    (wait-delay)
    (fn []
      (reset! is-running? false)
      nil)))

(defn throttle [f interval]
  (let [t (Throttle. f interval)]
    (fn [& args] (.apply (.-fire t) t (to-array args)))))

(defn app-db-change-producer [res-chan app-db-watcher]
  (let [throttled-put! (throttle #(put! res-chan true) 1)]
    (app-db-watcher
     (fn [data]
       (throttled-put! data)))
    (fn [])))

(defn finish-task! [app-db id state]
  (let [current (get-in app-db [:kv ::tasks id])
        version (:version current)
        stopper (get-in current [:stoppers version])]
    (dissoc-in (if stopper (stopper app-db state) app-db) [:kv ::tasks id])))

(defn make-app-db-watcher [app-db-atom watcher-id]
  (fn [cb]
    (add-watch app-db-atom watcher-id
               (fn [_ _ old-state new-state]
                 (when (not= old-state new-state)
                   (cb new-state))))))

(defn clear-task-version [app-db id version]
  (-> app-db
      (dissoc-in [:kv ::tasks id :states version])
      (dissoc-in [:kv ::tasks id :stoppers version])))


(defn register-task!
  ([app-db-atom id producer reducer resolve runner-chan]
   (let [version (gensym id)
         watcher-id [id version]
         finisher (producer runner-chan (make-app-db-watcher app-db-atom watcher-id))
         stopper (fn [app-db state]
                   (close! runner-chan)
                   (remove-watch app-db-atom watcher-id)

                   (if (= ::stopped state)
                     (resolve)
                     (resolve :keechma.toolbox.pipeline.core/break))

                   (if-let [finisher-res (finisher)]
                     (let [new-app-db (reducer {:state state :value finisher-res :id id} app-db)]
                       (if (instance? TaskStateUpdate new-app-db)
                         (throw (ex-info "It's impossible to change task state when the task is not running"
                                         {:task id :state state}))
                         (clear-task-version new-app-db id version)))
                     (clear-task-version app-db id version)))]

     (reset! app-db-atom (-> (finish-task! @app-db-atom id ::cancelled)
                             (assoc-in [:kv ::tasks id :version] version)
                             (assoc-in [:kv ::tasks id :states version] ::running)
                             (assoc-in [:kv ::tasks id :stoppers version] stopper)))
     {:stopper stopper
      :version version})))


(defn update-task-state! [state]
  (fn [app-db id]
    (pp/commit! (finish-task! app-db id state))))

(def stop-task! (update-task-state! ::stopped))
(def cancel-task! (update-task-state! ::cancelled))


(defn update-task-state [state]
  (fn [app-db id]
    (->TaskStateUpdate app-db id state)))

(def stop-task (update-task-state ::stopped))
(def cancel-task (update-task-state ::cancelled))

(defn update-app-db-atom! [payload app-db-atom]
  (if (nil? payload)
    app-db-atom
    (reset! app-db-atom payload)))

(defn ex-task-cancelled [id version]
  (ex-info "Task cancelled" {::task {:id id :version version :state ::cancelled}}))

(defn task-running? [app-db id]
  (let [task (get-in app-db [:kv ::tasks id])
        current-version (:version task)]
    (not (nil? (get-in task [:states current-version])))))

(defn task-loop [{:keys [producer reducer ctrl app-db-atom value resolve reject id]}]
  (let [runner-chan (chan)
        {:keys [stopper version]} (register-task! app-db-atom id producer reducer resolve runner-chan)]
    (let [started-at (.getTime (js/Date.))]
      (go-loop [times-invoked 0]
        (when-let [runner-value (<! runner-chan)]
              (let [app-db @app-db-atom
                    reducer-result (reducer {:times-invoked times-invoked
                                             :started-at started-at
                                             :id id
                                             :value runner-value
                                             :state ::running}
                                            app-db)
                    task-state-update? (instance? TaskStateUpdate reducer-result)]
                (if task-state-update?
                  (reset! app-db-atom (finish-task! (:app-db reducer-result) (:id reducer-result) (:state reducer-result)))
                  (do
                    (when (not= app-db-atom reducer-result)
                      (reset! app-db-atom reducer-result))
                    (recur (inc times-invoked))))))))))


(defn blocking-task-producer [producer id reducer ctrl app-db-atom value]
  (p/promise (fn [resolve reject]
               (task-loop {:reducer reducer
                           :producer producer
                           :ctrl ctrl
                           :app-db-atom app-db-atom
                           :value value
                           :resolve resolve
                           :reject reject
                           :id id}))))

(defn blocking-task! [producer id reducer]
  (with-meta (partial blocking-task-producer producer id reducer) {:pipeline? true}))

(defn non-blocking-task-producer [producer id reducer ctrl app-db-atom value]
  (task-loop {:reducer reducer
              :producer producer
              :ctrl ctrl
              :app-db-atom app-db-atom
              :value value
              :resolve identity
              :reject identity
              :id id})
  nil)

(defn non-blocking-task! [producer id reducer]
  (with-meta (partial non-blocking-task-producer producer id reducer) {:pipeline? true}))

(def blocking-raf! (partial blocking-task! raf-producer))
(def non-blocking-raf! (partial non-blocking-task! raf-producer))

(defn block-until! [id predicate-fn]
  (blocking-task! app-db-change-producer id
                  (fn [_ app-db]
                    (let [res (predicate-fn app-db)]
                      (if res
                        (stop-task app-db id)
                        app-db)))))
