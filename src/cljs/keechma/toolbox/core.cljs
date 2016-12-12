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
   [keechma.toolbox.pipeline.core :as pp :refer-macros [pipeline!]]
   [promesa.core :as p]
   [promesa.impl.promise :as pimpl]
   [keechma.toolbox.forms.helpers :as forms-helpers]
   [keechma.toolbox.forms.core :as forms-core]
   [forms.validator :as v]
   [clojure.string :as str]
   [ajax.core :refer [GET abort]]))

(defn movie-search [title]
  (p/promise (fn [resolve reject on-cancel]
               (let [req (GET (str "https://www.omdbapi.com/?s=" title)
                              {:handler resolve
                               :error-handler reject})]
                 (on-cancel #(abort req))))))

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

(def user-validator (v/validator {:username [not-empty?]
                                  :type [not-empty?]}))

(deftype UserForm []
  forms-core/IForm
  (process-in [_ _ _ _]
    {:username "retro"})
  (validate [_ data]
    (user-validator data))
  (process-attr-with [_ path]
    (if (= path [:foo])
      (fn [_ _ form-state _ _]
        (assoc form-state :cached-dirty-paths
               (set (concat #{:baz :qux} (:cached-dirty-paths form-state)))))))
  (format-attr-with [_ path]
    (when (= [:percentage] path)
      format-tax-id)))

(def app-forms
  {:user (->UserForm)})

(def user-form-controller
  (pp-controller/constructor
   (fn [] true)
   {:start (pipeline! [value app-db]
             (pp/send-command! [forms-core/id-key :mount-form] [:user :form]))}))

(defn user-form-render [ctx form-props]
  (fn []
    (let [form-state @(forms-helpers/form-state ctx form-props)
          {:keys [on-change on-blur validate set-value submit]} (forms-helpers/make-component-helpers ctx form-props)]
      (when form-state
        [:div
         (when (and (forms-helpers/form-submit-attempted? form-state)
                    (forms-helpers/form-invalid? form-state))
           [:div {:style {:color "red"}} "Please fix the errors"])
         [:input {:on-change (on-change :username)
                  :on-blur (on-blur :username)
                  :value (forms-helpers/attr-get-in form-state :username)}]
         (when-let [e (forms-helpers/attr-errors form-state :username)]
           [:div {:style {:color "orange" :padding "10px" :border "1px solid orange"}}
            (str/join ", " (get-in e [:$errors$ :failed]))])
         [:br]
         [:select {:on-change (on-change :type)
                   :on-blur (on-blur :type)
                   :value (or (forms-helpers/attr-get-in form-state :type) "")}
          [:option {:value ""} "-"]
          [:option {:value "bar"} "Bar"]]
         (when-let [e (forms-helpers/attr-errors form-state :type)]
           [:div {:style {:color "orange" :padding "10px" :border "1px solid orange"}}
            (str/join ", " (get-in e [:$errors$ :failed]))])
         [:br]
         [:input {:on-change (on-change :percentage)
                  :on-blur (on-blur :percentage)
                  :value (forms-helpers/attr-get-in form-state :percentage)}]
         [:br]
         [:button {:on-click #(set-value :username "")} "SET USERNAME"]
         [:button {:on-click #(set-value :foo "TEST")} "SET FOO"]
         [:button {:on-click #(validate)} "VALIDATE"]
         [:br]
         [:button {:on-click #(submit)} "SUBMIT"]]))))

(def user-form-component
  (ui/constructor
   {:renderer user-form-render
    :subscription-deps [:form-state]
    :topic forms-core/id-key}))

(defn search-render [ctx]
  [:div {:style {:border-bottom "10px solid gray" :padding-bottom "10px" :margin-bottom "10px"}}
   [:input {:on-change #(ui/send-command ctx :search (.. % -target -value))}]])

(def search-component
  (ui/constructor
   {:renderer search-render
    :topic :search}))

(defn main-render [ctx]
  [:div
   [:h1 "Forms"]
   [:hr]
   [(ui/component ctx :search)]
   [(ui/component ctx :user-form) [:user :form]]])

(def main-component
  (ui/constructor
   {:renderer main-render
    :component-deps [:user-form :search]}))

(defn delay-pipeline [ms]
  (p/promise (fn [resolve _] (js/setTimeout resolve ms))))

(def search-controller
  (pp-controller/constructor
   (fn [] true)
   {:search (pp/exclusive
             (pipeline! [value app-db]
               (when-not (empty? value)
                 (pipeline! [value app-db]
                   (delay-pipeline 500)
                   (movie-search value)
                   (println "SEARCH RESULTS:" value)))))}))

(def app-definition
  {:components    {:main      main-component
                   :user-form user-form-component
                   :search    search-component}
   :controllers   (-> {:user-form user-form-controller
                       :search    search-controller}
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
