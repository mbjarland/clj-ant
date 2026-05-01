(ns build
  "tools.build entry points for clj-ant.

  Common invocations:

      clj -T:build javac     ; compile the Java bridge once before dev
      clj -T:build lint
      clj -T:build check     ; lint, test, build jar
      clj -T:build clean
      clj -T:build jar
      clj -T:build pod-artifacts
      clj -T:build install"
  (:require [clojure.java.io :as io]
            [clojure.tools.build.api :as b]
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
(def pod-class-dir "target/pod-classes")
(def pod-artifact-dir "target/pod-registry")
(def pod-work-dir (str pod-artifact-dir "/work"))
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def pod-uber-file
  (format "%s/pod-clj-ant-%s-standalone.jar" pod-artifact-dir version))

(def scm
  {:url "https://github.com/mbjarland/clj-ant"
   :connection "scm:git:https://github.com/mbjarland/clj-ant.git"
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
      [:name "Matias Bjarland"]
      [:url "https://github.com/mbjarland"]]]])

(defn- java8-runtime? []
  (= "1.8" (System/getProperty "java.specification.version")))

(defn- javac-opts []
  (if (java8-runtime?)
    ["-source" "1.8" "-target" "1.8"]
    ["--release" "8"]))

(defn- process!
  "Run a subprocess and fail the build when it exits non-zero."
  [command-args]
  (let [{:keys [exit] :as result} (b/process {:command-args command-args})]
    (when-not (zero? exit)
      (throw (ex-info (str "Command failed: " command-args) result)))
    result))

(defn clean [_]
  (b/delete {:path "target"}))

(defn lint
  "Run static linting with clj-kondo. Warnings fail the build."
  [_]
  (process! ["clojure" "-M:lint"])
  (println "> lint passed"))

(defn javac
  "Compile src/java/** into target/classes. Idempotent. Required once
  before tests/REPL, and re-run if you edit any of the Java sources."
  [_]
  (b/javac {:src-dirs   ["src/java"]
            :class-dir  class-dir
            :basis      @basis
            ;; Build on JDK 8 itself as well as newer JDKs while keeping
            ;; the compiled bridge usable on the supported JDK floor.
            :javac-opts (javac-opts)})
  (println "> compiled src/java -> target/classes"))

(defn- posix-pod-launcher []
  (str "#!/usr/bin/env sh\n"
       "set -eu\n"
       "DIR=\"$(cd \"$(dirname \"$0\")\" && pwd)\"\n"
       "exec java ${JAVA_OPTS:-} -cp \"$DIR/pod-clj-ant.jar\" "
       "clojure.main -m clj-ant.pod \"$@\"\n"))

(defn- windows-pod-launcher []
  (str "@echo off\r\n"
       "setlocal\r\n"
       "set DIR=%~dp0\r\n"
       "java %JAVA_OPTS% -cp \"%DIR%pod-clj-ant.jar\" "
       "clojure.main -m clj-ant.pod %*\r\n"))

(defn- write-launcher! [path content executable?]
  (io/make-parents path)
  (spit path content)
  (when executable?
    (.setExecutable (io/file path) true false))
  path)

(defn- build-pod-uber! []
  (b/delete {:path pod-class-dir})
  (io/make-parents pod-uber-file)
  (b/javac {:src-dirs   ["src/java"]
            :class-dir  pod-class-dir
            :basis      @basis
            :javac-opts (javac-opts)})
  (b/copy-dir {:src-dirs   ["src/clj"]
               :target-dir pod-class-dir})
  (b/uber {:class-dir pod-class-dir
           :uber-file pod-uber-file
           :basis     @basis
           :manifest  {"Implementation-Title" "pod-clj-ant"
                       "Implementation-Version" version}})
  (println "> standalone pod jar created at" pod-uber-file))

(defn- stage-pod-package! [{:keys [classifier executable launcher]}]
  (let [package-name (format "pod-clj-ant-%s-%s" version classifier)
        package-dir  (str pod-work-dir "/" package-name)
        jar-path     (str package-dir "/pod-clj-ant.jar")
        launcher-path (str package-dir "/" executable)
        zip-file     (format "%s/%s.zip" pod-artifact-dir package-name)]
    (b/delete {:path package-dir})
    (b/delete {:path zip-file})
    (io/make-parents jar-path)
    (io/copy (io/file pod-uber-file) (io/file jar-path))
    (write-launcher! launcher-path launcher (= executable "pod-clj-ant"))
    (b/zip {:src-dirs [package-dir]
            :zip-file zip-file})
    zip-file))

(defn pod-artifacts
  "Build babashka pod-registry artifacts.

  Produces a standalone JVM pod jar plus zip files containing executable
  launchers. The Unix artifact can be used for Linux and macOS on any JVM
  architecture; the Windows artifact contains a .bat launcher. Set
  CLJ_ANT_VERSION to force the release version in artifact names."
  [_]
  (b/delete {:path pod-artifact-dir})
  (build-pod-uber!)
  (let [zips [(stage-pod-package! {:classifier "jvm-unix"
                                   :executable "pod-clj-ant"
                                   :launcher (posix-pod-launcher)})
              (stage-pod-package! {:classifier "jvm-windows"
                                   :executable "pod-clj-ant.bat"
                                   :launcher (windows-pod-launcher)})]]
    (println "> pod registry zip artifacts:")
    (doseq [zip-file zips]
      (println " " zip-file))
    zips))

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
  (process! ["clojure" "-M:test"])
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
