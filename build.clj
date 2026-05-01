(ns build
  "tools.build entry points for clj-ant.

  Common invocations:

      clj -T:build javac     ; compile the Java bridge once before dev
      clj -T:build lint
      clj -T:build check     ; lint, test, build jar
      clj -T:build clean
      clj -T:build jar
      clj -T:build install"
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'io.github.mbjarland/clj-ant)

(defn- tag-version [tag]
  (when (and tag (re-matches #"v\d+\.\d+\.\d+.*" tag))
    (subs tag 1)))

(defn- current-version []
  (or (not-empty (System/getenv "CLJ_ANT_VERSION"))
      (tag-version (System/getenv "GITHUB_REF_NAME"))
      (format "1.0.%s" (b/git-count-revs nil))))

(def version (current-version))
(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(def scm
  {:url "https://github.com/mbjarland/clj-ant"
   :connection "scm:git:git://github.com/mbjarland/clj-ant.git"
   :developerConnection "scm:git:ssh://git@github.com/mbjarland/clj-ant.git"
   :tag (or (System/getenv "GITHUB_REF_NAME") "HEAD")})

(def pom-data
  [[:description "Apache Ant's task ecosystem, fluent from Clojure."]
   [:url "https://github.com/mbjarland/clj-ant"]
   [:licenses
    [:license
     [:name "Eclipse Public License 1.0"]
     [:url "https://www.eclipse.org/legal/epl-v10.html"]
     [:distribution "repo"]]]
   [:developers
    [:developer
     [:id "mbjarland"]
     [:name "Morten Bjarland"]
     [:url "https://github.com/mbjarland"]]]])

(defn clean [_]
  (b/delete {:path "target"}))

(defn lint
  "Run static linting with clj-kondo. Warnings fail the build."
  [_]
  (b/process {:command-args ["clojure" "-M:lint"]})
  (println "> lint passed"))

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
                :src-dirs  ["src/clj" "src/java"]
                :src-pom   :none
                :scm       scm
                :pom-data  pom-data})
  (b/copy-dir {:src-dirs   ["src/clj"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file  jar-file})
  (println "> jar created at" jar-file))

(defn check
  "Run the full local verification pipeline: lint, tests, and jar build."
  [_]
  (lint nil)
  (b/process {:command-args ["clojure" "-M:test"]})
  (jar nil))

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
