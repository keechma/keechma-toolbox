(defproject keechma/toolbox "0.1.3"
  :description "Keechma Toolbox"
  :dependencies [[org.clojure/clojure "1.9.0-alpha17"]
                 [org.clojure/clojurescript "1.9.854"]
                 [reagent "0.6.0"]
                 [binaryage/devtools "0.8.2"]
                 [keechma "0.3.0" :exclusions [cljsjs/react-with-addons]]
                 [garden "1.3.2"]
                 [funcool/promesa "1.8.1"]
                 [keechma/forms "0.1.2"]
                 [medley "0.8.4"]
                 [cljs-ajax "0.5.8"]
                 [com.stuartsierra/dependency "0.2.0"]
                 [keechma/entitydb "0.1.0"]]

  :min-lein-version "2.5.3"

  :source-paths ["src/clj" "src/cljs"]


  :codox {:language :clojurescript
          :metadata {:doc/format :markdown}
          :namespaces [keechma.toolbox.dataloader.controller keechma.toolbox.pipeline.controller keechma.toolbox.pipeline.core keechma.toolbox.dataloader.subscriptions keechma.toolbox.ui]}

  :plugins [[lein-cljsbuild "1.1.7"]
            [lein-codox "0.9.3"]]

  :clean-targets ^{:protect false} ["resources/public/js/compiled"
                                    "target"
                                    "test/js"]

  :figwheel {:css-dirs ["resources/public/css"]}

  :profiles
  {:dev
   {:dependencies []

    :plugins      [[lein-figwheel "0.5.8"]
                   [lein-doo "0.1.7"]]}}


  :cljsbuild
  {:builds
   [{:id           "dev"
     :source-paths ["src/cljs"]
     :figwheel     {:on-jsload "keechma.toolbox.core/reload"}
     :compiler     {:main                 keechma.toolbox.core
                    :optimizations        :none
                    :output-to            "resources/public/js/compiled/app.js"
                    :output-dir           "resources/public/js/compiled/dev"
                    :asset-path           "js/compiled/dev"
                    :source-map-timestamp true}}

    {:id           "min"
     :source-paths ["src/cljs"]
     :compiler     {:main            keechma.toolbox.core
                    :optimizations   :advanced
                    :output-to       "resources/public/js/compiled/app.js"
                    :output-dir      "resources/public/js/compiled/min"
                    :closure-defines {goog.DEBUG false}
                    :pretty-print    false}}

    {:id           "test"
     :source-paths ["src/cljs" "test/cljs"]
     :compiler     {:output-to     "resources/public/js/compiled/test.js"
                    :output-dir    "resources/public/js/compiled/test"
                    :main          keechma.toolbox.runner
                    :optimizations :none}}]})
