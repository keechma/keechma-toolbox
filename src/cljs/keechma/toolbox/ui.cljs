(ns keechma.toolbox.ui
  (:require [keechma.ui-component :as ui]))

(defn sub> [ctx subscription & args]
  (deref (ui/subscription ctx subscription args)))

(defn <cmd [ctx command & args]
  (ui/send-command ctx command args))

