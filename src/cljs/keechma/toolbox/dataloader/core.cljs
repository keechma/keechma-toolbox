(ns keechma.toolbox.dataloader.core
  (:require [com.stuartsierra.dependency :as dep]
            [cljs.core.async :refer [<! chan put!]]
            [promesa.core :as p]
            [entitydb.core :as edb]
            [medley.core :refer [dissoc-in]]
            [clojure.set :as set])
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:import goog.date.DateTime))

(def id-key ::dataloader)

(def request-cache (atom {}))

(defrecord EntityDBWithRelations [data relations])

(defn target->edb [target]
  [(keyword (namespace target))
   (keyword (name target))])

(defn save-kv-data [app-db {:keys [target] :as datasource} data]
  (assoc-in app-db target data))

(defn insert-relations [edb-schema edb relations]
  (reduce-kv (fn [acc k v]
               (let [items (if (map? v) [v] v)]
                 (reduce (fn [acc2 item]
                           (edb/insert-item edb-schema acc2 k item))
                         acc items))) edb relations))

(defn save-edb-named-item [app-db edb-schema {:keys [target] :as datasource} data]
  (let [edb (:entity-db app-db)
        [_ edb-target] target
        [entity named-item] (target->edb edb-target)
        insert-named-item (partial edb/insert-named-item edb-schema)
        [data relations] (if (= EntityDBWithRelations (type data))
                           [(:data data) (:relations data)]
                           [data nil])] 
    (assoc app-db :entity-db
           (if data
             (-> (insert-relations edb-schema edb relations)
                 (insert-named-item entity named-item data))
             (edb/remove-named-item edb entity named-item)))))

(defn save-edb-collection [app-db edb-schema {:keys [target] :as datasource} data]
  (let [edb (:entity-db app-db)
        [_ edb-target] target
        [entity collection] (target->edb edb-target)
        insert-collection (partial edb/insert-collection edb-schema)
        [data relations] (if (= EntityDBWithRelations (type data))
                           [(:data data) (:relations data)]
                           [data nil])]
    (assoc app-db :entity-db
           (if (seq data)
             (-> (insert-relations edb-schema edb relations)
                 (insert-collection entity collection data))
             (edb/remove-collection edb entity collection)))))

(defn get-edb-named-item [app-db edb-schema {:keys [target]}]
  (let [edb (:entity-db app-db)
        [_ edb-target] target
        [entity named-item] (target->edb edb-target)]
    (edb/get-named-item edb-schema edb entity named-item)))

(defn get-edb-collection [app-db edb-schema {:keys [target]}]
  (let [edb (:entity-db app-db)
        [_ edb-target] target
        [entity collection] (target->edb edb-target)]
    (edb/get-collection edb-schema edb entity collection)))

(defn get-kv-data [app-db {:keys [target] :as datasource}]
  (get-in app-db target))

(defn get-meta [app-db datasource-key]
  (get-in app-db [:kv id-key datasource-key]))

(defn save-meta [app-db datasource-key meta]
  (assoc-in app-db [:kv id-key datasource-key] meta))

(defn save-data [app-db edb-schema {:keys [target] :as datasource} data]
  (let [[target-start] target
        setter (:set datasource)]
    (cond
      (boolean setter)
      (setter app-db datasource data)

      (= :edb/named-item target-start)
      (save-edb-named-item app-db edb-schema datasource data)

      (= :edb/collection target-start)
      (save-edb-collection app-db edb-schema datasource data)

      (boolean target)
      (save-kv-data app-db datasource data)

      :else app-db)))

(defn get-data [app-db edb-schema {:keys [target] :as datasource}]
  (let [[target-start] target
        getter (:get datasource)] 
    (cond
      (boolean getter)
      (getter app-db datasource)
      
      (= :edb/named-item target-start)
      (get-edb-named-item app-db edb-schema datasource)
      
      (= :edb/collection target-start)
      (get-edb-collection app-db edb-schema datasource)
      
      (boolean target)
      (get-kv-data app-db datasource)
      
      :else nil)))

