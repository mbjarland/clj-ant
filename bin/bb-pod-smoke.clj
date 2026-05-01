#!/usr/bin/env bb

(ns bb-pod-smoke
  (:require [babashka.pods :as pods]))

(pods/load-pod ["clojure" "-M:pod"])

(require '[clj-ant.pod :as a]
         '[clj-ant.tasks :as t])

(defn- fail! [message data]
  (binding [*out* *err*]
    (println message)
    (prn data))
  (System/exit 1))

(let [base (doto (java.io.File/createTempFile "cljant-bb-pod-smoke" "")
             .delete
             .mkdirs)
      source (doto (java.io.File. base "source.txt")
               (spit "source"))
      mkdir-target (str (java.io.File. base "created"))
      events (atom [])
      desc (a/describe :copy)
      issue (first (a/lint (t/copy :tdoir "out")))
      files (a/files (t/fileset :dir (str base) :includes "*.txt"))
      result (a/execute-stream [(t/mkdir :dir mkdir-target)]
                               #(swap! events conj %)
                               :level :warn
                               :capture? true)
      summary {:todir-type (get-in desc [:attrs "todir" :type])
               :lint-suggestion (get-in issue [:suggestions :tdoir])
               :files files
               :created? (.isDirectory (java.io.File. mkdir-target))
               :streamed-task? (boolean (some #(= :task-started (:phase %))
                                              @events))
               :error (:error result)}]
  (if (and (= "java.io.File" (:todir-type summary))
           (= [:todir] (:lint-suggestion summary))
           (= [(.getAbsolutePath source)] (:files summary))
           (:created? summary)
           (:streamed-task? summary)
           (nil? (:error summary)))
    (println "bb pod smoke ok")
    (fail! "bb pod smoke failed" summary)))
