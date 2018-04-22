(ns keechma.toolbox.animations.core
  (:require [keechma.toolbox.animations.helpers :as helpers]
            [keechma.toolbox.animations.animator :as animator]
            [keechma.toolbox.tasks :refer [stop-task blocking-raf! non-blocking-raf! stop-task! cancel-task!]]
            [medley.core :refer [dissoc-in]]))

(defn dispatcher [meta & args] (:identifier meta))

(defmulti animator dispatcher)
(defmulti step dispatcher)
(defmulti done? dispatcher)
(defmulti values dispatcher)

(defmethod animator :default [_ _]
  (animator/->DefaultAnimator))

(defmethod done? :default [meta animator]
  (animator/done? animator))

(defmethod step :default [meta data]
  data)

(defmethod values :default [meta]
  {})

(defn identifier->id-state [identifier]
  (vec (map keyword [(namespace identifier) (name identifier)])))

(defn make-initial-meta
  ([identifier] (make-initial-meta identifier nil nil))
  ([identifier args] (make-initial-meta identifier args nil))
  ([identifier args prev]
   (let [[id state] (identifier->id-state identifier)]
     {:id id
      :state state
      :identifier identifier
      :position 0
      :times-invoked 0
      :prev prev
      :args args})))

(defn animation-id
  ([id] (animation-id id nil))
  ([id version]
   (if version
     (hash [id version])
     id)))

(defn app-db-animation-path [id version]
  [:kv :animations (animation-id id version)])

(defn get-animation-state
  ([app-db id] (get-animation-state app-db id nil))
  ([app-db id version]
   (get-in app-db (concat (app-db-animation-path id version) [:meta :state]))))

(defn get-animation
  ([app-db id] (get-animation app-db id nil))
  ([app-db id version]
   (get-in app-db (app-db-animation-path id version))))

(defn render-animation-end
  ([app-db identifier] (render-animation-end app-db identifier nil nil))
  ([app-db identifier version] (render-animation-end app-db identifier version nil))
  ([app-db identifier version args]
   (let [[id _] (identifier->id-state identifier)
         init-meta (make-initial-meta identifier args)]
     (assoc-in app-db (app-db-animation-path id version)
               {:data (values init-meta)
                :meta init-meta}))))

(defn clear-animation
  ([app-db id] (clear-animation app-db id nil))
  ([app-db id version]
   (dissoc-in app-db (app-db-animation-path id version))))

(defn animate-state!
  ([task-runner! app-db identifier] (animate-state! task-runner! app-db identifier nil nil))
  ([task-runner! app-db identifier version] (animate-state! task-runner! app-db identifier version nil))
  ([task-runner! app-db identifier version args]
   (let [[id state] (identifier->id-state identifier)
         prev (get-animation app-db id version)
         prev-values (:data prev)
         prev-meta (:meta prev)
         init-meta (make-initial-meta identifier args prev-meta)
         animator (animator init-meta prev-values)
         values (values init-meta)
         start-end (helpers/start-end-values (helpers/prepare-values prev-values) (helpers/prepare-values values))
         task-id (animation-id id version)]
     (task-runner!
      task-id
      (fn [{:keys [times-invoked]} app-db]
        (let [current (get-animation app-db id version)
              current-meta (if (zero? times-invoked) init-meta (:meta current))
              next-position (animator/position animator)
              next-meta (assoc current-meta :times-invoked times-invoked :position next-position)
              done? (done? next-meta animator)
              next-data (step next-meta (helpers/get-current-styles (if done? 1 next-position) start-end done?))
              next-app-db (assoc-in app-db (app-db-animation-path id version) {:data next-data :meta next-meta})]
          (if done?
            (stop-task next-app-db task-id)
            next-app-db)))))))

(def blocking-animate-state! (partial animate-state! blocking-raf!))
(def non-blocking-animate-state! (partial animate-state! non-blocking-raf!))

(defn stop-animation!
  ([app-db id] (stop-animation! app-db id nil))
  ([app-db id version] (stop-task! app-db (animation-id id version))))

(defn cancel-animation!
  ([app-db id] (cancel-animation! app-db id nil))
  ([app-db id version] (cancel-task! app-db (animation-id id version))))
