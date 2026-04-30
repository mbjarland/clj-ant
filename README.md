# clj-ant

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

Each task call returns a node:

```clojure
(t/copy :todir "out"
  (t/fileset :dir "src" :includes "**/*.clj"))
;; => {:tag :copy, :attrs {:todir "out"},
;;     :children [{:tag :fileset, :attrs {:dir "src" :includes "**/*.clj"} ...}]}
```

Because that's just data you can:

* `update`, `assoc`, `dissoc`, `walk` it before running
* store it in an atom, send it across processes, save it to disk
* `(plan node)` to print the tree without running it
* compose builds out of normal Clojure functions
* feed the same node into the [babashka pod](doc/babashka.md) instead
  of a JVM REPL

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
arbitrarily, then drive a `(t/copy …)` with the surviving names via
`(t/filelist …)`.


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
src/clj/clj_ant/core.clj   the runner: ~250 LOC
src/clj/clj_ant/tasks.clj  auto-generated, 248 wrappers
src/clj/clj_ant/pod.clj    babashka pod
src/gen/clj_ant/gen.clj    the generator
test/clj_ant/core_test.clj
doc/babashka.md
build.clj                  tools.build entry points
deps.edn
```


## License

Eclipse Public License 1.0 or later (same as the original repo).
