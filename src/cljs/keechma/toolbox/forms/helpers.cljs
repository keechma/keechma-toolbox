(ns keechma.toolbox.forms.helpers
  (:require [keechma.ui-component :as ui]
            [keechma.toolbox.forms.core :as forms-core]
            [reagent.ratom]
            [forms.util :as keechma-forms-util]
            [forms.core :as keechma-forms-core])
  (:require-macros
   [reagent.ratom :refer [reaction]]))

(defn form-state [ctx form-props]
  (ui/subscription ctx :form-state [form-props]))

(defn form-state-sub [app-db form-props]
  (reaction
   (get-in @app-db [:kv forms-core/id-key :states form-props])))

(defn attr-assoc-in [form-state path value]
  (assoc form-state :data
         (assoc-in (:data form-state) (keechma-forms-util/key-to-path path) value)))

(defn attr-get-in [form-state path]
  (get-in (:data form-state) (keechma-forms-util/key-to-path path)))

(defn attr-valid? [form-state path]
  (nil? (get-in (:errors form-state) (keechma-forms-util/key-to-path path))))

(defn mark-dirty-paths
  ([form-state dirty-paths] (mark-dirty-paths form-state dirty-paths false))
  ([form-state dirty-paths cache?]
   (let [cached-dirty-paths (:cached-dirty-key-paths form-state)
         new-dirty-paths (set (concat dirty-paths cached-dirty-paths))
         new-cached-dirty-paths (if cache? new-dirty-paths cached-dirty-paths)]
     (assoc :dirty-paths new-dirty-paths
            :cached-dirty-paths new-cached-dirty-paths))))

(defn errors->paths [errors]
  (set (keechma-forms-core/errors-keypaths errors)))


(defn make-component-helpers [ctx form-props]
  {:on-change (fn [path]
                (let [path (keechma-forms-util/key-to-path path)]
                  (fn [e]
                    (let [el (.-target e)
                          value (.-value el)]
                      (ui/send-command ctx :on-change [form-props path el value])))))
   :on-blur (fn [path]
                (let [path (keechma-forms-util/key-to-path path)]
                  (fn [e]
                    (ui/send-command ctx :on-blur [form-props path e]))))})
