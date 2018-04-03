(ns keechma.toolbox.forms.app
  (:require [keechma.toolbox.forms.controller :as forms-controller]
            [keechma.toolbox.forms.mount-controller :as forms-mount-controller]
            [keechma.toolbox.forms.core :as forms-core])
  (:require-macros [reagent.ratom :refer [reaction]]))

(defn form-state-sub [app-db-atom form-props]
  (reaction
   (get-in @app-db-atom [:kv forms-core/id-key :states form-props])))

(defn add-form-state-subscription-dep-to-components [app-config]
  (assoc app-config :components
         (reduce-kv (fn [acc k c]
                      (let [s-deps (or (:subscription-deps c) [])]
                        (assoc-in acc [k :subscription-deps] (conj s-deps forms-core/id-key))))
                    (:components app-config) (:components app-config))))

(defn register-forms-controller [app-config forms]
  (update app-config :controllers #(forms-controller/register % forms)))

(defn register-forms-mount-controller [app-config forms-mount-fns]
  (if forms-mount-fns
    (update app-config :controllers #(forms-mount-controller/register % forms-mount-fns))
    app-config))

(defn install
  ([app-config forms] (install app-config forms nil))
  ([app-config forms forms-mount-fns]
   (-> app-config
       (assoc-in [:subscriptions forms-core/id-key] form-state-sub)
       (add-form-state-subscription-dep-to-components)
       (register-forms-controller forms)
       (register-forms-mount-controller forms-mount-fns))))
