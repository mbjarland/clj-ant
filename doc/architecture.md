# clj-ant — architecture and design decisions

This is the doc for someone new to the codebase who wants to know
*why* it's shaped the way it is, not just *what* the API looks like.
If you're sending a PR, this is the file to read first.

It pairs with three others:

- `README.md` — quickstart and the user-facing surface.
- `doc/examples.md` — recipes for real use cases.
- `doc/babashka.md` — the bb pod story.

If you read only one section here, read the next one. Everything
else falls out of it.


## What this project is, and isn't

clj-ant is a **Clojure interface to Apache Ant's task and type
ecosystem**. It is *not* a build tool — there's no opinion about
project layout, no tasks defined "by clj-ant," no replacement for
`tools.build`. It exists to make Ant's ~470 tasks and types
(`<copy>`, `<fileset>`, `<scp>`, `<sshexec>`, `<replaceregexp>`,
`<jar>`, `<get>`, `<checksum>`, …) callable as fluent Clojure code,
with results readable as Clojure data.

The audience is people who would otherwise:

- write XML build files and shell out via `clojure -X:foo`,
- hand-roll `ProcessBuilder` over the `ssh` CLI for deployment,
- `babashka.fs/copy-tree` then realise they need filter chains or
  mappers and there's no clean answer.


## The fundamental design choice

> **Build Ant's *own* AST (`UnknownElement` + `RuntimeConfigurable`)
> directly from Clojure data. Skip the XML round-trip. Skip the
> `Main.java` fork. Don't touch anything Ant has marked
> package-private.**

When Ant's XML parser (`ProjectHelper2`) reads `build.xml`, it
produces a tree of `UnknownElement` instances, each wrapping a
`RuntimeConfigurable` that holds the element's raw attributes and
child references. At execute time `RuntimeConfigurable.maybeConfigure`
walks that tree, expands `${…}` properties, resolves `refid`s,
applies `macrodef` / `presetdef`, and then invokes the element's
backing class.

Both `UnknownElement` and `RuntimeConfigurable` are **fully public
Ant API**. Building that tree from Clojure data instead of XML gives
us the entire Ant runtime — property expansion, refid, macrodef,
presetdef, custom taskdefs, `if`/`unless` attributes — with **zero
glue code on our side**.

```
   Clojure data form           UnknownElement / RuntimeConfigurable
   {:tag :copy                 the same AST ProjectHelper2 builds
    :attrs {:todir "out"}      from XML, just with "load XML" step
    :children [...]}    ─────► swapped for "walk Clojure data"
                                              │
                                              ▼ executeTarget
                                       Ant runs the build
```

The previous incarnation of this repo took a different path: emit
XML, register a custom URL protocol handler so the in-memory XML
masqueraded as a file, fork `Main.java` (1300 lines!) to suppress
`System.exit`, subclass `ProjectHelper2`. The new model deletes all
of that — about 1500 lines of Java plus the XML emit/parse step,
replaced by ~150 lines of Clojure walking the data into
`UnknownElement` directly.


## Layer model

```
┌─────────────────────────────────────────────────────────────┐
│  User code                                                  │
│  (a/ant (t/copy :todir "out" (t/fileset :dir "src")))       │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  Generated wrappers   (clj-ant.tasks)                       │
│  Each is one line: (apply c/element :tag args).             │
│  Carries rich :doc + :arglists for the IDE.                 │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  Data layer           (clj-ant.core/element, /Element)      │
│  Plain Clojure maps.  Mix in node trees, real Ant objects,  │
│  Files, lazy seqs -- as-child coerces them all.             │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  AST layer            (->unknown-element, java-child->ue)   │
│  Build UnknownElement / RuntimeConfigurable trees.          │
│  Resource collections come in via project refid proxies.    │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  Ant runtime          (Project, Target, Task)               │
│  We do not patch this layer. We feed it the AST it expects. │
└─────────────────────────────────────────────────────────────┘
```


## Source tree