(defn remove-pending-datasource [app-db datasource-key]
  (update-in app-db [:kv ::pending] #(disj % datasource-key)))

(defn mark-pending! [app-db-atom edb-schema datasources] 
  (swap! app-db-atom assoc-in [:kv ::pending] (set (keys datasources))))

(defn get-meta-and-data [app-db edb-schema datasource-key datasource]
  {:data (get-data app-db edb-schema datasource)
   :meta (get-meta app-db datasource-key)})

(defn datasource-params [datasources datasource-key datasource app-db edb-schema]
  (let [params-fn (or (:params datasource) (fn [& args]))
        prev (get-meta-and-data app-db edb-schema datasource-key datasource)
        route (get-in app-db [:route :data])
        deps (reduce (fn [acc dep-key]
                       (assoc acc dep-key (get-data app-db edb-schema (get datasources dep-key))))
                     {} (:deps datasource))]
    (params-fn prev route deps)))

(defn cache-key [datasource params]
  [datasource (hash params)])

(defn fulfilled-loader [reqs]
  (map (fn [_] ::fulfilled) reqs))

(defn default-loader [reqs]
  (repeat (count reqs) nil))

(defn datasources->loaders [app-datasources datasources invalid-datasources app-db results-chan edb-schema]
  (let [route-params (get-in app-db [:route :data])]
    (loop [ds datasources
           loaders {}]
      (if (not (seq ds))
        loaders
        (let [[key val] (first ds)
              prev (get-meta-and-data app-db edb-schema key val)
              params (datasource-params app-datasources key val app-db edb-schema)
              datasource-loader (or (:loader val) default-loader)
              cache-valid-fn? (or (:cache-valid? val) (constantly false))
              cached (get-in app-db [:kv ::req-cache key (hash params)])
              cache-valid? (and cached (cache-valid-fn? (assoc cached :params params :app-db app-db)))
              target (:target val)
              prev-meta (:meta prev)
              prev-params (:params prev-meta)
              loader (cond
                       cache-valid?
                       (constantly [(:value cached)]) ;; Value must be wrapped in vector here because loaders work on vector of requests

                       (or (contains? invalid-datasources key)
                           (nil? prev-meta)
                           (not= prev-params params))
                       datasource-loader

                       :else
                       fulfilled-loader)

              current-loaders (or (get loaders loader) [])]
         
          (recur (rest ds)
                 (assoc loaders loader
                        (conj current-loaders
                              (merge
                               val
                               {:params params
                                :prev prev
                                :datasource key
                                :app-db app-db
                                :target target
                                :cache-valid? cache-valid?
                                :current-request (get-in @request-cache (cache-key key params))})))))))))

(defn call-loader [loader pending-datasources context app-db]
  (let [reqs (vec (loader pending-datasources context app-db))]
    (doseq [[idx req] (map-indexed vector reqs)]
      (let [pending-datasource (get pending-datasources idx)
            c-key (cache-key (:datasource pending-datasource) (:params pending-datasource))]
        (swap! request-cache assoc-in c-key req)))
    reqs))

(defn clear-cache-for-datasource! [app-db-atom datasource]
   (when-not (:cache-valid? datasource)
     (let [datasource-key (:datasource datasource)
           params (:params datasource)]
       (swap! app-db-atom dissoc-in [:kv ::req-cache datasource-key (hash params)]))))

(defn cache-datasource-response! [app-db-atom datasource value]
  (when-not (:cache-valid? datasource)
    (let [cache-value {:cached-at (.getTime (goog.date.DateTime.))
                       :value value}
          datasource-key (:datasource datasource)
          params (:params datasource)]
      (swap! app-db-atom assoc-in [:kv ::req-cache datasource-key (hash params)] cache-value))))

(defn start-loaders! [app-db-atom app-datasources datasources invalid-datasources results-chan edb-schema context]
  (let [loaders (datasources->loaders app-datasources datasources invalid-datasources @app-db-atom results-chan edb-schema)]

    (doseq [[loader unsorted-pending-datasources] loaders]
      (let [pending-datasources (vec (sort-by #(nil? (:current-request %)) unsorted-pending-datasources))
            pending-datasources-with-current (vec (filter #(not (nil? (:current-request %))) pending-datasources))
            pending-datasources-without-current (vec (filter #(nil? (:current-request %)) pending-datasources))
            promises (call-loader loader pending-datasources-without-current context @app-db-atom)]
       
        (doseq [[idx loader-promise] (map-indexed vector (concat (map :current-request pending-datasources-with-current) promises))]
          (let [pending-datasource (get pending-datasources idx)]

            (clear-cache-for-datasource! app-db-atom pending-datasource)

            (when (and (not= fulfilled-loader loader)
                       (not (:cache-valid? pending-datasource)))
              (swap! app-db-atom assoc-in [:kv id-key (:datasource pending-datasource) :status] :pending))


            (->> (p/promise loader-promise)
                 (p/map (fn [value]
                          (let [processor (or (get-in datasources [(:datasource pending-datasource) :processor]) 
                                              (fn [data & args] data))
                                c-key (cache-key (:datasource pending-datasource) (:params pending-datasource))]


                            (swap! request-cache dissoc-in c-key)
                            (if (= ::fulfilled value)
                              (put! results-chan [:ok (assoc pending-datasource :value value)])
                              (do
                                (cache-datasource-response! app-db-atom pending-datasource value)
                                (put! results-chan [:ok (assoc pending-datasource
                                                               :value (processor value (assoc pending-datasource :app-db @app-db-atom)))]))))))
                 (p/error (fn [error]
                            (let [pending-datasource (get pending-datasources idx)
                                  c-key (cache-key (:datasource pending-datasource) (:params pending-datasource))]                            
                              (swap! request-cache dissoc-in c-key)
                              (put! results-chan [:error (assoc pending-datasource :error error)]))))))))))) ()


(defn has-pending-datasources? [app-db]
  (not (empty? (get-in app-db [:kv ::pending]))))

(defn store-datasource! [app-db-atom edb-schema payload]
  (if (= ::fulfilled (:value payload))
    (reset! app-db-atom (remove-pending-datasource @app-db-atom (:datasource payload)))
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
                  (save-data edb-schema payload res-data)
                  (remove-pending-datasource datasource-key))))))

(defn start-dependent-loaders! [app-db-atom app-datasources datasources invalid-datasources results-chan edb-schema context]
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
    (start-loaders! app-db-atom app-datasources fulfilled invalid-datasources results-chan edb-schema context)))

(defn store-datasource-error! [app-db edb-schema payload]
  (let [datasource-key (:datasource payload)]
    (-> app-db
        (save-meta datasource-key
                   {:status :error
                    :prev nil
                    :meta {}
                    :params (:params payload)
                    :error (:error payload)})
        (save-data edb-schema payload nil)
        (remove-pending-datasource datasource-key))))

(defn mark-dependent-errors! [app-db app-datasources datasources edb-schema payload]
  (reduce (fn [acc [datasource-key val]]
            (-> app-db
                (save-meta datasource-key
                           {:status :error
                            :prev nil
                            :params nil
                            :error (:error payload)})
                (save-data edb-schema val nil)
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
            manually-invalidated? (contains? invalid-datasources datasource-key)
            ;; reload? should be refactored as it's not completely clear what's going on
            reload? (if (or (not datasource-deps-fulfilled?)
                            manually-invalidated?)
                      true
                      (not (and (or (= (:params datasource-meta)
                                       new-datasource-params)
                                    (= ::ignore new-datasource-params))
                                (= :completed (:status datasource-meta)))))]

        (when (nil? datasource)
          (throw (ex-info (str "Missing datasource " datasource-key) {:type ::missing-datasource})))

        (recur (assoc datasources-plan datasource-key
                      {:deps-fulfilled? datasource-deps-fulfilled?
                       :reload? reload?
                       :manually-invalidated? manually-invalidated?})
               (rest datasources-order)))
      datasources-plan)))

(defn clear-cache-for-invalidated-datasources! [app-db-atom invalid-datasources]
  (when (seq invalid-datasources)
    (let [app-db @app-db-atom
          app-db' (reduce
                   (fn [acc k] 
                     (dissoc-in acc [:kv ::req-cache k])) app-db invalid-datasources)]
      (reset! app-db-atom app-db'))))

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
         (clear-cache-for-invalidated-datasources! app-db-atom invalid-datasources)
         (reset! active-dataloader-id-atom dataloader-id)
         (p/promise
          (fn [resolve reject on-cancel]
            (let [running? (atom true) 
                  results-chan (chan)
                  plan (datasources-load-plan @app-db-atom datasources datasources-order edb-schema invalid-datasources)
                  start-nodes (filter #(and (:reload? (get plan %)) (:deps-fulfilled? (get plan %))) (keys plan))]

              (when (fn? on-cancel) (on-cancel #(swap! running? not)))

              (mark-pending! app-db-atom edb-schema (select-keys datasources (filter #(:reload? (get plan %)) (keys plan))))
              (start-loaders! app-db-atom datasources (select-keys datasources start-nodes) invalid-datasources results-chan edb-schema context)
              
              (go-loop []
                (if (and @running? (= dataloader-id @active-dataloader-id-atom))
                  (if (has-pending-datasources? @app-db-atom)
                    (let [[status payload] (<! results-chan)
                          t-dependents (dep/transitive-dependents g (:datasource payload))]
                      (case status
                        :ok (when (and @running? (= dataloader-id @active-dataloader-id-atom))
                              (store-datasource! app-db-atom edb-schema payload)
                              (start-dependent-loaders! app-db-atom datasources (select-keys datasources t-dependents) invalid-datasources results-chan edb-schema context))
                        :error (do
                                 (when ^boolean js/goog.DEBUG
                                   (let [error (:error payload)
                                         original-message (.-message error)
                                         message (str "Dataloader error in " (:datasource payload) " datasource (" original-message ")")]
                                     (set! (.-message error) message)
                                     (.error js/console error)))
                                 (reset! app-db-atom
                                         (-> @app-db-atom
                                             (store-datasource-error! edb-schema payload)
                                             (mark-dependent-errors!
                                              datasources (select-keys datasources t-dependents) edb-schema payload))))
                        nil)
                      (recur))
                    (resolve @app-db-atom))
                  (reject (ex-info "New dataloader started" {:type ::new-dataloader-started}))))))))))))
