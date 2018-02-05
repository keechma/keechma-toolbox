(ns keechma.toolbox.dataloader.core
  (:require [com.stuartsierra.dependency :as dep]
            [cljs.core.async :refer [<! chan put!]]
            [promesa.core :as p]
            [entitydb.core :as edb]
            [medley.core :refer [dissoc-in]]
            [clojure.set :as set])
  (:require-macros [cljs.core.async.macros :refer [go-loop]]))

(def id-key ::dataloader)

(def request-cache (atom {}))

(defrecord EntityDBWithRelations [data relations])

(defn target->edb [target]
  [(keyword (namespace target))
   (keyword (name target))])

(defn save-kv-data [app-db target data]
  (assoc-in app-db target data))

(defn insert-relations [edb-schema edb relations]
  (reduce-kv (fn [acc k v]
               (let [items (if (map? v) [v] v)]
                 (reduce (fn [acc2 item]
                           (edb/insert-item edb-schema acc2 k item))
                         acc items))) edb relations))

(defn save-edb-named-item [app-db edb-schema target data]
  (let [edb (:entity-db app-db)
        [entity named-item] (target->edb target)
        insert-named-item (partial edb/insert-named-item edb-schema)
        [data relations] (if (= EntityDBWithRelations (type data))
                           [(:data data) (:relations data)]
                           [data nil])]
    (assoc app-db :entity-db
           (if data
             (-> (insert-relations edb-schema edb relations)
                 (insert-named-item entity named-item data))
             (edb/remove-named-item edb entity named-item)))))

(defn save-edb-collection [app-db edb-schema target data]
  (let [edb (:entity-db app-db)
        [entity collection] (target->edb target)
        insert-collection (partial edb/insert-collection edb-schema)
        [data relations] (if (= EntityDBWithRelations (type data))
                           [(:data data) (:relations data)]
                           [data nil])]
    (assoc app-db :entity-db
           (if (seq data)
             (-> (insert-relations edb-schema edb relations)
                 (insert-collection entity collection data))
             (edb/remove-collection edb entity collection)))))

(defn get-edb-named-item [app-db edb-schema target]
  (let [edb (:entity-db app-db)
        [entity named-item] (target->edb target)]
    (edb/get-named-item edb-schema edb entity named-item)))

(defn get-edb-collection [app-db edb-schema target]
  (let [edb (:entity-db app-db)
        [entity collection] (target->edb target)]
    (edb/get-collection edb-schema edb entity collection)))

(defn get-kv-data [app-db target]
  (get-in app-db target))

(defn get-meta [app-db datasource-key]
  (get-in app-db [:kv id-key datasource-key]))

(defn save-meta [app-db datasource-key meta]
  (assoc-in app-db [:kv id-key datasource-key] meta))

(defn save-data [app-db edb-schema target data]
  (let [target-start (first target)]
    (case target-start
      :edb/named-item (save-edb-named-item app-db edb-schema (last target) data)
      :edb/collection (save-edb-collection app-db edb-schema (last target) data)
      (save-kv-data app-db target data))))

(defn get-data [app-db edb-schema target]
  (let [target-start (first target)]
    (case target-start
      :edb/named-item (get-edb-named-item app-db edb-schema (last target))
      :edb/collection (get-edb-collection app-db edb-schema (last target))
      (get-kv-data app-db target))))

