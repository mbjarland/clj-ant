# clj-ant

Apache Ant's task ecosystem, fluent from Clojure. Tasks are functions;
resource collections (`fileset`, `path`, `dirset`, `union`, `restrict`,
...) are data until you run them, and can also be realized as lazy
`java.io.File` / Ant `Resource` sequences via `a/files` and
`a/resources`.

[![CI](https://github.com/mbjarland/clj-ant/actions/workflows/ci.yml/badge.svg)](https://github.com/mbjarland/clj-ant/actions/workflows/ci.yml)
[![Clojars Project](https://img.shields.io/clojars/v/io.github.mbjarland/clj-ant.svg?include_prereleases)](https://clojars.org/io.github.mbjarland/clj-ant)
[![cljdoc](https://cljdoc.org/badge/io.github.mbjarland/clj-ant)](https://cljdoc.org/d/io.github.mbjarland/clj-ant)
[![License](https://img.shields.io/badge/license-EPL%201.0-blue.svg)](LICENSE)

> **Pre-1.0 release candidate.** Architecture and primary APIs are expected
> to be stable; remaining changes before `1.0.0` should be bug fixes,
> documentation polish, or feedback from release-candidate users.

```clojure
(require '[clj-ant.core  :as a]
         '[clj-ant.tasks :as t]
         '[clojure.string :as str])

;; Build a distributable site from normal files plus selected entries
;; inside zip/jar files. No shelling out, no temporary unzip step.
(let [version "1.2.3"
      out     "target/site"
      docs    (->> (a/files (t/fileset :dir "doc" :includes "**/*.md"))
                   (remove #(str/includes? (.getName %) "draft")))]
  (a/ant
    (t/delete :dir out)
    (t/mkdir :dir out)

    ;; Clojure decides which files matter; Ant does the copying.
    (t/copy :todir (str out "/docs") docs)

    ;; Copy only built browser assets from a vendor zip.
    (t/copy :todir (str out "/assets")
      (t/zipfileset :src "vendor/admin-ui.zip"
                    :includes "dist/**"
                    :prefix "admin"))

    ;; Pull legal metadata out of the app jar the same way.
    (t/copy :todir (str out "/licenses")
      (t/zipfileset :src (str "target/app-" version ".jar")
                    :includes "META-INF/LICENSE*,META-INF/NOTICE*"))

    ;; Stream token replacement while copying config templates.
    (t/copy :todir (str out "/conf")
      (t/fileset :dir "etc" :includes "**/*.tmpl")
      (t/filterchain
        (t/tokenfilter
          (t/replacestring :from "@VERSION@" :to version))))

    (t/zip :destfile (str "target/site-" version ".zip")
      (t/fileset :dir out))))
```

That single expression weaves together things vanilla Clojure rarely
composes cleanly: Ant's pattern grammar, lazy resource collections,
Clojure filtering, streaming text transforms, archive-entry copying,
and zip creation. No XML, no shelling out to `zip` / `unzip`, no
temporary extraction just to grab files from a jar.


## Why

Clojure has excellent build tooling for Clojure code (`tools.build`,
`tools.deps`, `bb`). But the long tail of real-world build operations
— sign a jar, scp it somewhere, restart a remote service, replace
tokens in a config file, audit zip entries, run a command per file
— is exactly what Apache Ant has been good at for 20 years.

clj-ant wraps Ant's built-in tasks, data types, and nested elements as
Clojure functions that return data, with full Ant semantics underneath:
property expansion, `refid`, `macrodef`, target dependency resolution,
custom taskdefs. Compose them with the rest of your Clojure code freely.


## Install

```clojure
;; deps.edn
{:deps {io.github.mbjarland/clj-ant {:mvn/version "1.0.0-rc.1"}}}
```

Replace the version with the latest one shown by the Clojars badge
above when upgrading.

Requires JDK 8+. Released jars include the compiled Java bridge
class — downstream consumers do **not** need to run `javac`.

For babashka:
```clojure
(require '[babashka.pods :as pods])
(pods/load-pod ["clojure" "-M:pod"])
(require '[clj-ant.pod :as a] '[clj-ant.tasks :as t])
```


## Documentation

| Doc | Read it for |
|-----|-------------|
| **[doc/intro.md](doc/intro.md)** | Where to start. |
| **[doc/examples.md](doc/examples.md)** | Recipe cookbook (templating, bulk find-replace, smart copy, archive surgery, parallel pipelines, SSH, watch mode, …). |
| **[doc/architecture.md](doc/architecture.md)** | Design rationale, layer model, anti-patterns. For contributors. |
| **[doc/babashka.md](doc/babashka.md)** | The bb pod story. |
| **[doc/tools-build.md](doc/tools-build.md)** | Interop with `clojure.tools.build`. |
| **[doc/roadmap.md](doc/roadmap.md)** | What's done, what's planned. |
| **[doc/pre-release.md](doc/pre-release.md)** | Release checklist for alpha and 1.0. |

For the underlying Ant tasks themselves — what each one does, what
attributes they take, what nested elements they accept — the
canonical reference is:

**[Apache Ant Tasks Overview](https://ant.apache.org/manual/tasksoverview.html)**

Generated wrappers include manual-derived docstrings, and wrappers with
known Ant manual pages include direct links. Browse Ant's overview for
tasks not yet covered in the cookbook.


## Highlights

### Data-first

Every generated wrapper returns an `Element` record, which behaves like
a Clojure map. Nothing executes until the tree is handed to `a/ant`:

```clojure
(t/copy :todir "out"
  (t/fileset :dir "src" :includes "**/*.clj"))
;; => {:tag :copy :attrs {:todir "out"}
;;     :children [{:tag :fileset :attrs {:dir "src" ...} ...}]}
```

So you can `update`, `walk`, `assoc` plans before running them.

### Quiet by default

Ant's `DefaultLogger` output is captured into the result map instead of
printed to the console:

```clojure
(let [{:keys [out err]} (a/ant (t/echo :message "hello"))]
  out)
;; => "     [echo] hello\n\nBUILD SUCCESSFUL\nTotal time: ...\n"
```

Use `:log :inherit` for old-style console logging and `:log :quiet` to
discard logger output entirely. Use `:log {:console? true :capture? true :level :info}`
to tee logger output while retaining `:out`/`:err`.

### Resource collections as Clojure sequences

```clojure
(->> (t/fileset :dir "src" :includes "**/*.clj")
     a/files                              ; lazy seq of java.io.File
     (filter #(> (.lastModified %) cutoff))
     (mapv  #(.getName %)))
```

Anything that's an Ant `ResourceCollection` (fileset, filelist,
path, dirset, restrict, intersect, union, …) flows out via
`a/files` and `a/resources`. Round-trip back into a `(t/copy ...)`
without any string-join glue:

```clojure
(let [recent (->> (a/files (t/fileset :dir "src"))
                  (filter #(> (.lastModified %) cutoff)))]
  (a/ant (t/copy :todir "out" recent)))
```

### Sessions for tight loops

Many small calls in a REPL loop? Reuse one Ant Project:

```clojure
(a/with-session [s {:level :info}]
  (a/ant (t/property :name "v" :value "1.2.3"))
  (a/ant (t/echo :message "v=${v}")))
```

50-call benchmark on a 2-task plan: `~575 ms` fresh → `~23 ms`
sessioned → `~14 ms` with `(a/prepare ...)`.


### Async + cancellation

For long-running builds, `execute-async!` returns a `Run` that
behaves like a `promise`/`future`:

```clojure
(let [run (a/execute-async! [(t/scp :file "big.tar"
                                     :todir "deploy@host:/srv/"
                                     :keyfile "..." :trust "true")]
                            :on-event #(println (:phase %)))]
  ;; ...do other work...
  (when (slow?) (a/cancel! run))    ; interrupts the build thread
  @run)                             ; blocks for the result
```

IO tasks (`<scp>`, `<get>`, `<sshexec>`) honour the interrupt
cleanly. Pure-CPU tasks (`<javac>`, large `<copy>`) often don't
observe it, so `:cancelled? true` lands on the result map either
way to reflect caller intent.


### Watch mode

The "edit, save, see rebuild" loop bb developers expect, for any
clj-ant pipeline:

```clojure
(def stop (a/watch [(t/javac :srcdir "src" :destdir "out")
                    (t/copy  :todir "deploy" (t/fileset :dir "out"))]
                   :paths   ["src"]
                   :poll-ms 300
                   :session (a/session {:level :warn})))
;; ...edit src/...
(stop)
```

Polling-based, so it works the same on Linux, macOS, and Windows.
Pair with `:session` for the cheapest re-runs.


### Errors carry the element tree

When Ant raises a `BuildException` four levels into nested
elements, the error coming back is `ex-info` you can pattern-
match in code rather than a stringly-typed mystery:

```clojure
(let [r (a/ant (t/copy :tdoir "/tmp"))]   ; typo: tdoir
  (when-let [err (:error r)]
    (let [{:keys [clj-ant/elements ant/message]} (ex-data err)]
      (println message "in" (pr-str elements)))))
;; copy doesn't support the "tdoir" attribute in [#Element{...}]
```


### Read existing `build.xml`

```clojure
(require '[babashka.fs :as fs])

(a/ant (a/from-xml "build.xml"))                ; default target
(a/ant :targets ["jar"] (a/from-xml "build.xml"))   ; pick one

;; static analysis over a corpus
(for [^File f (fs/glob "." "**/build.xml")
      hit (a/elements (a/from-xml f)
                       #(and (= :scp (:tag %))
                             (= "true" (-> % :attrs :trust))))]
  {:file (str f) :scp-target (-> hit :attrs :file)})

;; rewrite and re-emit compact XML
(-> (a/from-xml "build.xml")
    (a/transform #(cond-> %
                    (= :copy (:tag %))
                    (assoc-in [:attrs :preservelastmodified] "true")))
    a/to-xml)
```

`to-xml` re-emits data-only element trees. Trees containing real Java
Ant objects, such as direct `FileSet`s or lazy file seq children, should
be run directly because they cannot be represented faithfully as XML.

### Clojure functions as first-class Ant tasks

```clojure
(a/deftask :slack-notify
  (fn [{:keys [channel msg webhook]}]
    (slack/post webhook channel msg)))

(a/ant
  (t/jar :destfile "app.jar" ...)
  (a/element :slack-notify :webhook url
             :channel "#deploys"
             :msg "shipped ${version}"))   ; ${version} expanded
```

The Clojure fn participates fully: build-listener events, macrodef
parameter expansion, and target execution. Put it inside a target and
`<antcall>` can invoke that target like any other Ant target.

### Validation (malli) and rich REPL

```clojure
(a/lint (t/copy :tdoir "out"))
;; => [{:tag :copy
;;      :path []
;;      :errors {:tdoir ["disallowed key"]}
;;      :suggestions {:tdoir [:todir]}}]

(a/ant :validate? true (t/copy :tdoir "out"))
;; ExceptionInfo: Validation failed: 1 issue(s)

(a/describe :copy)
;; => {:tag :copy
;;     :description "Copies a file or resource collection ..."
;;     :manual-url "https://ant.apache.org/manual/Tasks/copy.html"
;;     :attrs {"todir" {:type java.io.File
;;                       :description "The directory to copy to."
;;                       :required "..."}
;;             ...}
;;     ...}

(a/lint (t/mkdir))
;; => [{:tag :mkdir
;;      :path []
;;      :errors {:dir ["missing required attribute (Required: Yes)"]}}]

user=> (doc t/copy)
clj-ant.tasks/copy
([& args])
  Copies a file or resource collection ...
  **Attributes**
  | Attribute | Type | Description | Required |
  | `:todir` | `File` | The directory to copy to. | ... |
  ...
  https://ant.apache.org/manual/Tasks/copy.html
```

Generated `t/*` vars keep compact `([& args])` signatures so cljdoc
doesn't bury the useful prose under huge parameter lists. Attribute
names, Java types, manual prose, required markers, nested elements, and
backing classes are still available in generated docstrings, var
metadata, and `a/describe`.

`clojure.repl/doc` is still the right quick human-facing REPL view for
`t/*` vars. `a/describe` complements it by returning EDN for tools, UIs,
bb pod callers, and validation.

### SSH out of the box

`<scp>` and `<sshexec>` ship with the Terrapin-fixed `com.github.mwiede`
JSch fork (CVE-2023-48795 patched). The everyday "deploy and restart"
chain is one expression; see [doc/examples.md](doc/examples.md) for the
SSH recipes.

### Babashka pod

```clojure
(require '[babashka.pods :as pods])
(pods/load-pod ["clojure" "-M:pod"])
(require '[clj-ant.pod   :as a]
         '[clj-ant.tasks :as t])

(a/with-session [s]
  (a/execute-in s [(t/get   :src url :dest "/tmp/v.zip")
                   (t/unzip :src "/tmp/v.zip" :dest "/opt/v")]))
```

Streaming events, files, sessions — same surface as the JVM API.
See [doc/babashka.md](doc/babashka.md).


## Getting started for contributors

```sh
git clone https://github.com/mbjarland/clj-ant.git
cd clj-ant
clj -T:build javac     # one-time, compiles the Java bridge
clj -T:build lint      # static linting, warnings fail
clj -M:test            # run the test suite
```

Released jars include the pre-compiled bridge — only contributors
need the `javac` step.

### Project layout

```
src/clj/clj_ant/core.clj    the runner + sessions + execute! + ant
src/clj/clj_ant/spec.clj    malli schemas from IntrospectionHelper
src/clj/clj_ant/tasks.clj   auto-generated wrappers (one per Ant
                            task, type, and nested element)
src/clj/clj_ant/pod.clj     babashka pod
src/gen/clj_ant/gen.clj     the generator (clj -X:gen)
src/java/cljant/            the single Java bridge class
test/clj_ant/core_test.clj
doc/                        examples / architecture / babashka / etc.
build.clj                   tools.build entry points
deps.edn
```

### Aliases

```sh
clj -T:build javac          # compile src/java/** -> target/classes
clj -T:build lint           # run clj-kondo, warnings fail
clj -T:build check          # lint, test, and build the jar
clj -T:build jar            # build the jar
clj -T:build install        # install to local maven repo
clj -T:build deploy         # deploy to clojars (needs CLOJARS_*)
clj -X:gen                  # regenerate clj-ant.tasks from Ant
clj -M:test                 # run tests via kaocha
clj -M:pod                  # bb pod entry point
```


## Contributing

Bug reports, recipes for the cookbook, and PRs welcome. The pipeline:

1. **Open an issue first** for anything beyond a typo fix — saves
   wasted effort if the change doesn't fit the design.
2. **Read [doc/architecture.md](doc/architecture.md)**. The
   "anti-patterns" section in particular flags directions that look
   reasonable but break things.
3. **Tests required** for behaviour changes. Use `test/clj_ant/core_test.clj`
   as the model — kaocha, with `tmp-dir` for filesystem fixtures.
4. **Don't edit `tasks.clj`** by hand. It's generated; run
   `clj -X:gen` after `gen.clj` changes or an Ant version bump.
5. **Commit format**: summary line, blank line, body wrapped at
   80 columns. No AI/LLM attribution trailers.


## Acknowledgments

This project stands on top of [Apache Ant](https://ant.apache.org/)
(Apache 2.0). The data-first design draws inspiration from how
[`ProjectHelper2`](https://github.com/apache/ant/blob/master/src/main/org/apache/tools/ant/helper/ProjectHelper2.java)
already builds an `UnknownElement` AST during XML parse — the
"don't fight the framework" insight that makes everything else
fall out cheaply.

The babashka pod uses [babashka/pods](https://github.com/babashka/pods)
and a hand-rolled bencode codec.

SSH support comes via `org.apache.ant:ant-jsch` plus the
maintained [`com.github.mwiede:jsch`](https://github.com/mwiede/jsch)
fork (the original `com.jcraft:jsch` is unpatched against
CVE-2023-48795).


## License

Eclipse Public License 1.0 — see [LICENSE](LICENSE). Apache Ant is
distributed separately under Apache 2.0.