```
src/clj/clj_ant/
  core.clj      the runner: element/Element, as-child,
                ->unknown-element, execute!, ant, sessions,
                target/deftarget, deftask + task, from-xml,
                realize, files, resources, plan, describe, datafy
  tasks.clj    GENERATED -- one wrapper per Ant task, type, and
                nested element. Don't edit; regenerate via clj -X:gen.
  spec.clj     runtime validation. Builds malli schemas lazily from
                IntrospectionHelper. validate, validate-tree,
                schema-for, :closed?, :parent (context-aware).
  pod.clj      babashka pod. Inline bencode. Exposes execute,
                execute-stream, files, files-stream, plan, and
                open-session/execute-in/close-session, plus the
                full clj-ant.tasks namespace via the describe
                payload so bb scripts use t/* directly.

src/gen/clj_ant/
  gen.clj       the generator. Walks defaults.properties + the
                recursive nested-element graph, reflects via Ant's
                IntrospectionHelper, parses the bundled manual
                HTML for descriptions, and emits the wrappers.

src/java/cljant/
  ClojureTask.java   the only Java in the project. Bridges Ant's
                Task lifecycle to a Clojure IFn registry keyed by
                task name -- needed because Ant requires a real
                instantiable Class for taskdefs and uses
                Class.getDeclaredConstructor().newInstance() to
                build them.
```

Everything user-facing lives in `clj-ant.core`. The other namespaces
are either generated or single-purpose.


## Key abstractions

### `Element` record

```clojure
(defrecord Element [tag attrs children text])
```

The data form. `tag` is a keyword like `:copy`. `attrs` is a map of
keyword to anything-stringifiable. `children` is a vec of Elements
or JavaChilds. `text` is an optional string body.

Construct via `(element :copy :todir "out" (fileset …))` or directly
through any `t/foo` wrapper. `element?` is the predicate. The
record IS a map — `(:tag e)` works as expected, and `update` /
`assoc` round-trip cleanly.

### `JavaChild` record

```clojure
(defrecord JavaChild [object tag])
```

Holds a real Ant DataType (typically a `ResourceCollection`) that
the user wants to inject *as is* — no rebuilding from data. The
runner registers `object` on the project under a synthetic refid
and emits a `<tag refid="…"/>` proxy in the AST. `:tag` defaults to
`:resources`, which works for any `ResourceCollection` because
Ant's polymorphic `add(ResourceCollection)` adder accepts that
proxy.

End users almost never construct one directly — `as-child` does it.

### `as-child`

```clojure
(as-child x) -> Element | JavaChild
```

The single rule for "what can be a child of an element":

```
Element / JavaChild     -> as is
map (Element-shaped)    -> as is
ResourceCollection      -> JavaChild(:resources)
File / Resource         -> single-element ResourceCollection
seq of File/Resource    -> reified ResourceCollection over the seq
```

This is what makes `(a/ant (t/copy :todir "out" my-thing))` work
regardless of whether `my-thing` is a clj-ant element, a real Ant
`FileSet`, a single `File`, or a lazy seq of millions of `File`s.

### `->unknown-element`

```clojure
(->unknown-element element-or-java-child project target) -> UnknownElement
```

The whole interop with Ant. Walks an Element (or JavaChild)
recursively, builds a real `UnknownElement` for each, sets attrs
via `RuntimeConfigurable.setAttribute`, recurses for children. The
key incantation:

```clojure
(.setRuntimeConfigurableWrapper ue wrap)
```

is the same hook `ProjectHelper2.ElementHandler` uses when
assembling the AST from XML. Both methods are public.

For JavaChild: we register the object under `_clj-ant.ref-N`
on the project (per call, cleaned up in `finally`), build a stub
UnknownElement with `refid` set, and let Ant resolve it at
configure time.


## Why one Java file?

`src/java/cljant/ClojureTask.java` (~70 lines, sole Java in the
repo) exists because of `deftask`. The constraint:

```java
// Ant's Project.createTask:
Class<?> klass = ...;                       // from getTaskDefinitions
Task t = (Task) klass.getDeclaredConstructor().newInstance();
```

Ant looks up the task by name, gets a `Class`, calls a no-arg
constructor. For `(deftask :slack-notify (fn [...] ...))` to plug a
Clojure fn into that lifecycle, we need an actual instantiable
class.

Options considered and rejected:

| Option                | Why not                                     |
|-----------------------|---------------------------------------------|
| `gen-class`           | Requires AOT — awkward for clj users        |
| Runtime ASM/insn      | Adds a dep just for one class               |
| `proxy`               | Survives the proxy form's instance creation, but Ant's `newInstance()` produces a "blank" proxy with no fns wired |
| `deftype`             | Can implement interfaces, can't extend `Task` (an abstract class) |
| Fake-task (no class)  | Loses macrodef/antcall/event-stream integration; defeats the point of `deftask` |

