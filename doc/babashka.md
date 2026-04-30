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
(require '[clj-ant.pod :as a])

(a/execute [{:tag :property :attrs {:name "dst" :value "out"}}
            {:tag :copy
             :attrs {:todir "${dst}"}
             :children [{:tag :fileset
                         :attrs {:dir "src" :includes "**/*.clj"}}]}])

(a/files {:tag :fileset
          :attrs {:dir "src" :includes "**/*.clj"}})
;; => ["/abs/path/a.clj" "/abs/path/b.clj" ...]
```

Three operations are exposed today:

| op       | input                       | output                       |
|----------|-----------------------------|------------------------------|
| `execute`| `nodes` + opts map          | result map (no JVM objects)  |
| `files`  | a resource-collection node  | vector of absolute paths     |
| `plan`   | a node tree                 | XML-ish string               |

Inputs are plain edn data: a *node* is exactly the same map
`clj-ant.core/node` produces on the JVM side
(`{:tag … :attrs {…} :children […]}`).


## How it works

```
   bb script
      │  edn op + args via bencode
      ▼
   stdin → JVM clj-ant.pod → Ant Project
                      │
                      └─ build messages → stderr
                      └─ bencode replies  → stdout
```

* The pod swaps `System/out` for `System/err` immediately on startup,
  so Ant's `DefaultLogger` writes build chatter to the parent
  process's stderr — never onto the bencode wire.
* All return values are filtered through `clj-ant.pod/node-clean` to
  drop JVM-only objects (`Project`, `Target`, `UnknownElement`) before
  they're serialised to the bb side.


## Limitations to be aware of

1. **No streamed events yet.** The current pod returns a value once the
   build is finished. Real-time event streaming would require switching
   to the bb-pods `transit+json` async/streaming protocol — open work.
2. **No JVM objects round-trip.** A `(a/execute …)` call from bb returns
   a plain map; if you need the underlying `Project` you have to do
   that work in JVM-land.
3. **Latency.** Each `execute` reuses the same long-lived JVM, so cold
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
