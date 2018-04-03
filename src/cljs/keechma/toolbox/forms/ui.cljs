(ns keechma.toolbox.forms.ui
  (:require [keechma.ui-component :as ui]
            [keechma.toolbox.forms.core :as forms-core]
            [forms.util :as keechma-forms-util :refer [key-to-path]]
            [forms.core :as keechma-forms-core]))

(def no-immediate-validation :keechma.toolbox.forms.core/no-immediate-validation)

(defn form-state> [ctx form-props]
  (deref (ui/subscription ctx forms-core/id-key [form-props])))

(defn errors> [ctx form-props]
  (:errors (form-state> ctx form-props)))

(defn data> [ctx form-props]
  (:data (form-state> ctx form-props)))

(defn value-for> [ctx form-props path]
  (get-in (data> ctx form-props) (key-to-path key)))

(defn errors-for> [ctx form-props path]
  (let [form-state (form-state> ctx form-props)
        path (key-to-path path)
        is-dirty? (or (contains? (:cached-dirty-paths form-state) path)
                      (contains? (:dirty-paths form-state) path))]
    (when is-dirty?
      (get-in (:errors form-state) path))))

(defn valid-in?> [ctx form-props path]
  (nil? (errors-for> ctx form-props path)))

(defn valid?> [ctx form-props]
  (empty? (errors> ctx form-props)))

(defn invalid?> [ctx form-props]
  (not (valid?> ctx form-props)))

(defn invalid-in?> [ctx form-props path]
  (not (valid-in?> ctx form-props path)))

(defn submit-attempted?> [ctx form-props]
  (:submit-attempted? (form-state> ctx form-props)))

(defn <on-change [ctx form-props path e]
  (let [el (.-target e)
        value (.-value el)
        caret-pos (if (= "text" (.-type el)) (.-selectionStart el) nil)]
    (ui/send-command ctx [forms-core/id-key :on-change] [form-props (key-to-path path) el value caret-pos])))

(defn <on-blur [ctx form-props path e]
  (ui/send-command ctx [forms-core/id-key :on-blur] [form-props (key-to-path path)]))

(defn <validate
  ([ctx form-props] (<validate ctx form-props false))
  ([ctx form-props dirty-only?]
   (ui/send-command ctx [forms-core/id-key :on-validate] [form-props dirty-only?])))

(defn <set-value [ctx form-props path value]
  (ui/send-command ctx [forms-core/id-key :on-change] [form-props (key-to-path path) nil value nil]))

(defn <set-value-without-immediate-validation [ctx form-props path value]
  (ui/send-command ctx [forms-core/id-key :on-change] [form-props (key-to-path path) no-immediate-validation value nil]))

(defn <submit
  ([ctx form-props] (<submit ctx form-props nil))
  ([ctx form-props e]
   (when e (.preventDefault e))
   (ui/subscription ctx [forms-core/id-key :on-submit] form-props)))

(defn <call [ctx form-props & args]
  (ui/send-command ctx [forms-core/id-key :call] [form-props args]))
