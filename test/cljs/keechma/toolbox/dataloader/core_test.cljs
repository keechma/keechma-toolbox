(ns keechma.toolbox.dataloader.core-test
  (:require [cljs.test :refer-macros [deftest testing is async]]
            [keechma.toolbox.dataloader.core :as core]
            [promesa.core :as p]))

(defn promised-datasource
  ([] (promised-datasource nil))
  ([data]
   (fn [params]
     (map (fn [loader-params]
            (p/promise (fn [resolve reject]
                         (let [value (or data (:params loader-params))]
                           (js/setTimeout #(resolve value) 1)))))
          params))))

(def simple-datasources
  {:jwt                   
   {:target [:kv :jwt]
    :loader (promised-datasource "JWT")
    :processor (fn [value datasource]
                 (str value "!"))}

   :no-deps
   {:target [:kv :no-deps]
    :loader (fn [params] (map (fn [p] true) params))}

   :current-user
   {:target [:kv :user :current]
    :deps   [:jwt]
    :loader (promised-datasource)
    :params (fn [prev route {:keys [jwt]}]
              {:jwt jwt
               :current-user-id 1})}

   :users
   {:target [:kv :user :list]
    :deps   [:jwt]
    :loader (promised-datasource)
    :params (fn [prev route {:keys [jwt]}]
              {:jwt jwt
               :users [{:id 1} {:id 2}]})}

   :current-user-favorites
   {:target [:kv :favorites :current]
    :deps   [:jwt :current-user]
    :loader (promised-datasource)
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
                                    {:current-user {:status :error
                                                    :error "404"
                                                    :prev nil
                                                    :params nil}
                                     :current-user-favorites {:status :error
                                                              :error "404"
                                                              :prev nil
                                                              :params nil}
                                     :jwt {:status
                                           :completed
                                           :params nil
                                           :error nil
                                           :prev {:value nil
                                                  :params nil
                                                  :status nil
                                                  :error nil}}}
                                    :jwt "JWT!"
                                    :user {:current nil}
                                    :favorites {:current nil}}}))
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
                                {:route {:data {:foo :bar}}
                                 :kv {:foo :bar
                                      :jwt "123"
                                      :keechma.toolbox.dataloader.core/dataloader
                                      {:jwt {:status :completed
                                             :params "123"
                                             :error nil
                                             :prev {:value nil
                                                    :params nil
                                                    :status nil
                                                    :error nil}}
                                       :foo {:status :completed
                                             :params :bar
                                             :error nil
                                             :prev {:value nil
                                                    :params nil
                                                    :status nil
                                                    :error nil}}}}}))
                         (swap! app-db-atom assoc-in [:route :data :foo] :baz)
                         (dataloader app-db-atom)))
                (p/map (fn []
                         (is (= @app-db-atom
                                {:route {:data {:foo :baz}}
                                 :kv {:foo :baz
                                      :jwt "123"
                                      :keechma.toolbox.dataloader.core/dataloader
                                      {:jwt {:status :completed
                                             :params "123"
                                             :error nil
                                             :prev {:value nil
                                                    :params nil
                                                    :status nil
                                                    :error nil}}
                                       :foo {:status :completed
                                             :params :baz
                                             :error nil
                                             :prev {:value :bar
                                                    :params :bar
                                                    :status :completed
                                                    :error nil}}}}}))
                         (done)))))))
