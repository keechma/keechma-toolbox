(ns keechma-toolbox.runner
    (:require [doo.runner :refer-macros [doo-tests]]
              [keechma-toolbox.core-test]))

(doo-tests 'keechma-toolbox.core-test)
