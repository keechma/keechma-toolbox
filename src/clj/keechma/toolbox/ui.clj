(ns keechma.toolbox.ui
  (:require [clojure.string :as str]
            [clojure.walk :refer [postwalk prewalk]]))

(def generic-arg-prefix (gensym 'arg))

(defn is-rest-arg? [arg]
  (= "%&" (str arg)))

(defn get-arg-pos [arg]
  (let [str-arg (str (if (= '% arg) '%1 arg))]
    (if (and (str/starts-with? str-arg "%")
             (not= "%&" str-arg))
      (Integer/parseInt (subs str-arg 1))
      nil)))

(defn is-pos-arg? [arg]
  (boolean (get-arg-pos arg)))

(defn get-has-rest-args?
  ([args] (get-has-rest-args? args false))
  ([args initial]
   (reduce
    (fn [acc a]
      (if (coll? a)
        (get-has-rest-args? a false)
        (if (is-rest-arg? a)
          (reduced true)
          acc)))
    initial
    args)))

(defn get-anon-args-count
  ([args] (get-anon-args-count args 0))
  ([args init-count]
   (reduce
    (fn [cnt arg]
      (let [arg-pos (get-arg-pos arg)]
        (if (and arg-pos (> arg-pos cnt))
          arg-pos
          (if (coll? arg)
            (get-anon-args-count arg cnt)
            cnt))))
    init-count args)))

(defn get-named-args [args]
  (filterv #(not (or (is-pos-arg? %) (is-rest-arg? %))) args))

(defn pos->named [args-base-name arg]
  (str args-base-name "_" arg))

(defn get-fn-args [args-base-name args]
  (let [pos-args-count (get-anon-args-count args)
        has-rest-args? (get-has-rest-args? args)
        named-args (mapv (comp #(pos->named args-base-name %) inc) (range 0 pos-args-count))]
    (if has-rest-args?
      (vec (concat named-args ["&" (pos->named args-base-name "rest")]))
      named-args)))

(defmacro <mcmd [ctx command & args]
  (let [args-base-name (gensym 'arg)
        fn-args (mapv symbol (get-fn-args args-base-name args))
        mem-args (postwalk (fn [arg]
                         (cond
                           (is-pos-arg? arg) (symbol (pos->named args-base-name (get-arg-pos arg)))
                           (is-rest-arg? arg) (symbol (pos->named args-base-name "rest"))
                           :else arg))
                       args)
        cache-args (->
                    (postwalk (fn [arg]
                               (cond
                                 (is-pos-arg? arg) (str (pos->named generic-arg-prefix (get-arg-pos arg)))
                                 (is-rest-arg? arg) (str (pos->named generic-arg-prefix "rest"))
                                 :else arg))
                                 args)
                    flatten
                    vec)
        result-fn `(fn ~fn-args
                     (keechma.toolbox.ui/<cmd ~ctx ~command ~@mem-args))]
    `(keechma.toolbox.ui/memoize-cmd
      ~result-fn
      {:ctx ~ctx
       :command ~command
       :args ~cache-args})))
       
(defmacro <mredirect [& args] 
  `(keechma.toolbox.ui/memoize-redirect ~@args))
