(ns keechma.toolbox.pipeline.core
  (:require [clojure.set :as set]
            [clojure.pprint :refer [pprint]]
            [clojure.walk :refer [prewalk]]))

(defn extract-pipeline-parts [args body]
  (let [has-rescue-block? (= "rescue!" (name (first (last body))))
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
    `(keechma.toolbox.pipeline.core/make-pipeline
      ~(-> {}
           (begin-forms pipeline-parts)
           (rescue-forms pipeline-parts)))))

(defmacro pipeline! [args & body]
  (make-pipeline args body))