(defn remove-pending-datasource [app-db datasource-key]
  (update-in app-db [:kv ::pending] #(disj % datasource-key)))

(defn mark-pending! [app-db-atom edb-schema datasources] 
  (swap! app-db-atom assoc-in [:kv ::pending] (set (keys datasources))))

(defn get-meta-and-data [app-db edb-schema datasource-key target]
  {:data (get-data app-db edb-schema target)
   :meta (get-meta app-db datasource-key)})

(defn datasource-params [datasources datasource-key datasource app-db edb-schema]
  (let [params-fn (or (:params datasource) (fn [& args]))
        prev (get-meta-and-data app-db edb-schema datasource-key (:target datasource))
        route (get-in app-db [:route :data])
        deps (reduce (fn [acc dep-key]
                       (assoc acc dep-key (get-data app-db edb-schema (:target (get datasources dep-key)))))
                     {} (:deps datasource))]
    (params-fn prev route deps)))

(defn datasources->loaders [app-datasources datasources app-db results-chan edb-schema]
  (let [route-params (get-in app-db [:route :data])]
    (loop [ds datasources
           loaders {}]
      (if (not (seq ds))
        loaders
        (let [[key val] (first ds)
              prev (get-meta-and-data app-db edb-schema key (:target val))
              params (datasource-params app-datasources key val app-db edb-schema)
              loader (or (:loader val) identity)
              target (:target val)]
          
          (let [current-loaders (or (get loaders loader) [])]
            (recur (rest ds)
                   (assoc loaders loader
                          (conj current-loaders
                                {:params params
                                 :prev prev
                                 :datasource key
                                 :app-db app-db
                                 :target target
                                 :current-request (get-in @request-cache [loader params])})))))))))

(defn call-loader [loader pending-datasources context]
  (let [reqs (loader pending-datasources context)]
    (doseq [[idx req] (map-indexed vector reqs)]
      (swap! request-cache assoc-in [loader (get-in pending-datasources [idx :params])] req))
    reqs))

(defn start-loaders! [app-db-atom app-datasources datasources results-chan edb-schema context]
  (let [loaders (datasources->loaders app-datasources datasources @app-db-atom results-chan edb-schema)]
    (doseq [[loader pending-datasources] loaders]
      (let [pending-datasources-with-current (vec (filter #(not (nil? (:current-request %))) pending-datasources))
            pending-datasources-without-current (vec (filter #(nil? (:current-request %)) pending-datasources))
            promises (call-loader loader pending-datasources-without-current context)]
        (doseq [[idx loader-promise] (map-indexed vector (concat promises (map :current-request pending-datasources-with-current)))]
          (let [pending-datasource (get pending-datasources idx)]

            (swap! app-db-atom assoc-in [:kv id-key (:datasource pending-datasource) :status] :pending)

            (->> (p/promise loader-promise)
                 (p/map (fn [value]
                          (let [processor (or (get-in datasources [(:datasource pending-datasource) :processor]) identity)]
                            (swap! request-cache dissoc-in [loader (:params pending-datasource)])
                            (put! results-chan [:ok (assoc pending-datasource
                                                           :value (processor value pending-datasource))]))))
                 (p/error (fn [error]
                            (let [pending-datasource (get pending-datasources idx)]                            
                              (swap! request-cache dissoc-in [loader (:params pending-datasource)])
                              (put! results-chan [:error (assoc pending-datasource :error error)])))))))))))



(defn has-pending-datasources? [app-db]
  (not (empty? (get-in app-db [:kv ::pending]))))

(defn store-datasource! [app-db-atom edb-schema payload]
  (let [app-db @app-db-atom
        datasource-key (:datasource payload)
        value (:value payload)
        value-keys (if (map? value) (set (keys value)) #{})
        [res-data res-meta] (if (= #{:data :meta} value-keys) [(:data value) (:meta value)] [value {}])]
   

    (reset! app-db-atom
            (-> app-db
                (save-meta datasource-key
                           {:status :completed
                            :params (:params payload)
                            :error nil
                            :meta res-meta
                            :prev (merge {:status nil :error nil :params nil} (dissoc-in (:prev payload) [:meta :prev]))})
                (save-data edb-schema (:target payload) res-data)
                (remove-pending-datasource datasource-key)))))

(defn start-dependent-loaders! [app-db-atom app-datasources datasources results-chan edb-schema context]
  (let [app-db @app-db-atom
        statuses (reduce (fn [acc datasource-key]
                           (assoc acc datasource-key (:status (get-meta app-db datasource-key))))
                         {} (keys app-datasources))
        fulfilled  (reduce (fn [acc [datasource-key val]]
                             (if (and
                                  (contains? (get-in app-db [:kv ::pending]) datasource-key)
                                  (empty? (set/intersection (get-in app-db [:kv ::pending]) (set (:deps val)))))
                               (assoc acc datasource-key val)
                               acc))
                           {} datasources)]
    (start-loaders! app-db-atom app-datasources fulfilled results-chan edb-schema context)))

(defn store-datasource-error! [app-db edb-schema payload]
  (let [datasource-key (:datasource payload)]
    (-> app-db
        (save-meta datasource-key
                   {:status :error
                    :prev nil
                    :meta {}
                    :params (:params payload)
                    :error (:error payload)})
        (save-data edb-schema (:target payload) nil)
        (remove-pending-datasource datasource-key))))

(defn mark-dependent-errors! [app-db app-datasources datasources edb-schema payload]
  (reduce (fn [acc [datasource-key val]]
            (-> app-db
                (save-meta datasource-key
                           {:status :error
                            :prev nil
                            :params nil
                            :error (:error payload)})
                (save-data edb-schema (:target val) nil)
                (remove-pending-datasource datasource-key)))
          app-db datasources))

(defn deps-fulfilled? [app-db datasources-plan datasource]
  (reduce (fn [fulfilled? dep-key]
            (let [dep (get datasources-plan dep-key)]
              (and fulfilled?
                   (:deps-fulfilled? dep)
                   (not (:reload? dep)))))
          true (:deps datasource)))

(defn datasources-load-plan [app-db datasources datasources-order edb-schema invalid-datasources]
  (loop [datasources-plan {}
         datasources-order datasources-order]
    (if (seq datasources-order)
      (let [datasource-key (first datasources-order)
            datasource (get datasources datasource-key)
            datasource-meta (get-meta app-db datasource-key)
            datasource-deps-fulfilled? (deps-fulfilled? app-db datasources-plan datasource)
            new-datasource-params (datasource-params datasources datasource-key datasource app-db edb-schema)
            ;; reload? should be refactored as it's not completely clear what's going on
            reload? (if (or (not datasource-deps-fulfilled?)
                            (contains? invalid-datasources datasource-key))
                      true
                      (not (and (or (= (:params datasource-meta)
                                       new-datasource-params)
                                    (= ::ignore new-datasource-params))
                                (= :completed (:status datasource-meta)))))]

        (recur (assoc datasources-plan datasource-key
                      {:deps-fulfilled? datasource-deps-fulfilled?
                       :reload? reload?})
               (rest datasources-order)))
      datasources-plan)))

(defn make-dataloader
  ([datasources] (make-dataloader datasources {}))
  ([datasources edb-schema]
   (let [g (reduce
            (fn [acc [key val]]
              (let [deps (:deps val)]
                (reduce #(dep/depend %1 key %2) acc deps)))
            (dep/graph) datasources)
         g-nodes (dep/nodes g)
         independent (filter #(not (contains? g-nodes %)) (keys datasources))
         datasources-order (concat independent (dep/topo-sort g))
         active-dataloader-id-atom (atom nil)]
     (fn [app-db-atom {:keys [context invalid-datasources]}]
       (let [dataloader-id (gensym :dataloader)]
         (reset! active-dataloader-id-atom dataloader-id)
         (p/promise
          (fn [resolve reject on-cancel]
            (let [running? (atom true) 
                  results-chan (chan)
                  plan (datasources-load-plan @app-db-atom datasources datasources-order edb-schema invalid-datasources)
                  start-nodes (filter #(and (:reload? (get plan %)) (:deps-fulfilled? (get plan %))) (keys plan))]

              (when (fn? on-cancel) (on-cancel #(swap! running? not)))

              (mark-pending! app-db-atom edb-schema (select-keys datasources (filter #(:reload? (get plan %)) (keys plan))))
              (start-loaders! app-db-atom datasources (select-keys datasources start-nodes) results-chan edb-schema context)

              (go-loop []
                (if (and @running? (= dataloader-id @active-dataloader-id-atom))
                  (if (has-pending-datasources? @app-db-atom)
                    (let [[status payload] (<! results-chan)
                          t-dependents (dep/transitive-dependents g (:datasource payload))]
                      (case status
                        :ok (do
                              (store-datasource! app-db-atom edb-schema payload)
                              (start-dependent-loaders! app-db-atom datasources (select-keys datasources t-dependents) results-chan edb-schema context))
                        :error (reset! app-db-atom
                                       (-> @app-db-atom
                                           (store-datasource-error! edb-schema payload)
                                           (mark-dependent-errors!
                                            datasources (select-keys datasources t-dependents) edb-schema payload)))
                        nil)
                      (recur))
                    (resolve @app-db-atom))
                  (resolve @app-db-atom)))))))))))
