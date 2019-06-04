(ns keechma.toolbox.dataloader.helpers)

(defn map-loader [loader]
  (fn [reqs]
    (map loader reqs)))
