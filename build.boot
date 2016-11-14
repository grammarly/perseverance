(set-env!
 :repositories [["central" "http://repo1.maven.org/maven2"]
                ["clojars" "https://clojars.org/repo"]]
 :dependencies '[[org.clojure/clojure "1.6.0" :scope "provided"]
                 [boot/core "2.6.0" :scope "provided"]]
 :source-paths #{"src/"}
 :test-paths #{"test/"}
 :target "target/")

(task-options!
 pom  {:project     'com.grammarly/perseverance
       :version     "0.1.0-SNAPSHOT"
       :description "Flexible retries library for Clojure."
       :license     {"Apache License, Version 2.0"
                     "http://www.apache.org/licenses/LICENSE-2.0"}
       :url         "https://github.com/grammarly/perseverance"
       :scm         {:url "https://github.com/grammarly/perseverance"}})

(ns-unmap 'boot.user 'test)
(deftask test
  "Run unit tests."
  [v clojure-version VERSION str "Clojure version to run tests with. Must match BOOT_CLOJURE_VERSION."]
  (set-env! :source-paths #(into % (get-env :test-paths))
            :dependencies
            (fn [deps]
              (->> deps
                   (cons '[adzerk/boot-test "1.1.2" :scope "test"])
                   ;; Replace Clojure version with the provided.
                   (remove #(= (first %) 'org.clojure/clojure))
                   (cons ['org.clojure/clojure (or clojure-version "1.6.0")]))))
  (require 'adzerk.boot-test)
  ((resolve 'adzerk.boot-test/test)))

(deftask deploy
  "Build and deploy the library to Clojars."
  []
  (set-env! :resource-paths (get-env :source-paths))
  (comp (pom)
        (jar)
        (push :repo "clojars")))