A 70-line Java file is the lightest answer. The class:

- extends `Task`,
- implements `DynamicAttribute` so any keyword maps to an attr,
- holds a static `REGISTRY` of `task-name -> IFn`,
- on `execute()` looks up its fn via `getTaskName()` and invokes it
  with a map of expanded attrs + `:project` + `:task-name` + `:text`.

This is the only thing in the project that needs `clj -T:build javac`
before tests/REPL. The compiled class lives in `target/classes`,
which is on `:paths` in `deps.edn`. Pre-built `.class` ships in the
jar via `tools.build`.


## Design decisions, with rationale

### Ant 1.10.x, not 1.9 or 2.x

- 1.9.x is unmaintained
- 1.10.x is the current stable line, JDK 8+
- 2.x doesn't exist (the "Ant 2" effort was abandoned years ago)
- bumped to 1.10.17 alongside `ant-jsch:1.10.17` for SSH support

### Maintained jsch fork, not `com.jcraft:jsch:0.1.55`

`ant-jsch` transitively pulls in `com.jcraft:jsch:0.1.55`,
unmaintained since 2018, with **CVE-2023-48795 (Terrapin)**
unpatched. We exclude that and pin `com.github.mwiede:jsch:0.2.25`
— a maintained drop-in using the same `com.jcraft.jsch` package
name, with the Terrapin fix.

This is **two deps but one SSH implementation** by design. See
`deps.edn` comment.

### malli, not spec.alpha

Schemas as data, not as macros. `IntrospectionHelper` gives us
attribute types at runtime; we map them straight to malli schemas
without a single `defmacro` or registry registration. Malli's
`string-transformer` covers Ant's "everything coerces from String"
quirk for free. Spec would have meant generating `s/def` forms via
macros — much more code, less inspectable, harder to extend.

### Context-sensitive validation

Some nested tags are ambiguous: `<attribute>` under `<macrodef>` is
`MacroDef$Attribute` (with `:default`), under `<manifest>` it's
`Manifest$Attribute` (with `:value`). The generator records every
`(parent-class, tag) → child-class` triple it sees during the
nested-element walk, and emits a `:clj-ant/by-parent` map on the
wrapper for ambiguous tags. `validate-tree` threads parent context
down through the walk so each child validates against the right
class — `(t/attribute :name "x" :default "y")` passes under
`<macrodef>` and gets rejected under `<manifest>`, matching Ant's
runtime.

### `:closed? false` default for validation

Most users pull in custom tasks via `<taskdef>`, whose attributes
our static schema can't see. Defaulting open means validation
surfaces real type errors (boolean / int / enum mismatches) without
false-positives on user taskdefs. `:closed? true` is opt-in for the
"catch all typos" mode.

### Auto-coerce children, don't make users wrap

The runner's `as-child` accepts Files, Resources, RC instances, and
seqs of those, and wraps each appropriately. Earlier versions had
three named wrappers (`child`, `eager-resources`, `lazy-resources`)
the user had to pick from. That was five names for one concept; we
collapsed it. `lazy-resources` is kept around for the rare case
where you need to override the size hint or `isFilesystemOnly` flag.

### Sessions, not just `with-project`

`(a/session opts)` returns a Session record holding one Ant Project,
plus `with-session` for scoped lifetimes. `execute!` accepts
`:session` directly. The same shape exists in the bb pod
(`open-session` / `execute-in` / `close-session`). Steady-state
benchmark on a 50-call loop: 575 ms fresh → 23 ms with session →
14 ms with `prepare` + session.

The session machinery is also where lifecycle correctness matters
most: every synthetic refid we mint for a `JavaChild` and every
named target we `addOrReplace` is tracked per-call in a dynamic
`*execute-ctx*` and removed in `finally`. Without that, reused
sessions would grow the Project's reference and target tables
across every call. With it, properties carry over (intentional)
but the build-local clutter doesn't.

### bb pod, not native image

GraalVM-native-image of the pod would give instant startup but Ant
is reflection-heavy — substantial `reflect-config.json` work for
marginal gain. The warm-JVM pod starts in well under a second; in
steady state a bb invoke is just bencode-over-stdio. If someone
ever has a hard cold-start requirement, the protocol surface stays
identical so existing scripts wouldn't change.

