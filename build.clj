(ns build
  "tools.build entry points for clj-ant.

  Common invocations:

      clj -T:build javac     ; compile the Java bridge once before dev
      clj -T:build clean
      clj -T:build jar
      clj -T:build install"
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'io.github.mbjarland/clj-ant)
(def version (format "1.0.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn javac
  "Compile src/java/** into target/classes. Idempotent. Required once
  before tests/REPL, and re-run if you edit any of the Java sources."
  [_]
  (b/javac {:src-dirs   ["src/java"]
            :class-dir  class-dir
            :basis      @basis
            ;; --release 8 keeps the jar usable on JDK 8+ (Ant
            ;; itself supports JDK 8). Our bridge class uses
            ;; nothing newer, so there's no cost to staying low.
            :javac-opts ["--release" "8"]})
  (println "> compiled src/java -> target/classes"))

(defn jar [_]
  (clean nil)
  (javac nil)
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     @basis
                :src-dirs  ["src/clj" "src/java"]})
  (b/copy-dir {:src-dirs   ["src/clj"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file  jar-file})
  (println "> jar created at" jar-file))

(defn install [_]
  (jar nil)
  (b/install {:basis     @basis
              :lib       lib
              :version   version
              :jar-file  jar-file
              :class-dir class-dir})
  (println "> installed" lib version "to local maven repo"))

(defn deploy
  "Deploy the jar to Clojars. Requires CLOJARS_USERNAME and
  CLOJARS_PASSWORD env vars to be set to a deploy token (NOT your
  Clojars web password)."
  [_]
  (jar nil)
  (dd/deploy {:installer       :remote
              :sign-releases?  false
              :artifact        jar-file
              :pom-file        (b/pom-path {:lib lib :class-dir class-dir})})
  (println "> deployed" lib version "to clojars"))
