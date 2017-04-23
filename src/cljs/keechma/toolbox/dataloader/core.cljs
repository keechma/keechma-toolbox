(ns keechma.toolbox.dataloader.core
  (:require [com.stuartsierra.dependency :as dep]
            [cljs.core.async :refer [<! chan put!]]
            [promesa.core :as p])
  (:require-macros [cljs.core.async.macros :refer [go-loop]]))

(def id-key ::dataloader)

(defn datasources->loaders [app-datasources datasources app-db results-chan]
  (let [route-params (get-in app-db [:route :data])]
    (loop [ds datasources
           loaders {}]
      (if (not (seq ds))
        loaders
        (let [[key val] (first ds)
              prev (get-in app-db [:kv id-key key :prev])
              params-fn (or (:params val) (fn [& args]))
              deps-values (reduce
                           (fn [acc dep-key]
                             (let [dep (get app-datasources dep-key)]
                               (assoc acc dep-key (get-in app-db (:target dep))))) {} (:deps val))
              params (params-fn prev route-params deps-values)
              loader (or (:loader val) identity)
              target (:target val)]
          (if (and (= params (:params prev))
                   (= :completed (:status prev)))
            (do
              (put! results-chan [:ok {:datasource key
                                       :target target
                                       :params params
                                       :prev prev
                                       :value (:value prev)}])
              (recur (rest ds) loaders))
            (let [current-loaders (or (get loaders loader) [])]
              (recur (rest ds)
                     (assoc loaders loader
                            (conj current-loaders
                                  {:params params
                                   :prev prev
                                   :datasource key
                                   :target target}))))))))))

(defn start-loaders! [app-db-atom app-datasources datasources results-chan]
  (let [app-db @app-db-atom
        loaders (datasources->loaders app-datasources datasources app-db results-chan)]
    (doseq [[loader pending-datasources] loaders]
      (let [promises (loader pending-datasources)]
        (doseq [[idx loader-promise] (map-indexed vector promises)]
          (->> (p/promise loader-promise)
               (p/map (fn [value]
                        (let [pending-datasource (get pending-datasources idx)
                              processor (or (get-in datasources [(:datasource pending-datasource) :processor]) identity)]
                          (put! results-chan [:ok (assoc pending-datasource
                                                         :value (processor value pending-datasource))]))))
               (p/error (fn [error]
                          (let [pending-datasource (get pending-datasources idx)]                            
                            (put! results-chan [:error (assoc pending-datasource :error error)]))))))))))

(defn mark-pending! [app-db-atom datasources]
  (let [app-db @app-db-atom]
    (reset! app-db-atom
            (reduce
             (fn [acc [key val]]
               (let [prev-value (get-in acc (:target val))
                     prev-params (get-in acc [:kv id-key key :params])
                     prev-status (get-in acc [:kv id-key key :status])]
                 (assoc-in acc [:kv id-key key]
                           {:status :pending
                            :prev {:value prev-value
                                   :params prev-params
                                   :status prev-status}})))
             app-db datasources))))

(defn has-pending-datasources? [app-db]
  (let [statuses (map (fn [[_ val]] (get val :status)) (get-in app-db [:kv id-key]))]
    (boolean (some #(= :pending %) statuses))))

(defn store-datasource! [app-db-atom payload]
  (let [app-db @app-db-atom
        datasource (:datasource payload)]
    (reset! app-db-atom
            (-> app-db
                (assoc-in [:kv id-key datasource]
                          {:status :completed
                           :prev (get-in app-db [:kv id-key datasource :prev])})
                (assoc-in (:target payload) (:value payload))))))

(defn start-dependent-loaders! [app-db-atom app-datasources datasources results-chan]
  (let [app-db @app-db-atom
        statuses (reduce (fn [acc [key val]] (assoc acc key (:status val))) {} (get-in app-db [:kv id-key]))
        fulfilled  (reduce (fn [acc [key val]]
                             (if (and
                                  (= :pending (get-in app-db [:kv id-key key :status]))
                                  (every? #(= :completed %) (vals (select-keys statuses (:deps val)))))
                               (assoc acc key val)
                               acc))
                           {} datasources)]
    (start-loaders! app-db-atom app-datasources fulfilled results-chan)))

(defn store-datasource-error! [app-db-atom payload]
  (let [app-db @app-db-atom
        datasource (:datasource payload)]
    (reset! app-db-atom
            (-> app-db
                (assoc-in [:kv id-key datasource]
                          {:status :error
                           :prev nil
                           :error (:error payload)})))))

(defn mark-dependent-errors! [app-db-atom app-datasources datasources payload]
  (reset! app-db-atom
          (reduce (fn [acc [key val]]
                    (assoc-in acc [:kv id-key key]
                              {:status :error
                               :prev nil
                               :error (:error payload)})) @app-db-atom datasources)))

(defn make-dataloader [datasources]
  (let [g (reduce
           (fn [acc [key val]]
             (let [deps (:deps val)]
               (reduce #(dep/depend %1 key %2) acc deps)))
           (dep/graph) datasources)
        independent (filter #(not (seq (get-in datasources [% :deps]))) (keys datasources))]
    (fn [app-db-atom]
      (p/promise
       (fn [resolve reject on-cancel]
         (let [running? (atom true) 
               results-chan (chan)]
           (on-cancel #(swap! running? not))
           (mark-pending! app-db-atom datasources)
           (start-loaders! app-db-atom datasources (select-keys datasources independent) results-chan)
           (go-loop []
               (if @running?
                 (if (has-pending-datasources? @app-db-atom)
                   (let [[status payload] (<! results-chan)]
                     (let [t-dependents (dep/transitive-dependents g (:datasource payload))]
                       (if (= :ok status)
                         (do
                           (store-datasource! app-db-atom payload)
                           (start-dependent-loaders! app-db-atom datasources (select-keys datasources t-dependents) results-chan))
                         (do
                           (store-datasource-error! app-db-atom payload)
                           (mark-dependent-errors! app-db-atom datasources (select-keys datasources t-dependents) payload)))
                       (recur)))
                   (resolve))
                 (reject)))))))))