### bb pod ships the full `clj-ant.tasks` namespace

The pod's describe payload synthesises a `clj-ant.tasks` namespace
at startup (one tiny wrapper per task, plus an `make-element`
builder), populated by reflecting over the JVM-side `clj-ant.tasks`.
So bb scripts use `(t/copy :todir "out" (t/fileset :dir "src"))` —
identical syntax to the JVM. Without this the bb side could only
construct elements as raw `{:tag … :attrs …}` maps, which made
recipe code diverge.

### Two records (`Element`, `JavaChild`), not one

`Element` is built by clj-ant code (the data layer). `JavaChild`
wraps an opaque Java object. Both flow through children lists, but
the runner dispatches differently: Element → `->unknown-element`
recursion, JavaChild → register-and-refid. Keeping them as separate
records makes `instance?` checks unambiguous and lets the runner's
top-level dispatch read as a one-liner `if`.

### Generated wrappers (`tasks.clj`), not dynamic dispatch

We could resolve task names at runtime — every `(t/foo …)` call
delegates to a single `(element :foo …)`. Instead the generator
emits explicit `defn`s with rich docstrings + `:arglists`
metadata.

Why: **IDE ergonomics**. Cursive, CIDER, and clojure-lsp read
`:arglists` for keyword completion. With explicit defns, typing
`(t/copy :` brings up `:todir :tofile :overwrite …` inline. With
dynamic dispatch the IDE has nothing to introspect.

The cost is `tasks.clj` being a checked-in generated file (large).
Worth it. Regen with `clj -X:gen` after an Ant version bump.

### Keep "tag" names as Ant XML names

`<replaceregexp>`, `<patternset>`, `<sshexec>` keep their lowercase
no-hyphen Ant names as keywords (`:replaceregexp`, `:patternset`,
`:sshexec`). The generator only renames tags that collide with
Clojure special forms (`:if`, `:let`, `:do`, …) — those get a
`-task` suffix. `clojure.core` shadowing (`apply`, `concat`,
`replace`, `sort`, `filter`) is handled by `:refer-clojure :exclude`
on the generated namespace.

This means the Ant manual is the source of truth for attribute
names. Users can grep `https://ant.apache.org/manual/Tasks/copy.html`
and see the same names that work in their Clojure code.

### Streaming events as Clojure maps, not Java BuildEvents

`:on-event` (JVM) and `execute-stream` (bb) give you
`{:phase :task-started :task "echo"}` shaped maps. We don't expose
Ant's `BuildEvent` directly — most callers shouldn't care about the
JVM type, and crossing the bb pod boundary requires plain edn
anyway. The phases:

```
:started        :target-started        :task-started
:message
:task-finished  :target-finished       :finished
```

