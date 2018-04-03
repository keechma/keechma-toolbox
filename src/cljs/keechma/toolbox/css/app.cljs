(ns keechma.toolbox.css.app
  (:require [keechma.app-state.core :refer [reg-on-start reg-on-stop]]
            [clojure.string :as str]
            [keechma.toolbox.css.core :as core]))

(defn generate-stylesheet-id [config]
  (let [app-name (:name config)]
    (str "injected-css-" (str/join "-" (map name app-name)) "")))

(defn update-page-css [config stylesheet]
  (core/update-page-css stylesheet (generate-stylesheet-id config))
  config)

(defn remove-page-css [config]
  (core/remove-element-by-id (generate-stylesheet-id config))
  config)

(defn install [app-config stylesheet]
  (-> app-config
      (reg-on-start #(update-page-css % stylesheet))
      (reg-on-stop #(remove-page-css %))))
