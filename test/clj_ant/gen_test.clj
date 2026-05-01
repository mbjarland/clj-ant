(ns clj-ant.gen-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-ant.gen :as gen])
  (:import [java.io File]))

(defn- manual-info [tag]
  (#'gen/read-manual-info (File. "ant/manual") tag))

(deftest manual-info-extracts-task-attribute-docs
  (testing "task pages expose prose plus attribute table metadata"
    (let [info (manual-info "copy")]
      (is (re-find #"Copies a file" (:description info)))
      (is (= "The file to copy."
             (get-in info [:attrs :file :description])))
      (is (re-find #"Yes" (get-in info [:attrs :file :required])))
      (is (re-find #"directory to copy to"
                   (get-in info [:attrs :todir :description])))
      (is (re-find #"With the .*file.* attribute"
                   (get-in info [:attrs :todir :required]))))))

(deftest manual-info-extracts-type-attribute-docs
  (testing "type pages without a Description heading still expose their prose"
    (let [info (manual-info "fileset")]
      (is (re-find #"FileSet is a group" (:description info)))
      (is (re-find #"root of the directory tree"
                   (get-in info [:attrs :dir :description])))
      (is (re-find #"Exactly one of dir or file"
                   (get-in info [:attrs :file :required]))))))
