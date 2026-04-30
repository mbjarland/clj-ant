# clj-ant

> **Pre-1.0 alpha.** Architecture is stable; surface APIs may shift
> slightly during the alpha period based on real-world feedback. Once
> 1.0 lands, semver applies normally.

Fluent Apache Ant from Clojure. Write your build as Clojure data,
hand it to `ant`, get full Ant power — `${...}` properties, `refid`,
`macrodef`, fileset resolution, the works — without ever touching XML.

```clojure
(require '[clj-ant.core  :as a]
         '[clj-ant.tasks :as t])

(a/ant
  :basedir "."
  (t/property :name "out" :value "target/classes")
  (t/mkdir   :dir "${out}")
  (t/javac   :srcdir "src" :destdir "${out}" :includeantruntime "false")
  (t/jar     :destfile "target/app.jar" :basedir "${out}")
  (t/echo    :message "built ${out}"))
```

That's the whole story. There is no XML round-trip, no forked
`Main.java`, no custom URL protocol — just plain data plus Ant's own
public AST classes.


## Why a data-first model

Each task call returns an element:

```clojure
(t/copy :todir "out"
  (t/fileset :dir "src" :includes "**/*.clj"))
;; => {:tag :copy, :attrs {:todir "out"},
;;     :children [{:tag :fileset, :attrs {:dir "src" :includes "**/*.clj"} ...}]}
```

Because that's just data you can:

* `update`, `assoc`, `dissoc`, `walk` it before running
* store it in an atom, send it across processes, save it to disk
* `(plan elt)` to print the tree without running it
* compose pipelines out of normal Clojure functions
* feed the same element into the [babashka pod](doc/babashka.md)
  instead of a JVM REPL

Execution doesn't happen until the tree is handed to `a/ant` (or
`a/execute!`). At that point clj-ant builds Ant's own
`UnknownElement` + `RuntimeConfigurable` tree directly — the same AST
Ant's XML parser produces — and lets Ant evaluate it. So **everything
that works in `build.xml` works here**: property substitution, refids,
macrodef, presetdef, if/unless, custom taskdefs, the lot.


## Clojure sequences over Ant resource collections

```clojure
(->> (t/fileset :dir "src" :includes "**/*.clj")
     a/files                              ; lazy seq of java.io.File
     (filter #(> (.lastModified %) cutoff))
     (mapv  #(.getName %)))
```

Anything whose backing class implements
`org.apache.tools.ant.types.ResourceCollection` (`fileset`, `filelist`,
`path`, `dirset`, `files`, `restrict`, `intersect`, `union`, …) flows
through `a/resources` (yields `Resource`) and `a/files` (yields
`java.io.File`). The seqs are lazy iterator-seqs, so `transduce`,
`into`, `reduce`, and friends compose normally.

You can round-trip: pull a fileset into Clojure, filter it
arbitrarily, then hand the surviving files **straight back as a
child of any task**. The runner accepts whatever you have — a clj-ant
element, a real Ant `FileSet`, a single `File`, or a (lazy) seq of
`File`/`Resource`/path-string — and wraps it through Ant's project
reference machinery as needed. So you never write `(str/join "," xs)`
or build a tree of nested `<file>` elements, even at million-file
scale:

```clojure
(let [recent (->> (a/files (t/fileset :dir "src"))
                  (filter #(> (.lastModified %) cutoff)))]
  (a/ant (t/copy :todir "out" recent)))   ; raw seq, no wrapper
```

See **[doc/examples.md](doc/examples.md)** for the full set —
set algebra (`union`/`intersect`/`difference`), sort + first, archive
contents, mapped resources, token streams, and the scale path.


## Validation (malli)

Schemas are produced on demand from Ant's `IntrospectionHelper`. No
hand-written specs, no macros — just data, cached per tag:

```clojure
(require '[clj-ant.spec :as s])

(s/schema-for :copy)
;; => [:map {:closed false}
;;     [:todir       {:optional true} [:or [:fn ...] :string]]
;;     [:overwrite   {:optional true} [:or :boolean [:enum "true" "false" ...]]]
;;     ...]
```

Two ways to surface errors:

```clojure
(s/validate :copy {:overwrite "perhaps"})
;; => {:overwrite ["should be a boolean"
;;                 "should be either \"true\" \"false\" \"yes\" ..."]}

(a/ant :validate? true (t/copy :overwrite "perhaps"))
;; ExceptionInfo: Build failed validation: 1 issue(s)
```

Add `:closed? true` to also reject unknown attributes (catches typos
like `:tdoir`). Off by default — your build may load custom taskdefs
whose attributes vanilla Ant can't introspect.

