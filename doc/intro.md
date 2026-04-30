# Where to start

clj-ant is a Clojure interface to Apache Ant's task and type
ecosystem. It is **not** a build tool — there's no opinion about
project layout, no replacement for `tools.build`. It exists so that
the long tail of Ant's ~250 tasks (`<scp>`, `<replaceregexp>`,
`<filterchain>`, `<jar>`, `<get>`, `<checksum>`, `<apply>`, ...)
becomes callable as ordinary Clojure code, with results readable
as Clojure data.

The docs to read, roughly in order:

1. [README.md](../README.md) — quickstart and the data model.
2. [doc/examples.md](examples.md) — recipe cookbook for real
   problems Clojure devs hit (templating, bulk find-and-replace,
   smart copy, archive surgery, parallel pipelines, SSH from bb,
   …) plus the file-collection reference.
3. [doc/babashka.md](babashka.md) — the bb pod model.
4. [doc/architecture.md](architecture.md) — design decisions,
   layer model, anti-patterns. For someone (human or AI) operating
   in the codebase.
5. [doc/tools-build.md](tools-build.md) — interop with
   `clojure.tools.build`.
6. [doc/roadmap.md](roadmap.md) — open ideas, status per item.