(The outermost pair was named `:build-started` / `:build-finished`
inheriting from Ant's `BuildListener` method names. Renamed —
clj-ant isn't a build tool.)


## Common code paths

### Running a build (data → output)

```
(a/ant (t/copy :todir "out" (t/fileset :dir "src")))
            │
            ▼ ant fn parses leading kw opts, calls execute!
(execute! [Element copy] :level :info)
            │
            ▼ binding *execute-ctx* with refs/targets atoms
            ▼ make-project: Project + DefaultLogger, init,
            │ register all deftask'd names (or sync if reused)
            ▼ collect inline tasks (those with :clj-ant/inline-fn)
            ▼ collision-check + register inline tasks transiently
            ▼ for each top-level Element, ->unknown-element
            │
            │   ->unknown-element(Element copy, project, target)
            │       UnknownElement ue("copy")
            │       RuntimeConfigurable wrap
            │       wrap.setAttribute("todir", "out")
            │       recurse into :children:
            │           ->unknown-element(Element fileset, ...)
            │       (or, if a child is a JavaChild:
            │            register obj on project with a refid we
            │            also push into *execute-ctx*, emit refid
            │            proxy element)
            │       ue.setRuntimeConfigurableWrapper(wrap)
            │
            ▼ implicit Target ""
            │ + named-targets if any
            ▼ project.executeTargets(["", named...])
                │
                ▼ Ant evaluates the AST. RuntimeConfigurable
                  expands ${...}, resolves refids, builds the real
                  Task / DataType, calls .execute().
            │
            ▼ finally:
              remove BuildListener
              restore prior class bindings for inline tasks
              remove every refid we registered
              remove every named target we addOrReplace'd
```

### Reading a build.xml (XML → data)

```
(a/from-xml "build.xml")
       │
       ▼ clojure.xml/parse  (JDK SAX)
       ▼ xml->element walk:
       │   {:tag :attrs :content} -> ->Element
       │   text content collapsed; whitespace-only dropped
       ▼ root :project Element with :clj-ant/source-dir attr
       │
       ▼ pass to (a/ant ...) -- execute! special-cases :project:
       │   resolve relative basedir against source-dir,
       │   lift name/default into opts
       │   children become elements
       │   if a default target was named, run it (after the
       │   implicit unnamed target, so root <property> declarations
       │   fire first)
```

### Going through the bb pod

```
bb script                       JVM pod
─────────                       ───────
(load-pod ["clojure" "-M:pod"])
   sends "describe" op           ────►
                                 builds describe payload:
                                   - clj-ant.pod ns (execute, plan,
                                     files, execute-stream,
                                     files-stream, open/close/in
                                     session)
                                   - clj-ant.tasks ns (reflected
                                     from JVM, one wrapper per
                                     task + make-element)
                                 returns bencode dict
   ◄──── sci eval'd into bb's runtime
(t/copy :todir ... (t/fileset ...))
   builds {:tag :copy ...} map  (in bb)
(a/execute-in sid [...])
   sends "invoke" with edn args ────►
                                 op-execute-in calls
                                 core/execute! with :session.
                                 :on-event pushes each event back
                                 as bencode reply with status [].
                                 Final reply is the cleaned result
                                 wrapped in {:clj-ant/result …}.
   ◄──── for each event, success-handler fires
   ◄──── final reply is the sentinel; bb stub unwraps & returns
```


## Extension points

If you're adding a feature, here's where it goes:

| You want to...                           | Touch...                            |
|------------------------------------------|-------------------------------------|
| Add a new top-level operation            | `core.clj`                          |
| Add per-tag validation                   | `spec.clj` (build-schema)           |
| Add a streaming pod op                   | `pod.clj` (ops + describe payload)  |
| Add a CLI/tool helper                    | A new ns under `src/clj/clj_ant/`   |
| Tune the docstring format                | `gen.clj` (`task-fn-source`), regen |
| Bump Ant                                 | `deps.edn` + run `clj -X:gen`       |
| Add a recipe                             | `doc/examples.md`                   |
| Capture an open idea                     | `doc/roadmap.md`                    |

If you're adding a **task wrapper**, don't write it by hand —
either it's already in `tasks.clj` (run `clj -X:gen` after an Ant
bump), or it should come in via `<taskdef>` at runtime which the
runner handles transparently.

If you're adding **attribute coercion** (e.g. accept a `keyword`
for some new attr type), edit `attr->string` in `core.clj`. Don't
add per-tag special cases.

If you're adding **child types** (e.g. accept a Java `Path`
object), edit `as-child`. Same single point.

If you're adding **a new way to RUN elements** (e.g. dry-run, REPL
mode, distributed), build it on top of `execute!` rather than
inside it.


## Conventions

- **Commit messages**: summary line, blank line, body wrapped at
  80 columns. Keep messages focused on the *why*.
- **Comments**: explain *why*, not *what*. Anti-pattern is
  "// rename variable for refactor #123" — that belongs in a PR
  description.
- **No emoji** in code or commits unless explicitly requested.
- **Docstrings**: lead with what the function returns, then options.
  Mirror the structure of existing docstrings in `core.clj`.
- **Names from Ant**: keep tag names as Ant uses them. Don't alias
  `:replaceregexp` to `:regex-replace` or similar — drift from the
  manual is hostile.
- **Tests**: kaocha. New features land with at least one happy-path
  test. Side-effecting tests use `tmp-dir` from the test ns.


## Glossary of Ant terms used here

- **Task** — an executable Ant element (e.g. `<copy>`, `<echo>`).
  Subclasses `org.apache.tools.ant.Task`.
- **Type** (DataType) — a reusable element that doesn't execute
  but can be referenced (e.g. `<fileset>`, `<path>`). Subclasses
  `org.apache.tools.ant.types.DataType`.
- **Element** — the generic XML element name. Either a Task or a
  Type. clj-ant calls its data form an `Element` regardless.
- **UnknownElement** — Ant's intermediate AST node. Wraps a
  `RuntimeConfigurable` until execute time, when the real
  Task/DataType class is resolved.
