# Roadmap

Open ideas for clj-ant, ordered roughly by leverage-per-effort.
Each entry has a status, a one-paragraph sketch, and a rough size.

Statuses:
- 🚧 in progress
- ✅ done
- ⏳ planned
- 💭 idea — not committed to


## High-leverage

### `deftask` — Clojure fns as first-class Ant tasks  ✅

Today there's a wall between *Clojure code* and *the Ant element
tree*: you flip out of `(a/ant …)` to do anything in Clojure, then
flip back. `deftask` removes that wall by letting any Clojure fn
register itself as an Ant task name:

```clojure
(a/deftask :slack-notify
  (fn [{:keys [channel msg webhook]}]
    (slack/post webhook channel msg)))

(a/ant
  (t/jar :destfile "app.jar" ...)
  (t/scp :file "app.jar" :todir "deploy@host:/srv/")
  (a/element :slack-notify :webhook url :channel "#deploys"
             :msg "shipped ${version}"))
```

Inside the tree the new task is indistinguishable from a built-in:
`${…}` property expansion runs on its attributes, the build logger
emits `:task-started` / `:task-finished`, targets containing it can be
invoked with `<antcall>`, and `<macrodef>` can wrap it.

Implementation: a single small Java class (`cljant.ClojureTask`)
that extends `org.apache.tools.ant.Task` and implements
`DynamicAttribute`; an `IFn` registry keyed by task name. About 60
lines of Java + 30 lines of Clojure.

**Size:** medium. The Java code is contained and unambiguously
justified — it's a bridge that can't be done any other way without
AOT or a bytecode-gen library.


### `from-xml` — read existing `build.xml` files into clj-ant data  ✅

Inverse of execute. Hand it a path or a string, get back a clj-ant
element tree. Massive migration path for shops with thousands of
lines of legacy Ant: walk the tree from Clojure, refactor with
`update`/`assoc`/`walk`, re-emit compact XML with `to-xml`, or keep
it as data and run with `execute!`.

Same shape powers a static analyzer over a corpus of build files —
"every `<scp>` with `:trust true`", "every `<javac>` without
`:debug`".

**Size:** small. ~80 lines using `clojure.xml` (built-in, no dep).


### tools.build interop  ✅

Two-way bridge between clj-ant and `clojure.tools.build.api`.
Direction A: `b/javac`, `b/jar`, `b/copy-dir` etc. wrapped as
clj-ant tasks via `deftask` so they slot into element trees with
the same shape as everything else. Direction B: clj-ant tasks
called from a tools.build script — a normal fn call. The combined
pitch: tools.build for Clojure-native operations, clj-ant for the
long tail (signing, deployment, archive surgery, SSH, XSLT).

**Size:** small. ~50 lines wrappers + recipes in
`doc/tools-build.md`. Mostly documentation.


## Medium-leverage

### Element-tree querying  ✅

`(elements tree pred)` and `(transform tree f)` ship in `core.clj`.
Specter / zippers turned out to be unnecessary: `tree-seq` over
`:children` already gives a lazy depth-first walk, and the current
`transform` covers post-order rewrites. See `doc/examples.md` "Audit
a corpus of build.xml files".

**Size:** small. Shipped without adding Specter or a zipper layer.


### Session reuse for REPL and scripts  ✅

`(a/session opts)` and `(a/with-session [s opts] ...)` reuse one Ant
`Project` across many calls. Properties set in one call are visible in
the next, permanent `deftask` registrations are re-synced, and per-call
synthetic references/targets are cleaned up in `finally`. Pass
`:project` explicitly to opt out for a single call.


### Better error surface  ✅

`BuildException` raised by `executeTargets` is now wrapped into
`ex-info` carrying `:clj-ant/elements`, `:clj-ant/targets`,
`:ant/exception-class`, and `:ant/message` (the unwrapped root
message). The original throwable is preserved as `:cause` for
full stack-trace access. Tooling can pattern-match on failure
mode without parsing strings.


### Async + cancel  ✅

`(execute-async! …)` returns a Run handle that delegates to a
`promise` underneath. `(cancel! run)` interrupts the build
thread; IO tasks abort cleanly, CPU-bound ones may run through.
`:cancelled? true` lands on the result map regardless to
reflect caller intent.


### Watch mode  ✅

`(watch nodes :paths […] :poll-ms …)` re-runs the plan on every
filesystem change under the named paths. Polling-based; pairs
with `:session` for cheap re-runs.


### `ICoercible` extension protocol  ✅

`as-child` is now a protocol dispatch. Out-of-tree types extend
`clj-ant.core/ICoercible` from their own ns and flow as children
of any task without forking the lib.


## Lower-leverage

### Auto-`defmethod` dispatch on tag  💭

A multimethod `interpret-element` that defaults to UnknownElement
but can be specialised per tag. Lets users plug in custom side
effects without `deftask`'s registration step. Powerful but easy to
misuse; `deftask` covers the legitimate cases.

**Size:** small.


### Native pod (GraalVM image)  💭

A graalvm-native-image build of the bb pod for instant startup. Ant
uses a lot of runtime reflection, so this is a substantial
`reflect-config.json` project for relatively marginal gain over a
warm-JVM pod (which already starts in well under a second).

**Size:** large. Probably skip unless someone has a hard
cold-start requirement.


### Custom typedef from Clojure  💭

Same idea as `deftask` but for types/data structures (your own
ResourceCollection, Selector, Mapper). Useful but rare; most users
will never need it.

**Size:** medium.


## Anti-roadmap

Things deliberately *not* on the list, with reasons.

- **CLJS port.** The JVM is the entire point — porting Ant doesn't
  make sense.
- **Dropping `lazy-resources`.** Looks like an escape hatch but
  matters for million-file scans where the default `count` is too
  expensive.
- **Streaming `plan`.** It's already trivially fast; complexifying
  the wire protocol for nothing.
