(defproject keechma.toolbox "0.0.1-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.9.0-alpha14"]
                 [org.clojure/clojurescript "1.9.229"]
                 [reagent "0.6.0"]
                 [binaryage/devtools "0.8.2"]
                 [keechma "0.2.0-SNAPSHOT-4" :exclusions [cljsjs/react-with-addons]]
                 [devcards "0.2.2" :exclusions [cljsjs/react]]
                 [garden "1.3.2"]
                 [funcool/promesa "1.6.0"]
                 [keechma/forms "0.1.2"]
                 [medley "0.8.4"]
                 [cljs-ajax "0.5.8"]]

  :min-lein-version "2.5.3"

  :source-paths ["src/clj" "src/cljs"]

  :plugins [[lein-cljsbuild "1.1.4"]]

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

    {:id           "devcards"
     :source-paths ["src/devcards" "src/cljs"]
     :figwheel     {:devcards true}
     :compiler     {:main                 "keechma.toolbox.core-card"
                    :optimizations        :none
                    :output-to            "resources/public/js/compiled/devcards.js"
                    :output-dir           "resources/public/js/compiled/devcards"
                    :asset-path           "js/compiled/devcards"
                    :source-map-timestamp true}}

    {:id           "hostedcards"
     :source-paths ["src/devcards" "src/cljs"]
     :compiler     {:main          "keechma.toolbox.core-card"
                    :optimizations :advanced
                    :devcards      true
                    :output-to     "resources/public/js/compiled/devcards.js"
                    :output-dir    "resources/public/js/compiled/hostedcards"}}

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
