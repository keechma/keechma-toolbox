(ns keechma.toolbox.runner
    (:require [doo.runner :refer-macros [doo-tests]]
              [keechma.toolbox.dataloader-test]
              [keechma.toolbox.pipeline-test]
              [keechma.toolbox.css-test]
              [keechma.toolbox.forms-test]
              [keechma.toolbox.ui-test]))

(doo-tests 'keechma.toolbox.dataloader-test
           'keechma.toolbox.pipeline-test
           'keechma.toolbox.css-test
           'keechma.toolbox.forms-test
           'keechma.toolbox.ui-test
           )
