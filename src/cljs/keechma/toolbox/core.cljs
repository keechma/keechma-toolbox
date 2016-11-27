(ns keechma.toolbox.core
  (:require-macros
   [reagent.ratom :refer [reaction]])
  (:require
   [reagent.core :as reagent]
   [keechma.ui-component :as ui]
   [keechma.controller :as controller]
   [keechma.app-state :as app-state]
   [devtools.core :as devtools]
   [clojure.string :as str]
   [keechma.toolbox.forms.controller :as forms-controller]
   [keechma.toolbox.pipeline.controller :as pp-controller]
   [keechma.toolbox.pipeline.core :as pp :refer-macros [pipeline->]]
   [promesa.core :as p]
   [promesa.impl.promise :as pimpl]
   [keechma.toolbox.forms.helpers :as forms-helpers]
   [keechma.toolbox.forms.core :as forms-core]
   [forms.validator :as v]
   [clojure.string :as str]))

(def format-tax-id
  ^{:format-chars #{"-"}}
  (fn [value _]
    (let [clean-value (subs (str/replace (or value "") #"[^0-9]" "") 0 9)]
      (if (>= 2 (count clean-value))
        clean-value
        (str (subs clean-value 0 2) "-" (subs clean-value 2))))))

(defn get-percentage [value]
  (if (string? value) (js/parseFloat (str "0" (str/replace value "%" ""))) value))

(def format-percentage
  ^{:format-chars #{"%"}}
  (fn [value _]
    (if (or (nil? value) (empty? value))
      "0%"
      (str (.toFixed (get-percentage value) 2) "%"))))

(def not-empty? [:not-empty (fn [v path full-data]
                              (if (nil? v)
                                false
                                (not (empty? v))))])

(def user-validator (v/validator {:username [not-empty?]}))

(defrecord UserForm []
  forms-core/IForm
  (validate [_ data]
    (user-validator data))
  (format-attr-with [_ _ _ _ path _]
    (when (= [:percentage] path)
      format-tax-id)))

(def app-forms
  {:user (->UserForm)})

(def user-form-controller
  (pp-controller/constructor
   (fn [] true)
   {:start (pipeline->
            (begin [_ value app-db]
                   (pp/send-command! [forms-core/id-key :mount-form] [:user :form])))}))

(defn user-form-render [ctx form-props]
  (fn []
    (let [form-state @(forms-helpers/form-state ctx form-props)
          {:keys [on-change on-blur validate set-value submit]} (forms-helpers/make-component-helpers ctx form-props)]
      (println form-state)
      (when form-state
        [:div
         [:input {:on-change (on-change :username)
                  :on-blur (on-blur :username)
                  :value (forms-helpers/attr-get-in form-state :username)}]
         [:br]
         [:input {:on-change (on-change :percentage)
                  :on-blur (on-blur :percentage)
                  :value (forms-helpers/attr-get-in form-state :percentage)}]
         [:br]
         [:button {:on-click #(set-value :username "TEST")} "SET USERNAME"]
         [:button {:on-click #(validate true)} "VALIDATE"]
         [:br]
         [:button {:on-click #(submit)} "SUBMIT"]]))))

(def user-form-component
  (ui/constructor
   {:renderer user-form-render
    :subscription-deps [:form-state]
    :topic forms-core/id-key}))

(defn main-render [ctx]
  [:div
   [:h1 "Forms"]
   [:hr]
   [(ui/component ctx :user-form) [:user :form]]])

(def main-component
  (ui/constructor
   {:renderer main-render
    :component-deps [:user-form]}))

(def app-definition
  {:components    {:main main-component
                   :user-form user-form-component}
   :controllers   (-> {:user-form user-form-controller}
                      (forms-controller/register app-forms))
   :subscriptions {:form-state forms-helpers/form-state-sub}
   :html-element  (.getElementById js/document "app")})

(defonce debug?
  ^boolean js/goog.DEBUG)

(defonce running-app (clojure.core/atom))

(defn start-app! []
  (reset! running-app (app-state/start! app-definition)))

(defn dev-setup []
  (when debug?
    (enable-console-print!)
    (println "dev mode")
    (devtools/install!)
    ))

(defn reload []
  (let [current @running-app]
    (if current
      (app-state/stop! current start-app!)
      (start-app!))))

(defn ^:export main []
  (dev-setup)
  (start-app!))
