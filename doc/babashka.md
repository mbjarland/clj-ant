# clj-ant from babashka

Babashka itself **cannot load `org.apache.tools.ant`**. Ant relies
extensively on `URLClassLoader`, dynamic class definition, and
reflection-driven method lookup — none of which the bb sci runtime
supports. So the right interop story is a [**pod**][pods]: a JVM
process that exposes a small RPC surface, which bb scripts call as if
it were a local namespace.

[pods]: https://github.com/babashka/pods


## What you get

A bb script can:

```clojure
(require '[babashka.pods :as pods])
(pods/load-pod ["clojure" "-M:pod"])
(require '[clj-ant.pod   :as a]
         '[clj-ant.tasks :as t])     ; same wrappers bb gets via the pod

(a/execute
  [(t/property :name "dst" :value "out")
   (t/copy :todir "${dst}"
     (t/fileset :dir "src" :includes "**/*.clj"))])

(a/files (t/fileset :dir "src" :includes "**/*.clj"))
;; => ["/abs/path/a.clj" "/abs/path/b.clj" ...]
```

Operations exposed today:

| op               | input                              | output                       |
|------------------|------------------------------------|------------------------------|
| `execute`        | elements + opts map                | result map (no JVM objects)  |
| `execute-stream` | elements + handler + opts          | streams events to handler    |
| `files`          | a resource-collection element      | vector of absolute paths     |
| `files-stream`   | element + handler                  | streams paths to handler     |
| `plan`           | an element tree                    | XML-ish string               |

In addition the pod ships the full `clj-ant.tasks` namespace: 252
thin function wrappers, one per Ant task or type, that build the
element map for you so bb scripts never have to spell out
`{:tag … :attrs …}` by hand.


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


## Pure-bb alternative

For simple file-shuffling that doesn't need Ant's filter chains or
mappers, just use `babashka.fs` / `babashka.process` directly — it'll
be lighter and faster than spinning up a pod. The pod is the right
choice when you need Ant's ecosystem (tasks like `<javac>`, `<jar>`,
`<signjar>`, `<scp>`, `<junitreport>`, etc.) from a bb script.


## Future: a native pod

Today the pod is a regular `clojure -M:pod` JVM. A graalvm-native
image of the pod would be ideal — instant startup — but Ant's runtime
reflection makes that a non-trivial project (substantial
`reflect-config.json` work, plus dynamic class loading). When/if that
work happens, the protocol surface stays identical, so existing bb
scripts won't change.
