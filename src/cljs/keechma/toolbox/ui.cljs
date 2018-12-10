(ns keechma.toolbox.ui
  (:require [keechma.ui-component :as ui]))

(defn sub>
  "Read and defer a component subscription"
  [ctx subscription & args]
  (deref (ui/subscription ctx subscription args)))

(defn <cmd
  "Send a command to the controller"
  [ctx command & args]
  (apply ui/send-command ctx command args))

(defn route>
  "Read current route data. Derefs the route subscription"
  [ctx]
  (:data (deref (ui/current-route ctx))))

(defn memoize-cmd [result-fn {:keys [ctx command args]}]
  (let [app-db (:app-db ctx)
        cache-key (hash [ctx command args])
        existing (get-in @app-db [:internal :ui-fn-cache cache-key])]
    (if existing
      existing
      (do
        (swap! app-db assoc-in [:internal :ui-fn-cache cache-key] result-fn)
        result-fn))))

(defn memoize-redirect
  ([ctx args] (memoize-redirect ctx args nil)) 
  ([ctx args action]
   (let [app-db (:app-db ctx)
         cache-key (hash [ctx args action])
         existing (get-in @app-db [:internal :ui-fn-cache cache-key])
         result-fn #(ui/redirect ctx args action)]
     (if existing
       existing
       (do
         (swap! app-db assoc-in [:internal :ui-fn-cache cache-key] result-fn)
         result-fn)))))
