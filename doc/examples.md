# clj-ant cookbook

Two halves:

* **[Recipes](#recipes)** — problems Clojure devs hit where Ant
  has a much sharper tool than what's in the standard kit.
* **[File-collection reference](#file-collection-reference)** — the
  full grammar of the resource-collection abstraction.

Setup for every snippet:

```clojure
(require '[clj-ant.core  :as a]
         '[clj-ant.tasks :as t]
         '[clojure.java.io :as io])
```


## Recipes

### Stamp variables into config files

Common at deploy time: take a template, substitute environment-driven
values, write the result to a target directory. Vanilla Clojure does
this with hand-rolled string replacement; Ant has `<filterchain>` +
`<tokenfilter>`, which streams through the file at copy time:

```clojure
(a/ant :level :warn
  (t/copy :todir "deploy/etc"
    (t/fileset :dir "etc/templates" :includes "**/*.conf")
    (t/filterchain
      (t/tokenfilter
        (t/replacestring :from "@VERSION@" :to (:version env))
        (t/replacestring :from "@HOST@"    :to (:host env))
        (t/replacestring :from "@DB_URL@"  :to (:db env))))))
```

Drop in `(a/element :replaceregex :pattern …)` instead of `:replacestring`
for regex tokens, or `:expandproperties` to substitute every `${name}`
from the project properties in one shot. Streaming-style: the file is
never fully buffered.


### Bulk find-and-replace across a tree

Refactoring across hundreds of files. `<replaceregexp>` walks a fileset
and edits in place, with `byline` for line-anchored regex and `flags`
for the usual `g`/`i`/`m`/`s`:

```clojure
(a/ant :level :warn
  (t/replaceregexp
    :match   "old\\.namespace" :replace "new.namespace"
    :flags   "g" :byline "true"
    (t/fileset :dir "src" :includes "**/*.{clj,cljc,cljs}")))
```

The same task accepts `<substitution expression="…"/>` for backref
patterns (`$1`/`$2`/etc.) when the replacement depends on captures.


### Smart copy (skip if destination is newer)

`<copy>` honours mtimes by default — pass `:overwrite "false"` plus
`:granularity` to express "only re-copy if the source is newer by at
least N millis." Useful in bb scripts that re-run periodically:

```clojure
(a/ant :level :warn
  (t/copy :todir "build/classes"
          :overwrite "false"
          :granularity "2000"           ; FAT-tolerance
          :preservelastmodified "true"
    (t/fileset :dir "src" :includes "**/*.clj")))
```

Layer a `<modified>` selector on the fileset for content-aware
"changed" detection (Ant hashes each file, caches the hashes, and
skips by content rather than mtime).


### Mass file rename via mapper

"Move every `*.clj` to `*.cljc`" without a manual loop. The mapper +
`<move>` combo does this atomically per file:

```clojure
(a/ant :level :warn
  (t/move :todir "src"
    (t/fileset :dir "src" :includes "**/*.clj")
    (t/globmapper :from "*.clj" :to "*.cljc")))
```

Mappers come in many flavours — `glob`, `regexp`, `package` (for
`com.foo.Bar` → `com/foo/Bar.class`-style mappings), `flatten`,
`merge`, `composite`, `chained`. Combine with `<copy>` for a non-
destructive transform.


### Selective archive extraction

Pull only certain entries out of a zip/tar/jar without unpacking the
rest. `<unzip>` + `<patternset>`:

```clojure
(a/ant :level :warn
  (t/unzip :src "deps/big.jar" :dest "extracted/"
    (t/patternset
            :includes "**/*.properties,META-INF/services/**"
            :excludes "**/test/**")))
```

If you only need to *read* an entry without writing it to disk, the
inverse pattern is `(a/files (t/zipfileset :src "x.jar" :includes "**/*.properties"))`
and then `slurp` over the resources — see the file-collection reference
below.


### Download + verify + extract

A common bootstrap: fetch a tarball, check its SHA-256, unpack only
what's needed. Three Ant tasks, one expression:

```clojure
(a/ant :level :warn
  (t/get :src  "https://example.com/release-1.2.3.zip"
         :dest "/tmp/release.zip"
         :usetimestamp "true")
  (t/checksum :file "/tmp/release.zip"
              :algorithm "SHA-256"
              :property  "actual"
              :verifyproperty "ok")
  (t/fail :unless "ok"
          :message "checksum mismatch on release.zip")
  (t/unzip :src "/tmp/release.zip" :dest "/opt/app"))
```

The `:verifyproperty` form is the magic bit: `<checksum>` sets `ok`
to true/false based on a sibling `release.zip.SHA-256` file, and
`<fail unless="…">` short-circuits the build with a message.


### Run a shell command for each file

The find/-exec idiom. `<apply>` spawns the executable per matched
file (or, with `:parallel "true"`, in parallel):

```clojure
(a/ant :level :info
  (t/apply :executable "convert" :parallel "true"
           :dest "build/thumbs"
    (t/fileset :dir "src/img" :includes "**/*.png")
    (t/globmapper :from "*.png" :to "*.thumb.png")
    (a/element :arg :value "-resize")
    (a/element :arg :value "120x120")
    (t/srcfile)
    (a/element :targetfile)))
```

Mapper-driven `<apply>` is the part that's actually painful from raw
`ProcessBuilder`: matching each input to its mapped output, threading
arg lists, propagating non-zero exit codes.


### Parallel pipelines

`<parallel>` runs its child tasks concurrently. Useful when you've
composed several independent build phases:

```clojure
(let [t0 (System/currentTimeMillis)]
  (a/ant :level :warn
    (a/element :parallel
      (t/sleep :seconds "2")            ; pretend: javac main
      (t/sleep :seconds "2")            ; pretend: javac test
      (t/sleep :seconds "2")))          ; pretend: docs
  (println "wall:" (- (System/currentTimeMillis) t0) "ms"))
;; => wall: ~2100 ms (not 6 s)
```

`<parallel>` accepts `:threadCount`, `:timeout`, and a `:failonany`
flag if you want the first failure to abort the rest.


### SSH and SCP from babashka without writing your own SSH

clj-ant ships with `ant-jsch` and a maintained JSch fork (the
Terrapin-fixed `com.github.mwiede:jsch`), so `<scp>` and `<sshexec>`
work out of the box from both JVM and bb. This is the recipe that
saves the most code in practice — Clojure has nothing built-in for
SSH, and rolling it from `ProcessBuilder` over the `ssh` CLI means
fighting key prompts, host-key verification, and quoting hell.

Push a build artifact and run a remote command in one expression:

```clojure
(a/ant :level :warn
  (t/scp :file        "target/app.jar"
         :todir       "deploy@web-1.example.com:/srv/app/"
         :keyfile     (str (System/getenv "HOME") "/.ssh/id_ed25519")
         :passphrase  ""
         :trust       "true")        ; or :knownhosts "/path/to/known_hosts"

  (t/sshexec :host       "web-1.example.com"
             :username   "deploy"
             :keyfile    (str (System/getenv "HOME") "/.ssh/id_ed25519")
             :command    "systemctl --user restart app"
             :trust      "true"))
```

For a pull rather than push, swap source and dest:

```clojure
(t/scp :file  "deploy@web-1.example.com:/var/log/app.log"
       :todir "/tmp/"
       :keyfile (str (System/getenv "HOME") "/.ssh/id_ed25519")
       :trust "true")
```

`<sshexec>` accepts `:outputproperty` to capture remote stdout into
an Ant property and `:errorproperty` for stderr — combined with
`:on-event` you can pipe remote command output straight into your
Clojure side. Pair with `<sshsession>` for sustained sessions that
multiplex multiple commands and forward ports.


### Audit a corpus of `build.xml` files

`from-xml` + `elements` + `transform` is a static-analysis kit for
every `build.xml` in your org. Find every insecure SCP, every
`<javac>` without debug info, every taskdef referencing a deleted
class — without writing parsers:

```clojure
(require '[babashka.fs :as fs])

;; All <scp> calls with trust="true" across every build file
(for [^java.io.File f (fs/glob "." "**/build.xml")
      :let [tree (a/from-xml (.toFile f))]
      hit  (a/elements tree
                       #(and (= :scp (:tag %))
                             (= "true" (-> % :attrs :trust))))]
  {:file (str f) :file-attr (:file (:attrs hit))})

;; Rewrite every <copy> to add :preservelastmodified="true"
(let [tree (a/from-xml "build.xml")
      patched (a/transform tree
                           (fn [e]
                             (cond-> e
                               (= :copy (:tag e))
                               (assoc-in [:attrs :preservelastmodified] "true"))))]
  ;; round-trip back: just run the rewritten tree
  (a/ant patched))
```

`a/elements` is `(filter pred (tree-seq element? :children tree))`,
laziness preserved. `a/transform` walks depth-first; children are
rewritten before parents see them, and returning `nil` from the
mapper drops the element.


### Read existing `build.xml` files

`from-xml` parses an Ant build file into the same element tree
clj-ant data forms produce. Use it for migration (run, refactor, or
re-emit), or just to query a corpus of existing builds:

```clojure
;; Run a legacy build.xml unchanged:
(a/ant (a/from-xml "build.xml"))

;; Run a specific target:
(a/ant :targets ["jar"] (a/from-xml "build.xml"))

;; Walk the tree and audit:
(let [tree (a/from-xml "build.xml")]
  (->> (tree-seq :children :children tree)
       (filter #(= :scp (:tag %)))
       (filter #(= "true" (-> % :attrs :trust)))
       count))
;; how many <scp trust="true"/> calls in the corpus
```

`src` may be a path string, a `File`, an `InputStream`, or an XML
string (detected by leading `<`). The returned root is a
`:project` element; the runner unwraps it transparently and lifts
the project's `name` / `basedir` / `default` attributes into
execute options.


### Mix Clojure code into the element tree with `deftask`

Sometimes the work is part Ant (copy, scp, jar), part Clojure
(query an API, post to Slack, mutate an atom). Without `deftask`
you'd flip back and forth: assemble Ant calls, run them, do the
Clojure step, assemble more Ant. With `deftask` the Clojure code
becomes a first-class Ant task, mixable into the same element tree:

```clojure
(a/deftask :slack-notify
  (fn [{:keys [channel msg webhook]}]
    (slack/post webhook channel msg)))

(a/ant
  (t/property :name "version" :value (read-version))
  (t/jar     :destfile "app-${version}.jar" ...)
  (t/scp     :file "app-${version}.jar"
             :todir "deploy@host:/srv/" :keyfile key)
  (a/element :slack-notify
             :webhook    slack-url
             :channel    "#deploys"
             :msg        "shipped ${version}"))
```

The Clojure fn participates in Ant fully:

- `${version}` is property-expanded *before* the fn is called, so
  `:msg` arrives as `"shipped 1.2.3"`.
- The build logger fires `:task-started` and `:task-finished` for
  the Clojure task — your event stream sees it.
- `<antcall>` can target it, `<macrodef>` can wrap it, `<parallel>`
  can run it alongside other tasks.

The fn receives one map: every attribute as a keyword key
(values are post-expansion strings), plus `:project`, `:task-name`,
and `:text` if the element had a text body.

```clojure
(a/deftask :greet (fn [{:keys [who]}] (println "hello," who)))

(a/ant
  (t/macrodef :name "greet-twice"
    (t/attribute :name "who")
    (t/sequential
      (a/element :greet :who "@{who}")
      (a/element :greet :who "@{who}")))
  (a/element :greet-twice :who "world"))
;; hello, world
;; hello, world
```


### Tight loops: sessions and prepared plans

For REPL/script workloads making many small Ant calls in a row
(deploys, watchers, generators), the per-call init dominates. Two
hooks reduce it dramatically:

```clojure
;; Reuse one Project across many execute! calls. Properties carry
;; over, registered tasks stay registered, logger setup happens once.
(a/with-session [s {:level :info}]
  (a/ant (t/property :name "v" :value "1.2.3"))
  (a/ant (t/echo :message "v=${v}"))
  ...)

;; If the SAME plan runs in a tight loop, prepare it once. The
;; coercion/validation walks happen once; runs just bind a Project
;; and execute.
(a/with-session [s {}]
  (let [p (a/prepare nodes :validate? true)]
    (dotimes [_ 100] (a/run p :session s))))
```

A 50-call benchmark on a 2-task plan:

```
fresh        575 ms
session       23 ms     (~25× faster)
prepared+ses  14 ms     (~40× faster)
```

The bigger win is session reuse. `prepare` adds another modest
chunk by skipping per-call coercion / validation walks.

The babashka pod has the same pattern via `open-session` /
`execute-in` / `close-session`:

```clojure
(let [sid (a/open-session :level :warn)]
  (try
    (dotimes [i 100]
      (a/execute-in sid [(t/echo :message (str "iter " i))]))
    (finally (a/close-session sid))))
```


### Babashka: scriptable Ant in <100 ms steady-state

Once the pod is loaded, the same `t/copy`, `t/get`, `t/unzip`, …
wrappers you use on the JVM are available in bb. The JVM stays warm
across calls in the same script run:

```clojure
(require '[babashka.pods :as pods])
(pods/load-pod ["clojure" "-M:pod"])
(require '[clj-ant.pod   :as a]
         '[clj-ant.tasks :as t])

;; live-stream events to the bb console as tasks execute
(a/execute-stream
  [(t/get   :src "https://…/v1.zip" :dest "/tmp/v.zip")
   (t/unzip :src "/tmp/v.zip"        :dest "/opt/v")]
  (fn [{:keys [phase task message]}]
    (case phase
      :task-started  (println "[start]" task)
      :message       (when message (println " " message))
      :task-finished (println "[done] " task)
      nil)))
```

Pair with `babashka.fs` for the small filesystem ops bb already does
well, and reach for the pod when you need the heavyweight tasks
(filter chains, mappers, replaceregexp, archive entry-level access,
parallel, …) that bb itself can't host.


## File-collection reference

The single rule for getting files **into** an Ant task: pass anything.
The runner figures out the wrapping.

```clojure
(a/ant (t/copy :todir "out" (t/fileset :dir "src")))     ; node
(a/ant (t/copy :todir "out" my-real-fileset))            ; Java FileSet
(a/ant (t/copy :todir "out" (filter recent? files)))     ; lazy seq
(a/ant (t/copy :todir "out" (io/file "x.clj")))          ; one File
```

The single shape for getting files **out**: `a/files` (or `a/resources`
for non-File data, or `a/realize` for the live Java object).


### Set algebra

```clojure
(def clj-files (t/fileset :dir "src" :includes "**/*.clj"))
(def edn-files (t/fileset :dir "src" :includes "**/*.edn"))

(a/files (t/union     clj-files edn-files))
(a/files (t/intersect clj-files (t/fileset :dir "src" :includes "core*")))
(a/files (t/difference clj-files (t/fileset :dir "src" :includes "**/*_test.clj")))
```


### Sorted scans, take-N

```clojure
(a/files
  (t/first :count "10"
    (t/sort (t/fileset :dir "logs" :includes "*.log")
            (t/date))))         ; or :name, :size, :type, ...
```


### Selectors via `restrict`

Selectors are richer than glob includes — depth, modified-since,
content match, file signature, "present in another tree", and so on.

```clojure
(a/files
  (t/restrict
    (t/fileset :dir "src")
    (t/size :when "more" :size "1024")            ; > 1KiB
    (t/modified :seconds "86400")))               ; modified in last day
```


### Archive entries as resources

`zipfileset` / `tarfileset` expose archive contents *without
extracting*:

```clojure
(->> (t/zipfileset :src "lib/foo.jar" :includes "**/*.class")
     a/resources
     (map #(.getName %)))           ; "com/foo/A.class" "com/foo/B.class" ...
```

Each `Resource` has `(.getInputStream r)` so you can `slurp` archive
entries from Clojure without ever writing them to disk.


### Token streams

Turn a single file into one resource per token. With a line tokenizer
you get a resource per line, slurpable individually:

```clojure
(->> (t/tokens (t/file :file "TODO.md")
               (t/linetokenizer))
     a/resources
     (map #(slurp (.getInputStream %)))
     (filter #(re-find #"^- \[ \]" %)))
;; pending TODO bullets, line-by-line
```


### Mapper-driven renaming

`mappedresources` virtually renames a collection without copying:

```clojure
(a/files
  (t/mappedresources
    (t/fileset :dir "src" :includes "**/*.clj")
    (t/globmapper :from "*.clj" :to "*.cljc")))
;; same files reported under .cljc names
```


### Scale: lazy seq → real ResourceCollection

Comma-strings and nested `<file>` elements don't scale to millions of
entries. The runner auto-wraps any seq you pass into a reified
`ResourceCollection`, so this just works:

```clojure
(let [files (lazy-seq (find-millions-of-files))]
  (a/ant (t/copy :todir "out" files)))
```

Iteration is on demand: Ant pulls one `FileResource` at a time. For
the rare case where you want to override the size hint or the
filesystem-only flag, `a/lazy-resources` accepts both.


### Realize once, reduce many

Per-call materialisation is fine for normal sizes. For tight loops or
huge collections, realise once with `a/files` (or `a/realize`) and
fold:

```clojure
(transduce (comp (filter #(.isFile %))
                 (map    #(.length %)))
           +
           0
           (a/files (t/fileset :dir "/var/log")))
```


### Note: Clojure's chunked seqs

If you pass a seq produced by `map`/`filter` (etc.) as a child,
remember that Clojure's lazy seqs are **chunked** — realising one
element drags 31 of its neighbours along. For `<first count="5">`
over a chunked seq of a million files, the iterator pulls 32, not
5. Functionally fine (32 ≪ 1 000 000), but worth knowing if you've
got expensive per-element work or want exact bounds.

To get element-by-element realisation, build the seq with
`lazy-seq`/`cons` directly instead of going through `map`:

```clojure
(letfn [(go [i] (when (< i n)
                  (lazy-seq (cons (work i) (go (inc i))))))]
  (a/lazy-resources (go 0) {:size n}))
```


### Streaming paths from babashka

```clojure
(a/files-stream
  (t/fileset :dir "/big/data" :includes "**/*")
  (fn [path]
    (when (string? path)               ; :phase :done is the sentinel
      (handle path))))
```
