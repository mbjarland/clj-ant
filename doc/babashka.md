# clj-ant from babashka

Babashka itself **cannot load `org.apache.tools.ant`**. Ant relies
extensively on `URLClassLoader`, dynamic class definition, and
reflection-driven method lookup — none of which the bb sci runtime
supports. So the right interop story is a [**pod**][pods]: a JVM
process that exposes a small RPC surface, which bb scripts call as if
it were a local namespace.

[pods]: https://github.com/babashka/pods


## Loading the pod

Today the pod is loaded as a JVM process from the project classpath:

```clojure
(require '[babashka.pods :as pods])
(pods/load-pod ["clojure" "-M:pod"])

(require '[clj-ant.pod :as a]
         '[clj-ant.tasks :as t])
```

Publishing through the babashka pod registry is packaging work, not a
protocol change: the registry expects downloadable artifacts with a pod
entry point, while clj-ant currently ships the JVM entry point directly.
`bin/bb-pod-smoke.clj` is the CI smoke test for that public pod surface.

Registry-compatible JVM artifacts are built with:

```shell
clojure -T:build pod-artifacts
```

That creates `target/pod-registry/pod-clj-ant-<version>-jvm-unix.zip`
for Linux/macOS and `target/pod-registry/pod-clj-ant-<version>-jvm-windows.zip`
for Windows. Each zip contains a launcher executable plus the standalone
pod jar; Java must be available on the user's PATH.


## What you get

A bb deploy script. SSH, token substitution, and live event
output — the parts bb is bad at on its own — become a single
expression with the pod loaded:

```clojure
#!/usr/bin/env bb
(require '[babashka.pods :as pods]
         '[babashka.fs   :as fs]
         '[clojure.edn   :as edn])

(pods/load-pod ["clojure" "-M:pod"])
(require '[clj-ant.pod   :as a]
         '[clj-ant.tasks :as t])

(let [{:keys [version host user sha256]} (edn/read-string (slurp ".env.edn"))
      key   (str (fs/home) "/.ssh/id_ed25519")
      jar   (str "target/app-" version ".jar")
      print-step (fn [{:keys [phase task message]}]
                   (case phase
                     :task-started  (println "[start]" task)
                     :message       (when message (println " " message))
                     :task-finished (println "[done]" task)
                     nil))]

  (a/execute-stream
    [;; Stamp @VERSION@ / @HOST@ into every changed config template,
     ;; streaming through the file -- never fully buffered:
     (t/copy :todir "deploy/etc"
       ;; bb-side filter on the fileset, fed back as the source:
       (->> (a/files (t/fileset :dir "etc/templates"))
            (filter #(> (fs/last-modified-time %) (fs/last-modified-time
                                                    "/last-deploy"))))
       (t/filterchain
         (t/tokenfilter
           (t/replacestring :from "@VERSION@" :to version)
           (t/replacestring :from "@HOST@"    :to host))))

      ;; Verify the artifact, push it, restart the remote service:
      (t/condition :property "checksum.ok"
        (t/checksum :file jar :algorithm "SHA-256" :property sha256))
      (t/fail :unless "checksum.ok" :message "checksum mismatch")
      (t/scp     :file jar :todir (str user "@" host ":/srv/")
                 :keyfile key :trust "true")
      (t/sshexec :host host :username user :keyfile key :trust "true"
                 :command "systemctl --user restart app")]
    print-step))
```

That's a real CI-shaped script: bb-side mtime filter on an Ant
fileset, streaming token substitution at copy time, SHA-256
verification against the expected value from `.env.edn`, SSH push,
remote command — all with start/done/log lines streaming live to the
console as Ant fires the events.

`babashka.fs` and `babashka.process` cover the small filesystem
ops they do well. The pod is the right choice for **file pipelines
that need work after the scan**: filter-chain rewriting across
thousands of files, `restrict` + selector pruning, `replaceregexp`
over a tree, archive surgery (`<unzip>` / `<zipfileset>` reading
entries without extracting), mapper-driven mass renames, bulk SSH
push of a fileset, parallel chains where several independent
fileset operations run concurrently.

A note on raw scan speed: `babashka.fs/glob` is faster than the
pod for **just listing files** at every realistic scale.
Bb's glob is JDK NIO direct with no layers; the pod adds bencode
round-trip on top of Ant's pre-NIO `DirectoryScanner`. Sample
local benchmarks (matching `**/*.something`):

| tree            | files matched | `babashka.fs/glob` | clj-ant pod |
|-----------------|---------------|--------------------|-------------|
| ~7k jars        | ~1k           | 88 ms              | 117 ms      |
| 16k texts       | ~2k           | 111 ms             | 337 ms      |
| 84k             | (varies)      | ~195 ms            | ~660 ms     |
| 140k all match  | 84k           | 1.7 s              | 2.1 s       |
| 1.1M no match   | 0             | 23.7 s             | 22.9 s      |

