(ns keechma.toolbox.dataloader.core-test
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
                (p/error (fn []
                           (is (= false true "Promise rejected"))
                           (done)))
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
                           (done))))))))

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
                                  (js/setTimeout #(reject "404") 1)))) params))}

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
                                  {:current-user {:params nil, :status :error, :prev nil, :error "404"}
                                   :current-user-favorites
                                   {:params nil, :status :error, :prev nil, :error "404"}
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
