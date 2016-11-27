(ns keechma-toolbox.core-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [keechma-toolbox.core :as core]))

(deftest fake-test
  (testing "fake description"
    (is (= 1 2))))
