(ns keechma.toolbox.runner
    (:require [doo.runner :refer-macros [doo-tests]]
              [keechma.toolbox.core-test]
              [keechma.toolbox.dataloader.core-test]))

(doo-tests 'keechma.toolbox.core-test
           'keechma.toolbox.dataloader.core-test)
