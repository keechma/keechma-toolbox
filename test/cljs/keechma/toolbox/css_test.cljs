(ns keechma.toolbox.css-test
  (:require [cljs.test :refer-macros [deftest testing is async]]
            [keechma.toolbox.css.core :refer-macros [defelement]]
            [clojure.string :as str]
            [clojure.set :as set]
            [keechma.app-state :as app-state]
            [keechma.toolbox.css.app :as css]
            [keechma.toolbox.test-util :refer [make-container]]))



(defelement -my-element
  :class [:foo :bar])

(def -my-element-ns-class "keechma_toolbox_css_test--my-element")

(def -my-element-classes #{"foo" "bar" -my-element-ns-class})

(deftest correct-classes-are-generated []
  (let [parts (str/split (str -my-element) ".")
        el (first parts)
        classes (set (rest parts))
        gensym-class (first (set/difference classes -my-element-classes))]
    (is (= "div" el))
    (is (str/starts-with? gensym-class -my-element-ns-class))
    (is (= 1 (count (set/difference classes -my-element-classes))))))

(deftest css-is-installed
  (let [[c unmount] (make-container)
        app (-> {:components {:main {:renderer (fn [_])}}
                 :html-element c}
                (css/install []))]
    (async done
           (let [started-app (app-state/start! app)]
             (is (not (nil? (.getElementById js/document (css/generate-stylesheet-id started-app)))))
             (app-state/stop!
              started-app
              (fn [stopped-app]
                (is (nil? (.getElementById js/document (css/generate-stylesheet-id stopped-app))))
                (unmount)
                (done)))))))