- **RuntimeConfigurable** — holds raw attribute strings + child
  references + text. `maybeConfigure` is the entry point for
  `${...}` expansion and refid resolution.
- **IntrospectionHelper** — Ant's reflection layer over a
  Task/DataType class. Tells us which attributes / nested elements
  / addText support each class has.
- **refid** — an XML-level reference: `<fileset refid="x"/>`
  resolves to whatever was registered under id `"x"` on the
  Project.
- **macrodef** / **presetdef** — Ant's element-composition
  mechanisms. Implemented inside `RuntimeConfigurable` -- so they
  work for clj-ant element trees automatically.


## Decisions we'd revisit if we started over

A short list of choices that look reasonable but have known
limitations or open questions. Mostly here to save the next
contributor from re-discovering them.

- **The XML-emit approach.** The previous incarnation of this repo
  did this, with the `Main.java` fork and the URL handler. ~1500
  lines of Java. The current direct-AST approach is ~150 lines of
  Clojure. If you find yourself thinking "let me just generate
  the XML and hand it to ProjectHelper2" — read the `git log` of
  what got deleted to make this work.

- **`IntrospectionHelper.setAttribute` directly.** Skips the
  RuntimeConfigurable wrap and calls the setter immediately.
  Doesn't expand `${...}`, doesn't resolve `refid`, doesn't do
  `macrodef`. The wrap layer is mandatory.

- **A third record type.** Two records (`Element` and `JavaChild`)
  cover the runner's dispatch. A third would mean a third branch
  in `->unknown-element`. Resist.

- **Bundling every Ant optional module.** `ant-jsch` is bundled
  because SSH is the killer use case. `ant-junit` / `ant-jmf` /
  `ant-commons-net` etc. are not bundled — users who need them
  add them to their own `deps.edn`. The generator picks them up
  on the next `clj -X:gen` and emits wrappers automatically.

- **Lifting `:keep-going` / `:fail-on-error` / etc. to top-level
  opts.** These are Ant attributes on individual targets. Use
  them as attributes; don't lift them into `execute!`.

- **Editing `tasks.clj` by hand.** It's generated. Edit `gen.clj`
  and re-run `clj -X:gen`.


## Where to look when something's wrong

| Symptom                                         | Suspect                                         |
|-------------------------------------------------|-------------------------------------------------|
| `${...}` not expanded                           | Attribute coerced to non-String. Check `attr->string`. |
| `BuildException: doesn't support the foo attribute` | Attribute name mismatch with Ant. `(s/schema-for :tag)` to see what's accepted. |
| Mystery `NullPointerException` in JSch          | jcraft jsch and mwiede jsch both on classpath.  |
| bb pod returns nil for streamed values          | `:success` handler signature mismatch. Bb pod handlers receive the *decoded* value directly, not `{:value v}`. |
| Test passes locally, fails on fresh checkout    | `clj -T:build javac` not run. `target/classes/cljant/ClojureTask.class` missing. |
| Unknown task at runtime                         | Custom `<taskdef>` loaded the class but `make-project` didn't see it. Or the fn under that name in `ClojureTask/REGISTRY` was cleared. |
| Stack overflow loading `clj-ant.tasks` in bb    | `apply` (or `concat`/`replace`/...) self-recurses. The bb wrappers must use `clojure.core/apply` (fully qualified). |
| Reused session leaks references / targets       | `*execute-ctx*` not bound or `finally` not run. |


## Public-API cheat sheet

```
Element            user-built data form. Has :tag :attrs :children :text
JavaChild          wraps a Java DataType for refid injection
element / element? construct / predicate
as-child           single coercion point
->unknown-element  data → Ant AST
execute!           the runner (low level)
ant                user-facing wrapper around execute!
session / with-session  long-lived Project for tight loops
prepare / run      coerce+validate once, replay cheaply
target / deftarget named targets and dependency resolution
deftask            global registration of a Clojure fn as a task
task               inline Clojure thunk as a one-shot task element
realize            Element → live Java object
files / resources  Element → seq of File / Resource
plan               pretty-print an Element tree
describe           introspect a tag (tag → attrs/nested map)
from-xml           build.xml → Element tree
elements           lazy seq of every node in a tree
transform          rewrite a tree, post-order
schema-for         malli schema for a tag
validate-tree      collect errors from a tree
```
