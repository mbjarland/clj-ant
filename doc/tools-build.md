# clj-ant + tools.build

There's no friction between the two — they compose at the function-
call level. Pick whichever is the better tool for each step:

- **tools.build** wins for Clojure-native ops (`b/javac`, `b/jar`,
  `b/copy-dir`, `b/write-pom`, `b/install`, `b/uber`).
- **clj-ant** wins for the long tail (signing, deployment, archive
  surgery, SSH, XSLT, replaceregexp, filterchain, parallel,
  apply+mapper).

This doc shows the two directions of integration.


## tools.build calling clj-ant

It's just a function call. Inside any tools.build defn, require
clj-ant and run an element tree:

```clojure
(ns build
  (:require [clojure.tools.build.api :as b]
            [clj-ant.core  :as a]
            [clj-ant.tasks :as t]))

(def lib 'me.alice/myapp)
(def version "1.0.42")
(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn ci [_]
  (b/delete {:path "target"})
  (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
  (b/compile-clj {:basis     @basis
                  :src-dirs  ["src"]
                  :class-dir class-dir})
  (b/jar       {:class-dir class-dir :jar-file jar-file})

  ;; Hand off to clj-ant for the parts tools.build doesn't cover.
  ;; a/ant returns a result map; throw explicitly if Ant failed.
  (let [{:keys [error] :as result}
        (a/ant
          (t/scp     :file jar-file
                     :todir "deploy@web-1:/srv/app/"
                     :keyfile (str (System/getenv "HOME") "/.ssh/id_ed25519")
                     :trust "true")
          (t/sshexec :host "web-1" :username "deploy"
                     :keyfile (str (System/getenv "HOME") "/.ssh/id_ed25519")
                     :trust "true"
                     :command "systemctl --user restart app"))]
    (when error (throw error))
    result))
```

Run with `clj -T:build ci`. The clj-ant call is just another function
call; inspect the result map or throw `:error` to make failures fail the
tools.build task.


## clj-ant calling tools.build

The reverse direction is `(a/task tag f)`: inline an arbitrary
Clojure thunk as an Ant task. The thunk runs in target order with
full event-stream participation, so it shows up as
`:task-started`/`:task-finished` in `:on-event` like any other
task. Put it inside a target and `<antcall>` can invoke that target;
`<parallel>` and target dependency resolution work as they do for any
other Ant task.

```clojure
(ns deploy
  (:require [clojure.tools.build.api :as b]
            [clj-ant.core  :as a]
            [clj-ant.tasks :as t]))

(def class-dir "target/classes")
(def jar-file  "target/app.jar")
(def basis     (delay (b/create-basis {:project "deps.edn"})))

(defn -main [& _]
  (a/ant
    :on-event (fn [{:keys [phase task]}]
                (when (= phase :task-started)
                  (println "  >>" task)))

    (a/task :clean   #(b/delete {:path "target"}))

    (a/task :compile #(b/compile-clj {:basis     @basis
                                       :src-dirs  ["src"]
                                       :class-dir class-dir}))

    (a/task :jar     #(b/jar {:class-dir class-dir
                              :jar-file  jar-file}))

    ;; Now mix Ant tasks with the tools.build steps:
    (t/checksum :file jar-file :algorithm "SHA-256"
                :property "sum")
    (t/echo     :message "jar sha-256: ${sum}")

    (t/scp      :file jar-file
                :todir "deploy@web-1:/srv/"
                :keyfile (str (System/getenv "HOME") "/.ssh/id_ed25519")
                :trust "true")))
```

Why `task` rather than wrapping each `b/*` fn as a deftask: the
tools.build options are richer than Ant's string-attribute model
(vectors of paths, basis maps, etc.), so threading them through
Ant attributes would lose information. Inline thunks keep the
calling convention native and compose freely.


## Defining reusable named b-tasks

If you find yourself calling the same `b/*` operation in many
element trees, register it once with `deftask`:

```clojure
(a/deftask :tb/echo-version
  (fn [{:keys [project]}]
    (println "version:" (.getProperty project "app.version"))))

(a/ant
  (t/property :name "app.version" :value "1.2.3")
  (a/element :tb/echo-version))
```

Inside the `deftask` body you receive a map containing every
attribute as a keyword key (post-`${...}` expansion), plus
`:project`, `:task-name`, and `:text` if the element had a body.
Side effects, return values, and exceptions all behave as in any
other Clojure call — `BuildException` propagates, anything else
gets wrapped.


## Recipe: dependency-driven multi-step build

`deftarget` + `task` give you the full Ant-style target graph
without leaving Clojure-native operations:

```clojure
(a/deftarget clean
  (a/task :delete-target #(b/delete {:path "target"})))

(a/deftarget compile
  :depends [:clean]
  (a/task :compile #(b/compile-clj {:basis     @basis
                                     :src-dirs  ["src"]
                                     :class-dir class-dir})))

(a/deftarget jar
  :depends [:compile]
  (a/task :jar #(b/jar {:class-dir class-dir
                         :jar-file  jar-file})))

(a/deftarget deploy
  :depends [:jar]
  (t/scp :file jar-file :todir "..." :keyfile "..." :trust "true"))

(a/ant :targets ["deploy"] clean compile jar deploy)
;; clean -> compile -> jar -> deploy, in dependency order. Add
;; explicit <uptodate> properties plus target/task if/unless guards
;; when you want up-to-date targets to be skipped.
```

Note: `depends` is just Ant's classic depends-graph machinery —
clj-ant doesn't reimplement it. Targets fire once even if
multiple downstream targets depend on them.