So if all you need is *a list of paths*, stay in bb. Reach for the
pod when the pipeline does something with the matched files —
which is exactly when Ant's per-file machinery (filterchain,
mappers, replaceregexp, scp …) earns its keep, because it
streams that work through the scan rather than allocating a list
first and looping in bb.

Operations exposed today:

| op               | input                              | output                       |
|------------------|------------------------------------|------------------------------|
| `execute`        | elements + opts map                | result map (no JVM objects)  |
| `execute-stream` | elements + handler + opts          | streams events to handler    |
| `files`          | a resource-collection element      | vector of absolute paths     |
| `files-stream`   | element + handler                  | streams paths to handler     |
| `plan`           | an element tree                    | XML-ish string               |
| `describe`       | task/type tag                      | reflected docs/schema data   |
| `lint`           | element tree + opts                | validation issue vector      |
| `explain`        | element tree + opts                | alias for `lint`             |
| `open-session`   | opts                               | session id                   |
| `execute-in`     | session id + elements + opts       | execute using that session   |
| `close-session`  | session id                         | releases session state       |
| `with-session`   | binding vector + body              | session macro                |

In addition the pod ships the full `clj-ant.tasks` namespace: a
thin function wrapper per Ant task, type, and nested element, that
builds the element map for you so bb scripts never have to spell
out `{:tag … :attrs …}` by hand.

```clojure
(a/lint (t/copy :tdoir "out"))
;; => [{:tag :copy, :suggestions {:tdoir [:todir]}, ...}]

(-> (a/describe :copy) :attrs (get "todir"))
;; => {:type "java.io.File", :description "The directory to copy to.", ...}
```


## How it works

```
   bb script
      │  edn op + args via bencode
      ▼
   stdin → JVM clj-ant.pod → Ant Project
                      │
                      └─ task messages → stderr
                      └─ bencode replies → stdout
```

* The pod swaps `System/out` for `System/err` immediately on startup,
  so Ant's `DefaultLogger` writes task output to the parent
  process's stderr — never onto the bencode wire.
* All return values are filtered through `clj-ant.pod/element-clean` to
  drop JVM-only objects (`Project`, `Target`, `UnknownElement`) before
  they're serialised to the bb side.


## Limitations to be aware of

1. **No JVM objects round-trip.** `(a/execute …)` from bb returns a
   plain map; live `Project`/`Target`/`UnknownElement` references are
   stripped (`:targets` becomes a vector of names; `:tasks` becomes a
   count). If you need the underlying object you have to do that work
   in JVM-land.
2. **Latency.** Each pod call reuses the same long-lived JVM, so cold
   startup happens once. Subsequent calls are JVM-quick.
3. **Async / watch / cancel are JVM-only for now.** `execute-async!`,
   `cancel!`, and `watch` exist on the JVM but aren't exposed as pod
   ops yet. For long-running bb scripts that want a watch loop, run
   `bb` in foreground and rely on bb's own scripting (e.g.
   `babashka.process/process` waiting on `inotifywait`) to drive
   re-runs, calling `a/execute` per iteration. Open issue if you'd
   like this exposed; it's straightforward, just hasn't been needed.


## When to skip the pod

For one-off `cp` / `mv` / `glob` / scan work, plain `babashka.fs`
is lighter and faster — no pod startup, no JVM bencode crossing,
no extra layer over JDK NIO. Use it.

Reach for the pod when one or more of these is true:

- **Pipelined file operations.** Filter-chain templating during
  copy, `replaceregexp` across a tree, mapper-driven mass renames,
  selector chains (`restrict` + `modified` + `size` + `contains`).
  These compose in Ant; in bb they'd be many bespoke loops with a
  scan, then a doseq, then per-file IO. Ant streams the work
  through the scan, so the marginal cost over a plain `glob` is
  paid once.
- **Archive surgery without extracting.** `<zipfileset>` /
  `<tarfileset>` expose archive entries as resources you can
  iterate, slurp, or selectively `<unzip>`. bb has no equivalent.
- **SSH.** Vanilla bb has no built-in SSH. clj-ant ships
  `<scp>` and `<sshexec>` with the Terrapin-fixed JSch fork.
- **Parallel pipelines.** `<parallel>` runs independent task
  chains concurrently; useful when several heavyweight scans
  or transforms can overlap.

The honest summary: clj-ant doesn't beat `babashka.fs` at
finding files. It earns its keep on what happens after.


## Future: a native pod

Today the pod is a regular `clojure -M:pod` JVM. A graalvm-native
image of the pod would be ideal — instant startup — but Ant's runtime
reflection makes that a non-trivial project (substantial
`reflect-config.json` work, plus dynamic class loading). When/if that
work happens, the protocol surface stays identical, so existing bb
scripts won't change.
