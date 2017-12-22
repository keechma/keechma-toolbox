(ns keechma.toolbox.dataloader-test
  (:require [cljs.test :refer-macros [deftest testing is async]]
            [keechma.toolbox.dataloader.core :as core]
            [promesa.core :as p]
            [entitydb.core :as edb]))

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
                         (is (= @app-db-atom
                                {:kv
                                 {:keechma.toolbox.dataloader.core/dataloader
                                  {:current-user {:params nil, :status :error, :prev nil, :error error-404}
                                   :current-user-favorites
                                   {:params nil, :status :error, :prev nil, :error error-404}
                                   :jwt
                                   {:meta {}
                                    :params nil
                                    :status :completed
                                    :prev
                                    {:meta {:status :pending, :prev {:value nil}}
                                     :value nil
                                     :params nil
                                     :status nil
                                     :error nil
                                     :data nil}
                                    :error nil}}
                                  :favorites {:current nil}
                                  :jwt "JWT!"
                                  :user {:current nil}}}))
                         (done)))))))

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
                         (is (= @app-db-atom
                                {:kv
                                 {:keechma.toolbox.dataloader.core/dataloader
                                  {:jwt
                                   {:meta {}
                                    :params "123"
                                    :status :completed
                                    :prev
                                    {:meta {:status :pending, :prev {:value nil}}
                                     :value nil
                                     :params nil
                                     :status nil
                                     :error nil
                                     :data nil}
                                    :error nil}
                                   :foo
                                   {:meta {}
                                    :params :bar
                                    :status :completed
                                    :prev
                                    {:meta {:status :pending, :prev {:value nil}}
                                     :value nil
                                     :params nil
                                     :status nil
                                     :error nil
                                     :data nil}
                                    :error nil}}
                                  :jwt "123"
                                  :foo :bar}
                                 :route {:data {:foo :bar}}}))
                         (swap! app-db-atom assoc-in [:route :data :foo] :baz)
                         (dataloader app-db-atom)))
                (p/map (fn []

                         
                         (is (= @app-db-atom
                                {:kv
                                 {:keechma.toolbox.dataloader.core/dataloader
                                  {:jwt
                                   {:meta {}
                                    :params "123"
                                    :status :completed
                                    :prev
                                    {:meta {:status :pending, :prev {:value nil}}
                                     :value nil
                                     :params nil
                                     :status nil
                                     :error nil
                                     :data nil}
                                    :error nil}
                                   :foo
                                   {:meta {}
                                    :params :baz
                                    :status :completed
                                    :prev
                                    {:meta
                                     {:status :pending
                                      :prev
                                      {:meta {}, :value :bar, :params :bar, :status :completed, :error nil}}
                                     :value nil
                                     :params nil
                                     :status nil
                                     :error nil
                                     :data :bar}
                                    :error nil}}
                                  :jwt "123"
                                  :foo :baz}
                                 :route {:data {:foo :baz}}}))
                         (dataloader app-db-atom)))
                (p/map (fn []   
                         (= @call-counter 3)
                         (done)))))))

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
                           (done))))))))



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
                           (done))))))))

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
                ))))

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
           (->> (dataloader app-db-atom context)
                (p/map (fn []
                         (is (= {:source :context} (:foo @app-db-atom)))
                         (done)))))))

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
    (dataloader app-db-atom)
    (async done
           (->> (dataloader app-db-atom)
                (p/map (fn []
                         (is (= {:some :data} (:user @app-db-atom)))
                         (is (= 1 @tracking-atom))
                         (done)))))))
