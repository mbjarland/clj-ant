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
    (a/element :filterchain
      (a/element :tokenfilter
        (a/element :replacestring :from "@VERSION@" :to (:version env))
        (a/element :replacestring :from "@HOST@"    :to (:host env))
        (a/element :replacestring :from "@DB_URL@"  :to (:db env))))))
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
    (a/element :globmapper :from "*.clj" :to "*.cljc")))
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
    (a/element :patternset
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
    (a/element :globmapper :from "*.png" :to "*.thumb.png")
    (a/element :arg :value "-resize")
    (a/element :arg :value "120x120")
    (a/element :srcfile)
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
    (a/element :attribute :name "who")
    (a/element :sequential
      (a/element :greet :who "@{who}")
      (a/element :greet :who "@{who}")))
  (a/element :greet-twice :who "world"))
;; hello, world
;; hello, world
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
            (a/element :date))))         ; or :name, :size, :type, ...
```


### Selectors via `restrict`

Selectors are richer than glob includes — depth, modified-since,
content match, file signature, "present in another tree", and so on.

```clojure
(a/files
  (t/restrict
    (t/fileset :dir "src")
    (a/element :size :when "more" :size "1024")            ; > 1KiB
    (a/element :modified :seconds "86400")))               ; modified in last day
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
               (a/element :linetokenizer))
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
    (a/element :globmapper :from "*.clj" :to "*.cljc")))
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


### Streaming paths from babashka

```clojure
(a/files-stream
  (t/fileset :dir "/big/data" :includes "**/*")
  (fn [path]
    (when (string? path)               ; :phase :done is the sentinel
      (handle path))))
```
