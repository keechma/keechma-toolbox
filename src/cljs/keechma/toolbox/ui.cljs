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

(defn memoize-cmd [result-fn {:keys [ctx command id]}]
  (let [fn-cache (:fn-cache ctx)
        cache-key [ctx command id]
        existing (get @fn-cache cache-key)]
    (println "CMD >" id)
    (if existing
      existing
      (do
        (swap! fn-cache assoc cache-key result-fn)
        result-fn))))

(defn memoize-redirect
  ([result-fn {:keys [ctx id cache-fn]}]
   (let [fn-cache (:fn-cache ctx)
         cache-key [ctx id]
         existing (get @fn-cache cache-key)]
     (println "REDIRECT >" id (cache-fn))
     (if existing
       existing
       (do
         (swap! fn-cache assoc cache-key result-fn)
         result-fn)))))
