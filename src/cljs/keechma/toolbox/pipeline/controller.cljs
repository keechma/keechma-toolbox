(ns keechma.toolbox.pipeline.controller
  (:require [keechma.controller :as controller]
            [cljs.core.async :refer [<!]]
            [keechma.toolbox.pipeline.core :refer [run-pipeline]]
            [clojure.set :as set])
  (:require-macros [cljs.core.async.macros :refer [go-loop]]))

(def pipeline-rename-map {:start         :on-start
                          :stop          :on-stop
                          :route-changed :on-route-changed})

(defn get-pipeline [pipelines pipeline-name]
  (let [pipeline (get pipelines pipeline-name)]
    (if (keyword? pipeline)
      (get pipelines pipeline)
      pipeline)))

(defrecord PipelineController [controller-api pipelines])

(defmethod controller/params PipelineController [this route-params]
  (let [params-fn (get-in this [:controller-api :params])]
    (params-fn route-params)))
(defmethod controller/start PipelineController [this params app-db]
  (let [start-fn (get-in this [:controller-api :start])]
    (controller/execute this :start params)
    (start-fn this params app-db)))
(defmethod controller/stop PipelineController [this params app-db]
  (let [stop-fn (get-in this [:controller-api :stop])]
    (controller/execute this :stop params)
    (stop-fn this params app-db)))
(defmethod controller/handler PipelineController [this app-db-atom in-chan _]
  (go-loop []
    (let [[command args] (<! in-chan)
          pipeline-name (or (get pipeline-rename-map command) command)]
      (when-let [pipeline (get-pipeline (:pipelines this) pipeline-name)]
        (pipeline this app-db-atom args))
      (when command (recur)))))

(def default-start-stop (fn [this params app-db] app-db))
(def default-params (fn [route-params] nil))

(defn prepare-controller-api [controller-api]
  (merge {:stop   default-start-stop 
          :start  default-start-stop 
          :params default-params}
         (if (fn? controller-api)
           {:params controller-api}
           controller-api)))


(defn redundant-pipeline-message [pipeline]
  (str "You can't have `:" pipeline "` "
       "and `:on-" pipeline "` "
       "pipeline defined. Use `:on-" pipeline "`, `:" pipeline "` is deprecated."))

(defn prepare-pipelines [pipelines]
  (when (and (:stop pipelines)
             (:on-stop pipelines))
    (throw (ex-info (redundant-pipeline-message "stop") {})))
  (when (and (:start pipelines)
             (:on-start pipelines))
    (throw (ex-info (redundant-pipeline-message "start") {})))
  (when (and (:route-changed pipelines)
             (:on-route-changed pipelines))
    (throw (ex-info (redundant-pipeline-message "route-changed") {})))
  (set/rename-keys pipelines {:stop          :on-stop
                              :start         :on-start
                              :route-changed :on-route-changed}))



(defn make-constructor [controller-api pipelines]
  (fn []
    (->PipelineController (prepare-controller-api controller-api) (prepare-pipelines pipelines))))


(defn constructor
  "
Constructor for the pipeline controller.

Pipeline controllers are special type of Keechma controllers that handle their commands with pipelines. Pipelines allow you to model a command / action as a series of steps. Pipelines know how to handle promises and they themselves return a promise.

Pipelines allow you to split async actions into distinct steps:

- Pipeline processing
- Sideffects

If a pipeline processing step returns a promise (for instance from an AJAX request), pipeline will wait until that promise is resolved before proceeding to the next step.

Simple example

```clojure
(ns some-namespace
  (:require [keechma.toolbox.pipeline.controller :as pp-controller]
            [keechma.toolbox.pipeline.core :as pp :refer-macros [pipeline!]])

(def some-controller
  (fn [route-params] true) ;; route params function
  {:start (pipeline! [value app-db]
            (pp/commit! (assoc-in app-db [:kv :current-user-status] :loading))
            (load-user-from-server)
            (pp/commit! (->app-db
                           (assoc-in [:kv :current-user-status] :loaded)
                           (assoc-in [:kv :current-user] value))))})
```

In the previous example three things happened:

1. We stored current user status as `:loading` in the app-db
2. We loaded user from the server
3. We stored the current user status and the current user in the app-db

`value` always holds the returned (or resolved) value from the previous processing step - unless the function returns `nil` in that case, value is bound to the previous value. Also, we had to use the `commit!` sideffect function to mutate the app-db.

`value` and `app-db` arguments are always bound to the current pipeline value and to the current version of app-db. This means that in every processing step, value and app-db point to the results of the previous processing step.`

If we want to handle errors or rejections in pipelines, we can use the `rescue!` block:

```clojure
(def some-controller
  (fn [route-params] true) ;; route params function
  {:start (pipeline! [value app-db]
            (pp/commit! (assoc-in app-db [:kv :current-user-status] :loading))
            (load-user-from-server)
            (pp/commit! (->app-db
                           (assoc-in [:kv :current-user-status] :loaded)
                           (assoc-in [:kv :current-user] value)))
            (rescue! [error]
              (pp/commit! (assoc-in app-db [:kv :current-user-status] :error))})
```

Rescue block will catch any errors that happen in the pipeline - including the promises that were rejected. Steps in the rescue block have access to value and app-db arguments.

**Nesting pipelines**

Sometimes you need to run the pipeline only if some condition is true. In that case you can nest pipelines:

```clojure
(def some-controller
  (fn [route-params] true) ;; route params function
  {:start (pipeline! [value app-db]
            (when (= value true)
              (pipeline! [value app-db]
                (some-api-call)
                (pp/commit! (mutate-app-db app-db)))))}) 
```

**Pipeline sideffects**

Pipelines can have various sideffects:

1. `commit!` - commits the new app-db version in the global app-db atom
2. `send-command!` - sends command to another controller
3. `execute!` - executes command in the current controller
4. `redirect!` - redirects to a different URL

**Exclusive pipelines**

In some cases you want to always run only one pipeline at a time. One example of that behavior is the live search. 

Let's say that you want to implement it as a command that runs on every key press. After the command is ran, it should wait for 500msec before calling the API. If the command is called again in these 500msec, stop the pipeline and run a new one.

Here's how you would implement this feature:

```clojure
(def search-controller
  (pp-controller/constructor
   (fn [] true)
   {:search (pp/exclusive
             (pipeline! [value app-db]
               (when-not (empty? value)
                 (pipeline! [value app-db]
                   (delay-pipeline 500)
                   (movie-search value)
                   (println \"SEARCH RESULTS:\" value)))))}))
```
  "
  [controller-api pipelines]
  (let [c (make-constructor controller-api pipelines)]
    (c)))
