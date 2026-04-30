(ns clj-ant.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clj-ant.core :as a])
  (:import [java.io File]
           [java.nio.file Files]))

(defn- tmp-dir []
  (let [d (Files/createTempDirectory "cljant-test" (make-array java.nio.file.attribute.FileAttribute 0))]
    (.toFile d)))

(defn- spit-file [^File parent ^String name content]
  (let [f (File. parent name)]
    (io/make-parents f)
    (spit f content)
    f))

(deftest node-construction
  (testing "node returns a record with expected shape"
    (let [n (a/node :copy :todir "out"
                    (a/node :fileset :dir "src" :includes "**/*.clj"))]
      (is (a/node? n))
      (is (= :copy (:tag n)))
      (is (= {:todir "out"} (:attrs n)))
      (is (= 1 (count (:children n))))
      (is (= :fileset (-> n :children first :tag))))))

(deftest copy-and-property-expansion
  (testing "property expansion + nested fileset is honoured by Ant"
    (let [base (tmp-dir)
          src  (File. base "src")
          dst  (File. base "dst")]
      (.mkdirs src)
      (spit-file src "a.txt" "alpha")
      (spit-file src "b.txt" "beta")
      (let [r (a/ant
                :basedir (.getAbsolutePath base)
                :level :warn
                :capture? true
                (a/node :property :name "dst" :value (.getAbsolutePath dst))
                (a/node :copy :todir "${dst}"
                        (a/node :fileset
                                :dir (.getAbsolutePath src)
                                :includes "**/*.txt")))]
        (is (nil? (:error r)))
        (is (= 2 (count (.listFiles dst))))
        (is (some #(= :task-finished (:phase %)) (:events r)))))))

(deftest macrodef-via-runtime
  (testing "macrodef works because RuntimeConfigurable handles expansion"
    (let [base (tmp-dir)
          out  (File. base "out.txt")
          r (a/ant
              :basedir (.getAbsolutePath base)
              :level :warn
              :capture? true
              (a/node :macrodef :name "shout"
                      (a/node :attribute :name "what")
                      (a/node :sequential
                              (a/node :echo :message "@{what}!")))
              (a/node :shout :what "hello"))]
      (is (nil? (:error r)))
      (is (some #(and (= :message (:phase %))
                      (= "hello!" (:message %)))
                (:events r))))))
