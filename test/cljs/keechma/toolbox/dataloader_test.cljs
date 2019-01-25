(ns keechma.toolbox.dataloader-test
  (:require [cljs.test :refer-macros [deftest testing is async]]
            [keechma.toolbox.dataloader.core :as core]
            [keechma.toolbox.dataloader.app :as app]
            [promesa.core :as p]
            [entitydb.core :as edb]
            [cljs.core.async :refer [<! put! timeout]]
            [keechma.app-state :as app-state]
            [keechma.ui-component :as ui]
            [keechma.toolbox.test-util :refer [make-container]]
            [medley.core :refer [dissoc-in]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn map-loader [loader]
  (fn [reqs]
    (map loader reqs)))

(defn promised-datasource
  ([] (promised-datasource nil))
  ([data]
   (fn [params]
     (map (fn [loader-params]
            (p/promise (fn [resolve reject]
                         (let [value (or data (:params loader-params))]
                           (js/setTimeout #(resolve value) 1)))))
          params))))

(def simple-promised-datasource (promised-datasource))

(def simple-datasources
  {:jwt                   
   {:target [:kv :jwt]
    :loader (promised-datasource)
    :processor (fn [value datasource]
                 (str value "!"))
    :params (fn [prev route _]
              (or (:jwt route) "JWT"))}

   :no-deps
   {:target [:kv :no-deps]
    :loader (fn [params] (map (fn [p] true) params))}

   :current-user
   {:target [:kv :user :current]
    :deps   [:jwt]
    :loader simple-promised-datasource
    :params (fn [prev route {:keys [jwt]}]
              {:jwt jwt
               :current-user-id 1})}

   :users
   {:target [:kv :user :list]
    :deps   [:jwt]
    :loader simple-promised-datasource
    :params (fn [prev route {:keys [jwt]}]
              {:jwt jwt
               :users [{:id 1} {:id 2}]})}

   :current-user-favorites
   {:target [:kv :favorites :current]
    :deps   [:jwt :current-user]
    :loader simple-promised-datasource
    :params (fn [prev route {:keys [jwt current-user]}]
              {:jwt jwt
               :current-user current-user
               :favorites [{:id 3} {:id 4}]})}})

(deftest make-dataloader
  (async done
         (let [dataloader (core/make-dataloader simple-datasources)
               app-db-atom (atom {})]
           (->> (dataloader app-db-atom) 
                (p/map (fn []
                         (let [app-db @app-db-atom]
                           (is (= "JWT!" (get-in app-db [:kv :jwt])))
                           (is (= true (get-in app-db [:kv :no-deps])))
                           (is (= {:jwt "JWT!"
                                   :users [{:id 1} {:id 2}]}
                                  (get-in app-db [:kv :user :list])))
                           (is (= {:jwt "JWT!"
                                   :current-user-id 1})
                               (get-in app-db [:kv :user :current]))
                           (is (= {:jwt "JWT!"
                                   :current-user {:jwt "JWT!"
                                                  :current-user-id 1}
                                   :favorites [{:id 3} {:id 4}]}
                                  (get-in app-db [:kv :favorites :current])))
                           (swap! app-db-atom assoc-in [:route :data :jwt] "JWT2")
                           (dataloader app-db-atom))))
                (p/map (fn []
                         (let [app-db @app-db-atom]
                           (is (= "JWT2!" (get-in app-db [:kv :jwt])))
                           (is (= true (get-in app-db [:kv :no-deps])))
                           (is (= {:jwt "JWT2!"
                                   :users [{:id 1} {:id 2}]}
                                  (get-in app-db [:kv :user :list])))
                           (is (= {:jwt "JWT2!"
                                   :current-user-id 1})
                               (get-in app-db [:kv :user :current]))
                           (is (= {:jwt "JWT2!"
                                   :current-user {:jwt "JWT2!"
                                                  :current-user-id 1}
                                   :favorites [{:id 3} {:id 4}]}
                                  (get-in app-db [:kv :favorites :current])))
                           (done))))
                (p/error (fn []
                           (is (= false true "Promise rejected"))
                           (done)))))))

(def error-404 (js/Error. "404"))

(def datasources-with-errors
  {:jwt                   
   {:target [:kv :jwt]
    :loader (promised-datasource "JWT")
    :processor (fn [value datasource]
                 (str value "!"))}

   :current-user
   {:target [:kv :user :current]
    :deps   [:jwt]
    :loader (fn [params]
              (map (fn [_]
                     (p/promise (fn [_ reject]
                                  (js/setTimeout #(reject error-404) 1)))) params))}

   :current-user-favorites
   {:target [:kv :favorites :current]
    :deps   [:jwt :current-user]
    :loader (promised-datasource)
    :params (fn [prev route {:keys [jwt current-user]}]
              {:jwt jwt
               :current-user current-user
               :favorites [{:id 3} {:id 4}]})}})

(deftest make-dataloader-with-errors
  (async done
         (let [dataloader (core/make-dataloader datasources-with-errors)
               app-db-atom (atom {})
               route {}]
           (->> (dataloader app-db-atom)
                (p/map (fn []
                         (is (= (dissoc-in @app-db-atom [:kv :keechma.toolbox.dataloader.core/req-cache])

                                {:kv {:keechma.toolbox.dataloader.core/pending #{},
                                      :keechma.toolbox.dataloader.core/dataloader 
                                      {:jwt {:status :completed,
                                             :params nil,
                                             :error nil,
                                             :meta {},
                                             :prev {:status nil,
                                                    :error nil,
                                                    :params nil,
                                                    :data nil}},
                                       :current-user {:status :error,
                                                      :prev nil,
                                                      :meta {},
                                                      :params nil,
                                                      :error error-404},
                                       :current-user-favorites {:status :error,
                                                                :prev nil,
                                                                :params nil,
                                                                :error error-404}},
                                      :jwt "JWT!",
                                      :user {:current nil},
                                      :favorites {:current nil}}}))
                         (done)))
                (p/error (fn []))))))

(deftest route-dependent-dataloader
  (let [call-counter (atom 0)
        loader (fn [params]
                 (swap! call-counter inc)
                 (map (fn [p] (:params p)) params))
        datasources {:jwt {:target [:kv :jwt]
                           :loader loader
                           :params (fn [_ _ _]
                                     "123")}
                     :foo {:target [:kv :foo]
                           :loader loader
                           :params (fn [_ route _]
                                     (:foo route))}}
        app-db-atom (atom {:route {:data {:foo :bar}}})
        dataloader (core/make-dataloader datasources)]
    (async done
           (->> (dataloader app-db-atom)
                (p/map (fn []
                         (is (= (dissoc-in @app-db-atom [:kv :keechma.toolbox.dataloader.core/req-cache])
                                {:route {:data {:foo :bar}},
                                 :kv {:keechma.toolbox.dataloader.core/pending #{},
                                      :keechma.toolbox.dataloader.core/dataloader
                                      {:jwt {:status :completed,
                                             :params "123",
                                             :error nil,
                                             :meta {},
                                             :prev {:status nil,
                                                    :error nil,
                                                    :params nil,
                                                    :data nil}},
                                       :foo {:status :completed,
                                             :params :bar,
                                             :error nil,
                                             :meta {},
                                             :prev {:status nil,
                                                    :error nil,
                                                    :params nil,
                                                    :data nil}}},
                                      :jwt "123",
                                      :foo :bar}}

                                ))
                         (swap! app-db-atom assoc-in [:route :data :foo] :baz)
                         (dataloader app-db-atom)))
                (p/map (fn []

                         (is (= (dissoc-in @app-db-atom [:kv :keechma.toolbox.dataloader.core/req-cache])
                                {:route {:data {:foo :baz}},
                                 :kv {:keechma.toolbox.dataloader.core/pending #{},
                                      :keechma.toolbox.dataloader.core/dataloader 
                                      {:jwt {:status :completed,
                                             :params "123",
                                             :error nil,
                                             :meta {},
                                             :prev {:status nil,
                                                    :error nil,
                                                    :params nil,
                                                    :data nil}},
                                       :foo {:status :completed,
                                             :params :baz,
                                             :error nil,
                                             :meta {},
                                             :prev {:status nil,
                                                    :error nil,
                                                    :params nil,
                                                    :data :bar,
                                                    :meta {:status :completed,
                                                           :params :bar,
                                                           :error nil,
                                                           :meta {}}}}},
                                      :jwt "123",
                                      :foo :baz}}




                                ))
                         (dataloader app-db-atom)))
                (p/map (fn []   
                         (= @call-counter 3)
                         (done)))
                (p/error (fn []))))))

(def datasources-with-edb
  {:jwt {:target [:kv :jwt]
         :loader (fn [loader-params]
                   (map (fn [_] "JWT") loader-params))}
   :current-user {:target [:edb/named-item :user/current]
                  :deps [:jwt]
                  :loader (fn [loader-params]
                            (map (fn [p] 
                                   (when-let [user-id (get-in p [:params :user-id])]
                                     {:id user-id
                                      :jwt (get-in p [:params :jwt])
                                      :name (str "user " user-id)}))
                                 loader-params))
                  :params (fn [prev route {:keys [jwt]}]
                            {:user-id (:id route)
                             :jwt jwt})}
   :users {:target [:edb/collection :user/list]
           :deps [:jwt]
           :loader (fn [loader-params]
                     (map (fn [p]
                            [{:id 1 :last-name "user 1 last-name" :jwt-list (get-in p [:params :jwt])}
                             {:id 2 :last-name "user 2 last-name" :jwt-list (get-in p [:params :jwt])}])
                          loader-params))
           :params (fn [prev route {:keys [jwt]}]
                     {:jwt jwt})}})

(deftest dataloader-with-edb
  (let [app-db-atom (atom {:route {:data {}}})
        edb-schema {:user {:id :id}}
        dataloader (core/make-dataloader datasources-with-edb edb-schema)]
    (async done
           (->> (dataloader app-db-atom)
                (p/map (fn []
                         (let [app-db @app-db-atom
                               edb (:entity-db app-db)]
                           (is (= [{:id 1 :last-name "user 1 last-name" :jwt-list "JWT"}
                                   {:id 2 :last-name "user 2 last-name" :jwt-list "JWT"}]
                                  (edb/get-collection edb-schema edb :user :list)))
                           (is (= nil (edb/get-named-item edb-schema edb :user :current)))
                           (is (= "JWT" (get-in app-db [:kv :jwt])))
                           (swap! app-db-atom assoc-in [:route :data] {:id 1})
                           (dataloader app-db-atom))))
                (p/map (fn []
                         (let [app-db @app-db-atom
                               edb (:entity-db app-db)]
                           (is (= [{:id 1 :last-name "user 1 last-name" :name "user 1" :jwt "JWT" :jwt-list "JWT"}
                                   {:id 2 :last-name "user 2 last-name" :jwt-list "JWT"}]
                                  (edb/get-collection edb-schema edb :user :list)))
                           (is (= {:id 1 :last-name "user 1 last-name" :name "user 1" :jwt "JWT" :jwt-list "JWT"}
                                  (edb/get-named-item edb-schema edb :user :current)))
                           (is (= "JWT" (get-in app-db [:kv :jwt])))
                           (swap! app-db-atom assoc-in [:route :data] {:id 2})
                           (dataloader app-db-atom))))
                (p/map (fn []
                         (let [app-db @app-db-atom
                               edb (:entity-db app-db)]
                           (is (= [{:id 1 :last-name "user 1 last-name" :name "user 1" :jwt "JWT" :jwt-list "JWT"}
                                   {:id 2 :last-name "user 2 last-name" :name "user 2" :jwt "JWT" :jwt-list "JWT"}]
                                  (edb/get-collection edb-schema edb :user :list)))
                           (is (= {:id 2 :last-name "user 2 last-name" :name "user 2" :jwt "JWT" :jwt-list "JWT"}
                                  (edb/get-named-item edb-schema edb :user :current)))
                           (is (= "JWT" (get-in app-db [:kv :jwt])))
                           (done))))
                (p/error (fn []))))))



(def datasources-with-removing-edb-collection
  {:users {:target [:edb/collection :user/list]
           :params (fn [prev route _]
                     route)
           :loader (fn [reqs]
                     (map (fn [p]
                            (if-let [page (get-in p [:params :page])]
                              [{:id 1}]
                              [])) reqs))}})

(deftest dataloader-knows-when-to-remove-edb-collection

  (let [app-db-atom (atom {:route {:data {:page "users"}}})
        edb-schema {:user {:id :id}}
        dataloader (core/make-dataloader datasources-with-removing-edb-collection edb-schema)]
    (async done
           (->> (dataloader app-db-atom)
                (p/map (fn []
                         (let [app-db @app-db-atom
                               edb (:entity-db app-db)]
                           (is (= [{:id 1}]
                                  (edb/get-collection edb-schema edb :user :list)))
                           (swap! app-db-atom assoc-in [:route :data] {})
                           (dataloader app-db-atom))))
                (p/map (fn []
                         (let [app-db @app-db-atom
                               edb (:entity-db app-db)]
                           (is (= [] (edb/get-collection edb-schema edb :user :list)))
                           (done))))
                (p/error (fn []))))))

(def datasources-with-edb-relations
  {:current-article {:target [:edb/named-item :article/current]
                     :loader (fn [loader-params]
                               (map (fn [p] 
                                      (core/->EntityDBWithRelations
                                       {:id 3
                                        :tags [{:id 1} {:id 3}]
                                        :name "article 3"
                                        :author {:id 2}}
                                       {:author {:id 2 :name "Author 2"}
                                        :tag [{:id 1 :tag "tag1"} {:id 3 :tag "tag3"}]}))
                                    loader-params))
                     :params (fn [prev route deps])}
   :articles {:target [:edb/collection :article/list]
              :loader (fn [loader-params]
                        (map (fn [p]
                               (core/->EntityDBWithRelations
                                [{:id 1 :name "article 1 name" :tags [{:id 1} {:id 2}] :author {:id 1}}
                                 {:id 2 :name "article 2 name" :author {:id 2}}]
                                {:tag [{:id 1 :tag "tag1"}
                                       {:id 2 :tag "tag2"}]
                                 :author [{:id 2 :name "Author 2"}
                                          {:id 1 :name "Author 1"}]}))
                             loader-params))
              :params (fn [prev route deps] )}})


(deftest inserting-related-items
  (let [app-db-atom (atom {:route {:data {}}})
        edb-schema {:article {:id :id :relations {:author [:one :author]
                                                  :tags [:many :tag]}}
                    :tag {:id :id}
                    :author {:id :id}}
        dataloader (core/make-dataloader datasources-with-edb-relations edb-schema)]
    (async done
           (->> (dataloader app-db-atom)
                (p/map (fn []
                         (is (= {:article
                                 {:c-one {:current 3}
                                  :store
                                  {1 {:name "article 1 name" :id 1}
                                   2 {:name "article 2 name" :id 2}
                                   3 {:name "article 3" :id 3}}
                                  :c-many {:list [1 2]}}
                                 :author
                                 {:c-one
                                  {[:article 3 :author] 2 [:article 1 :author] 1 [:article 2 :author] 2}
                                  :store {1 {:name "Author 1" :id 1} 2 {:name "Author 2" :id 2}}}
                                 :tag
                                 {:store
                                  {1 {:id 1 :tag "tag1"} 2 {:id 2 :tag "tag2"} 3 {:id 3 :tag "tag3"}}
                                  :c-many {[:article 3 :tags] [1 3] [:article 1 :tags] [1 2]}}}
                                (:entity-db @app-db-atom)))
                         (done)))
                (p/error (fn []))))))

(def datasources-with-context-loader
  {:foo {:loader (fn [reqs context]
                   (map (fn [r]
                          (let [loader (get context :loader)]
                            (loader (:params r))))
                        reqs))
         :params (fn [_ _ _])
         :target [:foo]}})

(deftest using-context-in-loader
  (let [context {:loader (fn [params] {:source :context})}
        app-db-atom (atom {:route {:data {}}})
        dataloader (core/make-dataloader datasources-with-context-loader)]
    (async done
           (->> (dataloader app-db-atom {:context context})
                (p/map (fn []
                         (is (= {:source :context} (:foo @app-db-atom)))
                         (done)))
                (p/error (fn []))))))

(defn make-datasource-that-tracks-calls [tracking-atom]
  {:user {:loader (fn [reqs]
                    (map (fn [r]
                           (when-let [params (:params r)]
                             (swap! tracking-atom inc)
                             (p/promise (fn [resolve reject]
                                          (js/setTimeout #(resolve {:some :data}) 10)))))
                         reqs))
          :params (fn [_ _ _]
                    {:some :params})
          :target [:user]}})


(deftest calling-dataloader-twice-shouldnt-make-duplicate-requests
  (let [tracking-atom (atom 0)
        app-db-atom (atom {:route {:data {}}})
        dataloader (core/make-dataloader (make-datasource-that-tracks-calls tracking-atom))]
    (->> (dataloader app-db-atom)
         (p/error (fn [])))
    (async done
           (->> (dataloader app-db-atom)
                (p/map (fn []
                         (is (= {:some :data} (:user @app-db-atom)))
                         (is (= 1 @tracking-atom))
                         (done)))
                (p/error (fn [] (is false)))))))

(defn make-inc-datasources []
  (let [counter (atom 0)]
    {:counter {:target [:kv :counter]
               :params (fn [_ _ _])
               :loader (fn [reqs]
                         (map (fn [r]
                                (swap! counter inc)
                                @counter)
                              reqs))}
     :rel-counter {:target [:kv :rel-counter]
                   :deps [:counter]
                   :params (fn [_ _ {:keys [counter]}]
                             counter)
                   :loader (fn [reqs]
                             (map (fn [r]
                                    (inc (:params r)))
                                  reqs))}}))

(deftest manual-datasource-invalidation
  (let [app-db-atom (atom {:route {:data {}}})
        datasources (make-inc-datasources)
        dataloader (core/make-dataloader datasources)]
    (async done
           (->> (dataloader app-db-atom)
                (p/map (fn []
                         (is (= 1 (get-in @app-db-atom [:kv :counter])))
                         (is (= 2 (get-in @app-db-atom [:kv :rel-counter])))
                         (dataloader app-db-atom {:invalid-datasources #{:counter}})))
                (p/map (fn []
                         (is (= 2 (get-in @app-db-atom [:kv :counter])))
                         (is (= 3 (get-in @app-db-atom [:kv :rel-counter])))
                         (done)))
                (p/error (fn [] (is false)))))))

(defn make-params-test-datasources [log]
  {:counter {:target [:kv :counter]
             :params (fn [prev _ _]
                       (swap! log conj prev)
                       (gensym "params"))
             :loader (fn [reqs]
                       (map (fn [r] 1) reqs))}})

(deftest params-test
  (let [app-db-atom (atom {:route {:data {}}})
        log (atom [])
        datasources (make-params-test-datasources log)
        dataloader (core/make-dataloader datasources)]
    (async done
           (->> (dataloader app-db-atom)
                (p/map (fn []
                         (dataloader app-db-atom)))
                (p/map (fn []
                         (dataloader app-db-atom)))
                (p/map (fn []
                         (dataloader app-db-atom)))
                (p/map (fn []
                         (doseq [l (partition 2 @log)]
                           (is (apply = l)))
                         (done)))
                (p/error (fn [] (is false)))))))


(defn make-prev-validation-datasources [log]
  (let [counter (atom 1)]
    {:counter {:target [:kv :counter]
               :params (fn [{:keys [data]} _ _]
                         (swap! log conj data)
                         (gensym "params"))
               :loader (fn [reqs]
                         (map (fn [r]
                                (swap! counter inc)
                                @counter)
                              reqs))}}))

(deftest prev-validation
  (let [log (atom [])
        app-db-atom (atom {:route {:data {}}})
        datasources (make-prev-validation-datasources log)
        dataloader (core/make-dataloader datasources)]
    (async done
           (->> (dataloader app-db-atom)
                (p/map (fn []
                         (dataloader app-db-atom)))
                (p/map (fn []
                         (dataloader app-db-atom)))
                (p/map (fn []
                         (dataloader app-db-atom)))
                (p/map (fn []
                         (is (= [nil nil 2 2 3 3 4 4] @log))
                         (done)))
                (p/error (fn [] (is false)))))))

(defn make-infinite-pagination-datasources []
  (let [users-list (vec (map (fn [i] {:id i}) (range 1 11)))]
    {:users {:target [:kv :users]
             :loader (fn [reqs]
                       (map (fn [r]
                              (let [offset (get-in r [:params :offset])]
                                (subvec users-list offset (+ offset 2))))
                            reqs))
             :processor (fn [data req]
                          (let [prev-data (or (get-in req [:prev :data]) [])
                                params (:params req)
                                prev-params (or (get-in req [:prev :meta :params]) {})]

                            (if (= (dissoc params :offset)
                                   (dissoc prev-params :offset))
                              (concat prev-data data)
                              data)))
             :params (fn [prev route _]
                       (let [offset (:offset route)
                             some-param (:some-param route)]
                         {:offset (or offset 0)
                          :some-param some-param}))}}))

(deftest infinite-pagination
  (let [datasources (make-infinite-pagination-datasources)
        app-db-atom (atom {:route {:data {}}})
        dataloader (core/make-dataloader datasources)]
    (async done
           (->> (dataloader app-db-atom)
                (p/map (fn []
                         (is (= [{:id 1} {:id 2}]
                                (get-in @app-db-atom [:kv :users])))
                         (swap! app-db-atom assoc-in [:route :data :offset] 2)
                         (dataloader app-db-atom)))
                (p/map (fn []
                         (is (= [{:id 1} {:id 2} {:id 3} {:id 4}]
                                (get-in @app-db-atom [:kv :users])))
                         (swap! app-db-atom assoc-in [:route :data] {:offset 4 :some-param :foo})
                         (dataloader app-db-atom)))
                (p/map (fn []
                         (is (= [{:id 5} {:id 6}]
                                (get-in @app-db-atom [:kv :users])))))
                (p/map #(done))
                (p/error (fn [e]
                           (is (= false true "Promise rejected"))
                           (done)))))))

(defn make-race-condition-datasource [log]
  {:user {:target [:kv :user]
          :loader (fn [reqs]
                    (map (fn [r]
                           (let [u-delay (or (get-in r [:params :delay]) 0)
                                 user (get-in r [:params :user])]
                             (p/promise (fn [resolve reject]
                                          (js/setTimeout
                                           (fn []
                                             (swap! log conj user)
                                             (resolve user)) u-delay))))) reqs))
          :params (fn [_ route _]
                    route)}})

(deftest race-condition-test
  (let [log (atom [])
        datasources (make-race-condition-datasource log)
        dataloader (core/make-dataloader datasources)
        app-db-atom (atom {:route {:data {:delay 100 :user 1}}})]
    (->> (dataloader app-db-atom)
         (p/error (fn [])))
    (async done
           (swap! app-db-atom assoc-in [:route :data] {:delay 0 :user 2})
           (->> (dataloader app-db-atom)
                (p/map (fn []
                         (is (= 2 (get-in @app-db-atom [:kv :user])))
                         (p/promise (fn [resolve reject]
                                      (js/setTimeout resolve 100)))))
                (p/map (fn []
                         (is (= [2 1] @log))
                         (is (= 2 (get-in @app-db-atom [:kv :user])))
                         (done)))
                (p/error (fn [] (is false)))))))

(defn make-race-condition-datasource-2 [log]
  (let [product-id (atom 0)]
    {:user {:target [:kv :user]
            :loader (fn [reqs]
                      (map (fn [r]
                             (let [u-delay (or (get-in r [:params :user-delay]) 0)
                                   user (get-in r [:params :user])]
                               (p/promise (fn [resolve reject]
                                            (js/setTimeout
                                             (fn []
                                               (swap! log conj user)
                                               (resolve user)) u-delay))))) reqs))
            :params (fn [_ route _]
                      route)}
     :products {:target [:kv :product]
                :deps [:user]
                :loader (fn [reqs]
                          (map (fn [r]
                                 (swap! product-id inc)
                                 (let [p-delay (or (get-in r [:params :product-delay]) 0)
                                       product {:id @product-id :user (get-in r [:params :user])}]
                                   (p/promise (fn [resolve reject]
                                                (js/setTimeout
                                                 (fn []
                                                   (swap! log conj product)
                                                   (resolve product)) p-delay))))) reqs))
                :params (fn [_ r {:keys [user]}]
                          (assoc r :user user))}}))

(deftest race-condition-test-2
  (let [log (atom [])
        datasources (make-race-condition-datasource-2 log)
        dataloader (core/make-dataloader datasources)
        app-db-atom (atom {:route {:data {:user-delay 0 :product-delay 50 :user 1}}})]
    (->> (dataloader app-db-atom)
         (p/error (fn [])))
    (async done
           
           (->> (p/promise (fn [resolve reject]
                             (js/setTimeout
                              (fn []
                                (swap! app-db-atom assoc-in [:route :data] {:user-delay 0 :product-delay 0 :user 2})
                                (resolve)) 10)))

                (p/map #(dataloader app-db-atom))
                (p/map (fn []
                         (is (= 2 (get-in @app-db-atom [:kv :user])))
                         (p/promise (fn [resolve reject]
                                      (js/setTimeout resolve 100)))))
                (p/map (fn []
                         (is (= [1 2 {:id 2 :user 2} {:id 1 :user 1}] @log))
                         (is (= 2 (get-in @app-db-atom [:kv :user])))
                         (is (= {:id 2 :user 2} (get-in @app-db-atom [:kv :product])))
                         (done)))
                (p/error (fn [] (is false)))))))

(defn delayed-loader [reqs]
  (map 
   (fn [r]
     (let [d (get-in r [:params :delay])
           v (get-in r [:params :value])]
       
       (p/promise (fn [resolve reject]
                    (js/setTimeout #(resolve v) d)))))
   reqs))

(defn make-cached-datasources [ds]
  (reduce (fn [acc [i [l d]]]
            (assoc acc l {:target [:kv :foo l]
                          :processor (fn [data]
                                       (str i " - " data))
                          :loader delayed-loader
                          :params (fn [_ _ _]
                                    {:delay d
                                     :value l})}))
          {} (map-indexed vector ds)))


(deftest cached-test
  (let [ds [["A" 30]
            ["B" 100]
            ["C" 30]
            ["D" 100]
            ["E" 30]
            ["F" 100]
            ["G" 30]
            ["H" 100]
            ["I" 30]
            ["J" 100]
            ["K" 30]
            ["L" 100]
            ["M" 30]
            ["N" 100]
            ["O" 30]
            ["P" 100]] 
        datasources (make-cached-datasources ds)
        dataloader (core/make-dataloader datasources)
        app-db-atom (atom {:route {:data {}}})
        success-res (reduce (fn [acc [i [l _]]]
                              (assoc acc l (str i " - " l)))
                            {} (map-indexed vector ds))]

    (->> (dataloader app-db-atom)
         (p/error (fn [])))
    

    (async done
           (->> (p/promise (fn [resolve _] (js/setTimeout resolve 50)))
                (p/map #(dataloader app-db-atom {:invalid-datasources #{"A" "C" "E"}}))
                (p/map (fn []
                         (let [res (get-in @app-db-atom [:kv :foo])]
                           (is (= success-res res))
                           #_(println "-------------"
                                      (reduce-kv (fn [acc k v]
                                                   (let [success-v (get success-res k)]
                                                     (if (= v success-v)
                                                       acc
                                                       (conj acc [success-v v])))) [] res))
                           (done))))
                (p/error (fn [] (is false)))))))


(defn make-excess-loader-calls-datasources [log]
  {:user     {:target [:kv :user]
              :loader (fn [reqs]
                        (map (fn [{:keys [params]}]
                               params) reqs))
              :params (fn [_ {:keys [user]} _]
                        {:user user})}
   :projects {:target [:kv :projects]
              :loader (fn [reqs]
                        (map (fn [{:keys [params]}]
                               (swap! log conj params)
                               params) reqs))
              :deps   [:user]
              :params (fn [_ _ _]
                        {:call :true})}})

(deftest excess-loader-calls
  (let [log (atom [])
        datasources (make-excess-loader-calls-datasources log)
        dataloader (core/make-dataloader datasources)
        app-db-atom (atom {:route {:data {}}})]
    (async done
           (->> (dataloader app-db-atom)
                (p/map (fn []
                         (swap! app-db-atom assoc-in [:route :data :user] 1)
                         (dataloader app-db-atom)))
                (p/map (fn []
                         (is (= @log [{:call :true}]))
                         (done)))
                (p/error (fn [error]
                           (is false)
                           (done)))))))


(deftest check-dataloader-rejection
  (let [log (atom [])
        datasources (make-race-condition-datasource-2 log)
        dataloader (core/make-dataloader datasources)
        app-db-atom (atom {:route {:data {:user-delay 0 :product-delay 50 :user 1}}})]
    (->> (dataloader app-db-atom)
         (p/error (fn [e]
                    (is (= :keechma.toolbox.dataloader.core/new-dataloader-started (:type (.-data e)))))))
    (async done
           (->> (dataloader app-db-atom)
                (p/map #(done))
                (p/error (fn [] (is false)))))))

(def datasources-with-missing-datasource 
  {:user {:target [:kv :user]
          :deps [:jwt]
          :loader (fn [reqs] (map (fn [r] nil) reqs))
          :params (fn [_ _ {:keys [jwt]}])}})

(deftest depending-on-non-existing-datasource
  (let [log (atom 0)
        datasources datasources-with-missing-datasource
        dataloader (core/make-dataloader datasources)
        app-db-atom (atom {:route {:data {}}})]
    (async done
           (->> (dataloader app-db-atom)
                (p/map (fn []
                         (is false)
                         (done)))
                (p/error (fn [e]
                           (is (= :keechma.toolbox.dataloader.core/missing-datasource (:type (.-data e))))
                           (done)))))))

(defn make-invalidation-datasources []
  (let [call-count$ (atom {:current-user 1
                           :users 1})]
    {:current-user
     {:target [:kv :user :current]
      :loader (fn [reqs]
                (map
                 (fn [r]
                   (let [call-count (or (:current-user @call-count$) 0)]
                     (swap! call-count$ update :current-user inc)
                     {:id 1 :call-count call-count}))
                 reqs))
      :params (fn [_ _ _] true)}
     
     :users
     {:target [:kv :user :list]
      :loader (fn [reqs]
                (map
                 (fn [r]
                   (let [call-count (or (:users @call-count$) 0)]
                     (swap! call-count$ update :users inc)
                     [{:id 2 :call-count call-count}]))
                 reqs))
      :params (fn [_ _ _] true)}}))

(deftest invalidation-datasources
  (let [[c unmount] (make-container)
        app (-> {:html-element c
                 :components {:main (ui/constructor {:renderer (fn [ctx] [:div])})}}
                (app/install (make-invalidation-datasources) {}))
        app-state (app-state/start! app)
        commands-chan (:commands-chan app-state)
        app-db (:app-db app-state)]
    (async done
           (go
             (<! (timeout 10))
             (is (= {:current {:id 1 :call-count 1}
                     :list [{:id 2 :call-count 1}]}
                    (get-in @app-db [:kv :user])))
             
             
             (put! commands-chan [[core/id-key :load-data] [:current-user]])
             (<! (timeout 10))

             (is (= {:current {:id 1 :call-count 2}
                     :list [{:id 2 :call-count 1}]}
                    (get-in @app-db [:kv :user])))

             (put! commands-chan [[core/id-key :load-data] :all])
             (<! (timeout 10))

             (is (= {:current {:id 1 :call-count 3}
                     :list [{:id 2 :call-count 2}]}
                    (get-in @app-db [:kv :user])))
             
             (app-state/stop! app-state done)))))

(defn make-cached-res-datasources []
  (let [call-count$ (atom {:current-user 1
                           :users 1
                           :current-user-cache 0})]
    {:current-user
     {:target [:kv :user :current]
      :loader (fn [reqs]
                (map
                 (fn [{:keys [params]}]
                   (when params
                     (let [call-count (or (:current-user @call-count$) 0)]
                       (swap! call-count$ update :current-user inc)
                       {:id 1 :call-count call-count})))
                 reqs))
      :params (fn [_ {:keys [id]} _]
                (when id true))
      :cache-valid? (fn [& args]
                      (swap! call-count$ update :current-user-cache inc)
                      (< (:current-user-cache @call-count$) 3))}
     
     :users
     {:target [:kv :user :list]
      :loader (fn [reqs]
                (map
                 (fn [{:keys [params]}]
                   (when params
                     (let [call-count (or (:users @call-count$) 0)]
                       (swap! call-count$ update :users inc)
                       [{:id 2 :call-count call-count}])))
                 reqs))
      :params (fn [_ {:keys [id]} _] (when-not id true))
      :cache-valid? (constantly true)}}))

(deftest cached-res-datasources
  (async done
         (let [cached-datasources (make-cached-res-datasources)
               dataloader (core/make-dataloader cached-datasources)
               app-db-atom (atom {:route {:data {}}})]
           (go
             (dataloader app-db-atom)
             (<! (timeout 10))
             (is (= {:current nil
                     :list [{:id 2 :call-count 1}]}
                    (get-in @app-db-atom [:kv :user])))
             (swap! app-db-atom assoc-in [:route :data :id] 1)
             (dataloader app-db-atom)
             (<! (timeout 10))
             (is (= {:current {:id 1 :call-count 1}
                     :list nil}
                    (get-in @app-db-atom [:kv :user])))
             (swap! app-db-atom dissoc-in [:route :data :id])
             (dataloader app-db-atom)
             (<! (timeout 10))
             (is (= {:current nil
                     :list [{:id 2 :call-count 1}]}
                    (get-in @app-db-atom [:kv :user])))
             (swap! app-db-atom assoc-in [:route :data :id] 1)
             (dataloader app-db-atom)
             (<! (timeout 10))
             (is (= {:current {:id 1 :call-count 1}
                     :list nil}
                    (get-in @app-db-atom [:kv :user])))
             (dataloader app-db-atom {:invalid-datasources #{:current-user :users}})
             (<! (timeout 10))
             (is (= {:current {:id 1 :call-count 2}
                     :list nil}
                    (get-in @app-db-atom [:kv :user])))
             (swap! app-db-atom dissoc-in [:route :data :id])
             (dataloader app-db-atom)
             (<! (timeout 10))
             (is (= {:current nil
                     :list [{:id 2 :call-count 2}]}
                    (get-in @app-db-atom [:kv :user])))
             (swap! app-db-atom assoc-in [:route :data :id] 1)
             (dataloader app-db-atom)
             (<! (timeout 10))
             (is (= {:current {:id 1 :call-count 3}
                     :list nil}
                    (get-in @app-db-atom [:kv :user])))
             (swap! app-db-atom dissoc-in [:route :data :id])
             (dataloader app-db-atom)
             (<! (timeout 10))
             (is (= {:current nil
                     :list [{:id 2 :call-count 2}]}
                    (get-in @app-db-atom [:kv :user])))
             (swap! app-db-atom assoc-in [:route :data :id] 1)
             (dataloader app-db-atom)
             (<! (timeout 10))
             (is (= {:current {:id 1 :call-count 4}
                     :list nil}
                    (get-in @app-db-atom [:kv :user])))
             (swap! app-db-atom dissoc-in [:route :data :id])
             (dataloader app-db-atom)
             (<! (timeout 10))
             (is (= {:current nil
                     :list [{:id 2 :call-count 2}]}
                    (get-in @app-db-atom [:kv :user])))
             (done)))))

(def custom-get-set-datasources
  {:current-user
   {:target [:edb/named-item :user/current]
    :loader (map-loader
             (fn [{:keys [params]}]
               (when params
                 (p/promise (fn [resolve _]
                              (js/setTimeout #(resolve {:id 1 :email "konjevic@gmail.com" :first "Mihael" :last "Konjevic"}) 100))))))
    :params (fn [_ {:keys [page id]} _]
              (when (= :user page) id))}

   :init-current-user
   {:get (fn [app-db _]
           (let [edb (:entity-db app-db)]
             (edb/get-named-item {} edb :user :current)))
    :set (fn [app-db {:keys [params]} _]
           (if params
             (let [edb (:entity-db app-db)
                   user (edb/get-item-by-id {} edb :user params)]
               (if user
                 (assoc app-db :entity-db (edb/insert-named-item {} edb :user :current user))
                 app-db))
             app-db)) 
    :params (fn [_ {:keys [page id]} _]
              (when (= :user page) id))}

   :users
   {:target [:edb/collection :user/list]
    :loader (map-loader
             (fn [{:keys [params]}]
               (when params
                 [{:id 1 :email "konjevic@gmail.com"}
                  {:id 2 :email "konjevic+1@gmail.com"}])))
    :params (fn [_ {:keys [page]} _]
              (= :users page))}})

(deftest custom-get-set
  (async done
         (let [dataloader (core/make-dataloader custom-get-set-datasources)
               app-db-atom (atom {:route {:data {:page :users}}})]
           (go
             (dataloader app-db-atom)
             (<! (timeout 10))
             (is (= [{:id 1 :email "konjevic@gmail.com"}
                     {:id 2 :email "konjevic+1@gmail.com"}]
                    (edb/get-collection {} (:entity-db @app-db-atom) :user :list)))
             (swap! app-db-atom assoc-in [:route :data] {:id 1 :page :user})
             (dataloader app-db-atom)
             (<! (timeout 10))
             (is (= {:id 1 :email "konjevic@gmail.com"}
                    (edb/get-named-item {} (:entity-db @app-db-atom) :user :current)))
             (<! (timeout 100))
             (is (= {:id 1 :email "konjevic@gmail.com" :first "Mihael" :last "Konjevic"}
                    (edb/get-named-item {} (:entity-db @app-db-atom) :user :current)))
             (done)))))