```clojure
(a/ant :validate? true :closed? true (t/copy :tdoir "out"))
;; => :tdoir ["disallowed key"]
```


## Streaming events

`:on-event` fires synchronously per `BuildEvent`:

```clojure
(a/ant
  :on-event (fn [{:keys [phase task message level]}]
              (when (= :message phase)
                (println " >>" message)))
  (t/javac :srcdir "src" :destdir "out"))
```

Phases the listener emits: `:started` `:target-started`
`:task-started` `:message` `:task-finished` `:target-finished`
`:finished`. Composes with `:capture? true` if you want both
live updates and a final list.

The babashka pod ships an `execute-stream` and `files-stream` op that
relay each event / each path through bb's multi-reply protocol — see
[doc/babashka.md](doc/babashka.md).


## Targets and dependencies

For real builds you want named targets with declared dependencies, a
default target, and dispatch by name. clj-ant treats targets as data
nodes too:

```clojure
(a/deftarget clean
  (t/delete :dir "out" :failonerror "false"))

(a/deftarget compile
  :depends [:clean]
  :description "compile sources"
  (t/mkdir :dir "out")
  (t/javac :srcdir "src" :destdir "out"))

(a/deftarget package
  :depends [:compile]
  (t/jar :destfile "app.jar" :basedir "out"))

;; Run with explicit target:
(a/ant :targets ["package"] clean compile package)
;; clean and compile fire automatically as declared dependencies.

;; Or with :default for the project default target:
(a/ant :default "package" clean compile package)
```

`:depends` accepts a string, a list of strings, or a list of
keywords/symbols. `:if` and `:unless` work the same way they do in
`build.xml`.


## REPL ergonomics

Every task wrapper carries a rich docstring and an `:arglists`
metadata that lists the keyword attributes:

```
user=> (doc t/copy)
-------------------------
clj-ant.tasks/copy
([& {:keys [todir tofile overwrite encoding ...] :as attrs} & nested])
  Copies a file or resource collection to a new file or directory. ...

  Attributes:
    :todir          File
    :tofile         File
    :overwrite      boolean
    :encoding       String
    ...
  Nested elements:
    :fileset        (FileSet)
    :filterchain    (FilterChain)
    ...
  https://ant.apache.org/manual/Tasks/copy.html
```

Cursive, CIDER, and clojure-lsp all read `:arglists` for keyword
completion, so typing `(t/copy :` brings up `:todir :tofile :overwrite
…` inline. For programmatic introspection use `(a/describe :copy)`,
which returns the same information as Clojure data.


## SSH support

`<scp>` and `<sshexec>` are bundled out of the box — `ant-jsch` plus
the Terrapin-fixed `com.github.mwiede:jsch` fork. Push a jar and
restart a remote service in one expression:

```clojure
(a/ant
  (t/scp     :file "target/app.jar" :todir "deploy@web-1:/srv/"
             :keyfile "~/.ssh/id_ed25519" :trust "true")
  (t/sshexec :host "web-1" :username "deploy"
             :keyfile "~/.ssh/id_ed25519" :trust "true"
             :command "systemctl --user restart app"))
```

See [doc/examples.md](doc/examples.md#ssh-and-scp-from-babashka-without-writing-your-own-ssh).


## Getting started in this repo

clj-ant has one Java file (`src/java/cljant/ClojureTask.java`)
that bridges Ant's `Task` lifecycle to a Clojure fn registry. It
needs to be compiled once before tests / REPL:

```sh
clj -T:build javac     # one-time, after a fresh clone
clj -M:test            # then this works
```

Released jars on Clojars include the pre-compiled class — downstream
consumers do **not** need to run `javac` themselves.


## Aliases

```
clj -X:gen          ; regenerate src/clj/clj_ant/tasks.clj from Ant's
                    ; defaults.properties + manual
clj -M:test         ; run tests via kaocha
clj -T:build jar    ; build a jar
clj -M:pod          ; entry point used by the babashka pod
```


## Project layout

```
src/clj/clj_ant/core.clj    the runner + targets + execute! + ant
src/clj/clj_ant/spec.clj    malli schemas from IntrospectionHelper
src/clj/clj_ant/tasks.clj   auto-generated wrappers (one per Ant
                            task, type, and nested element)
src/clj/clj_ant/pod.clj     babashka pod
src/gen/clj_ant/gen.clj     the generator
test/clj_ant/core_test.clj
doc/babashka.md             pod design + bb usage
doc/examples.md             resource-collection cookbook
build.clj                   tools.build entry points
deps.edn
```


## License

Eclipse Public License 1.0 or later (same as the original repo).
