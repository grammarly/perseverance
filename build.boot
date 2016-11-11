(set-env!
 :repositories [["central" "http://repo1.maven.org/maven2"]
                ["clojars" "https://repo.clojars.org/"]]
 :dependencies '[;; Development
                 [boot/core "2.6.0" :scope "provided"]
                 [adzerk/boot-test "1.1.2" :scope "test"]]
 :source-paths #{"src/"}
 :test-paths #{"test/"}
 :target "target/")

(task-options!
 pom  {:project     'com.grammarly/perseverance
       :version     "0.1.0-SNAPSHOT"
       :description "Flexible retries library for Clojure."
       :license     {:name "Apache License, Version 2.0"
                     :url "http://www.apache.org/licenses/LICENSE-2.0"}
       :url         "https://github.com/grammarly/perseverance"
       :scm         {:url "https://github.com/grammarly/perseverance"}})

(ns-unmap 'boot.user 'test)
(deftask test
  "Run unit tests."
  []
  (set-env! :source-paths #(into % (get-env :test-paths)))
  (require 'adzerk.boot-test)
  (eval `(adzerk.boot-test/test)))
