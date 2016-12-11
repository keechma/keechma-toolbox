(ns keechma.toolbox.pipeline.core
  (:require [clojure.set :as set]
            [clojure.pprint :refer [pprint]]
            [clojure.walk :refer [prewalk]]))

(def error-messages
  {:maximum       "pipeline-> macro expect maximum of two blocks: begin and rescue"
   :non-allowed   "pipeline-> macro allows only begin and rescue blocks as top forms"
   :begin-missing "pipeline-> macro must have a begin block"
   :begin-args    "pipeline-> begin block must accept three arguments: pipeline-context, value and app-db"
   :rescue-args   "pipeline-> rescue block must accept four arguments: pipeline-context, error, value and app-db"})

(defn add-begin-forms [acc blocks]
  (let [begin-args (first blocks)
        begin-forms (rest blocks)]
    (if (not (= 3 (count begin-args)))
      (throw (ex-info (:begin-args error-messages) {}))
      (assoc acc :begin (into [] (map (fn [f] `(fn ~begin-args ~f)) begin-forms))))))

(defn add-rescue-forms [acc blocks]
  (if (nil? blocks)
    acc
    (let [rescue-args (first blocks)
          rescue-forms (rest blocks)]
      (if (not (= 4 (count rescue-args)))
        (throw (ex-info (:rescue-args error-messages) {}))
        (assoc acc :rescue (into [] (map (fn [f] `(fn ~rescue-args ~f)) rescue-forms)))))))

(defmacro pipeline-> [& steps]
  (let [blocks (reduce (fn [acc s]
                         (assoc acc (keyword (name (first s))) (rest s))) {} steps)
        block-keys (set (keys blocks))]
    (cond
      (< 2 (count block-keys)) (throw (ex-info (:maximum error-messages) {}))
      (not (empty? (set/difference block-keys #{:begin :rescue}))) (throw (ex-info (:non-allowed error-messages) {}))
      (not (contains? block-keys :begin)) (throw (ex-info (:begin-missing error-messages) {}))
      :else `(keechma.toolbox.pipeline.core/make-pipeline
              ~(-> {}
                   (add-begin-forms (:begin blocks))
                   (add-rescue-forms (:rescue blocks)))))))

(defn extract-pipeline-parts [args body]
  (let [has-rescue-block? (= `rescue! (first (last body)))
        [begin-body rescue] (if has-rescue-block? [(drop-last body) (last body)] [body nil])
        [_ rescue-args & rescue-body] rescue]
    {:begin-args args
     :begin-body begin-body
     :rescue-args rescue-args
     :rescue-body rescue-body}))



(defn expand-body [args body]
  (into [] (map (fn [f] `(fn ~args ~f)) body)))

(defn begin-forms [acc {:keys [begin-args begin-body]}]
  (if (not= 2 (count begin-args))
    (throw (ex-info "Pipeline takes exactly two arguments: value and app-db" {}))
    (assoc acc :begin (expand-body begin-args begin-body))))

(defn rescue-forms [acc {:keys [begin-args rescue-args rescue-body]}]
  (if (or (nil? rescue-args) (nil? rescue-body))
    acc
    (if (not= 1 (count rescue-args))
      (throw (ex-info "Pipeline catch block takes exactly one argument: error" {}))
      (assoc acc :rescue (expand-body (into [] (concat begin-args rescue-args)) rescue-body)))))

(defn make-pipeline [args body]
  (let [pipeline-parts (extract-pipeline-parts args body)]
    `(keechma.toolbox.pipeline.core/make-pipeline2
      ~(-> {}
           (begin-forms pipeline-parts)
           (rescue-forms pipeline-parts)))))

(defmacro pipeline! [args & body]
  (make-pipeline args body))
