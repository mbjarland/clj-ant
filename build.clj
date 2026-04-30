(ns build
  "tools.build entry points for clj-ant.

  Common invocations:

      clj -T:build clean
      clj -T:build jar
      clj -T:build install"
  (:require [clojure.tools.build.api :as b]))

(def lib 'io.github.mbjarland/clj-ant)
(def version (format "1.0.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (clean nil)
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     @basis
                :src-dirs  ["src/clj"]})
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
