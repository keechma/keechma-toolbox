(ns keechma.toolbox.ui-test
  (:require [cljs.test :refer-macros [deftest testing is async]]
            [keechma.toolbox.ui :refer-macros [<mcmd <mredirect]]))

(deftest mcmd
  (let [foo "bar"
        app-db (atom {})]
    (is (= (<mcmd {:foo :bar :app-db app-db} [:baz :quux] % %1 1 2 3 foo %&)
           (<mcmd {:foo :bar :app-db app-db} [:baz :quux] % %1 1 2 3 foo %&)))
    (is (not= (<mcmd {:foo :bar :app-db app-db} [:baz :quux] % %1 1 2 3 foo %&)
              (<mcmd {:foo :bar :app-db app-db} [:baz :quux] % %2 1 2 3 foo %&)))))

(deftest mredirect
  (let [bar "baz"
        baz "qux"
        app-db (atom {})]
    (is (= (<mredirect {:app-db app-db} {:foo bar})
           (<mredirect {:app-db app-db} {:foo bar})))
    (is (not= (<mredirect {:app-db app-db} {:foo bar})
              (<mredirect {:app-db app-db} {:bar baz})))))
