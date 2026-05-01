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

(deftest manual-info-follows-redirect-pages
  (testing "short redirect pages resolve to their replacement manual page"
    (let [info (manual-info "gzip")]
      (is (re-find #"Packs a resource" (:description info)))
      (is (re-find #"file to gzip"
                   (get-in info [:attrs :src :description]))))))

(deftest manual-info-uses-overview-aliases
  (testing "task overview aliases resolve tags without matching HTML files"
    (let [info (manual-info "execon")]
      (is (re-find #"Executes a system command" (:description info)))
      (is (re-find #"command to execute"
                   (get-in info [:attrs :executable :description]))))))

(deftest manual-info-extracts-grouped-task-sections
  (testing "multi-task manual pages expose the section for the requested tag"
    (let [info (manual-info "cccheckout")]
      (is (re-find #"cleartool checkout" (:description info)))
      (is (= "Specifies whether to check out the file as reserved or not"
             (get-in info [:attrs :reserved :description])))
      (is (= "Yes" (get-in info [:attrs :reserved :required]))))))

(deftest manual-info-uses-known-manual-aliases
  (testing "legacy task names can point at differently named manual pages"
    (let [info (manual-info "blgenclient")]
      (is (re-find #"Borland Application Server" (:description info)))
      (is (re-find #"ejbclient.jar"
                   (get-in info [:attrs :clientjar :description])))
      (is (not (re-find #"&rArr;"
                        (get-in info [:attrs :clientjar :description])))))))
