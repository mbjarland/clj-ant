# File / resource collections

Ant's resource-collection abstraction is one of its most useful
ideas, and the Clojure surface here surfaces it cleanly: any node
whose backing class implements
`org.apache.tools.ant.types.ResourceCollection` flows through
`(a/resources …)` (yields `Resource`) and `(a/files …)` (yields
`java.io.File`) as a lazy seq.

```clojure
(require '[clj-ant.core  :as a]
         '[clj-ant.tasks :as t]
         '[clojure.string :as str])
```


## 1. Plain fileset → seq

```clojure
(->> (t/fileset :dir "src" :includes "**/*.clj")
     a/files
     (map #(.getName %))
     sort)
;; => ("core.clj" "gen.clj" "pod.clj" "spec.clj" "tasks.clj")
```


## 2. Filter Clojure-side, then drive an Ant copy

A fileset can't express "files modified in the last 24 h" directly,
but Clojure can. Pull the names, filter, hand the survivors to
`<filelist>`:

```clojure
(let [src   "src"
      out   "out/recent"
      since (- (System/currentTimeMillis) (* 24 60 60 1000))
      hits  (->> (t/fileset :dir src :includes "**/*")
                 a/files
                 (filter #(>= (.lastModified %) since))
                 (mapv #(.getName %)))]
  (a/ant
    (t/mkdir   :dir out)
    (t/copy    :todir out
      (t/filelist :dir src :files (str/join "," hits)))))
```


## 3. Set operations with `union`, `intersect`, `difference`

Ant gives you set algebra over collections:

```clojure
(def clj-files
  (t/fileset :dir "src" :includes "**/*.clj"))
(def edn-files
  (t/fileset :dir "src" :includes "**/*.edn"))

(a/files (t/union clj-files edn-files))
;; all .clj and .edn files

(a/files (t/intersect clj-files
                       (t/fileset :dir "src" :includes "**/core*")))
;; .clj files whose name matches core*
```

`t/difference`, `t/sort`, `t/first`, `t/last` work the same way.


## 4. Sort by name, take 2

`<sort>` accepts a selector child. Pair with `<first>` to take the
top N:

```clojure
(a/files
  (t/first :count "2"
    (t/sort
      (t/fileset :dir "logs" :includes "*.log")
      (a/node :name))))
;; first 2 .log files in alphabetical order
```

Selectors available out of the box include `:name`, `:date`, `:size`,
`:content`, `:type`, `:exists`. Ant's manual lists more under
**Resource Comparators**.


## 5. Reach into archives without extracting

`zipfileset` exposes the entries of a zip as resources:

```clojure
(->> (t/zipfileset :src "lib/something.jar" :includes "**/*.class")
     a/resources
     (map #(.getName %)))
;; ("META-INF/MANIFEST.MF" "com/.../Foo.class" ...)
```

`tarfileset` does the same for `.tar.*`. Combined with `union` and
`restrict` you can write a single resource-collection that spans
several archives plus the local filesystem.


## 6. `restrict` + selectors for DSL-flavoured filtering

```clojure
(a/files
  (t/restrict
    (t/fileset :dir "src")
    (a/node :size :when "more" :size "1024")))      ; > 1024 bytes
```

Ant ships dozens of selectors (size, depth, modified, signature,
present, contains, regex, …) that the data form composes the same
way.


## 7. Token streams: split a file into resources

`<tokens>` turns a resource into one resource per token. With a
linetokenizer it gives you a resource per line:

```clojure
(->> (t/tokens
       (t/file :file "TODO.md")
       (a/node :linetokenizer))
     a/resources
     (map (fn [r] (slurp (.getInputStream r)))))
;; (\"line 1 contents\" \"line 2 contents\" ...)
```

Pair with `<concat>` or `<echoxml>` to stream-process line-by-line
files entirely from Clojure data.


## 8. Mapped resources: rename on the fly

```clojure
(a/files
  (t/mappedresources
    (t/fileset :dir "src" :includes "**/*.clj")
    (a/node :globmapper :from "*.clj" :to "*.cljc")))
;; same files, but reported under .cljc names
```

Useful when you want to drive a `<copy>` whose destination names
differ from sources, but you still want to verify the destination
list in Clojure first.


## 9. Realize once, iterate many times

`a/files` materialises a fresh project per call. If you're iterating
a large fileset many times, build it once:

```clojure
(let [files (vec (a/files my-fileset-node))]
  (println "found" (count files))
  (doseq [f files] (process f)))
```

For sources where the collection is huge, prefer `transduce` /
`reduce` so the lazy seq isn't fully realised:

```clojure
(transduce (comp (filter #(.isFile %))
                 (map    #(.length %)))
           +
           0
           (a/files (t/fileset :dir "/var/log")))
```


## 10. Babashka: stream paths as they're discovered

Long scans benefit from streaming on the bb side:

```clojure
(require '[babashka.pods :as pods])
(pods/load-pod ["clojure" "-M:pod"])
(require '[clj-ant.pod :as a])

(a/files-stream
  {:tag :fileset :attrs {:dir "/big/data" :includes "**/*"}}
  (fn [path]
    (when (string? path)               ; skip the final {:phase :done}
      (println "scanning" path)
      ;; do work as paths arrive, no need to wait for the full scan
      )))
```


## What's underneath

```
node (data)         -> ->unknown-element       -> UnknownElement
                                                   ↓ maybeConfigure
                                                  (the real Ant type)
                                                   ↓ .iterator
                                                  Iterator<Resource>
                                                   ↓ iterator-seq
                                                  lazy Clojure seq
                                                   ↓ map FileProvider.getFile
                                                  lazy seq of File
```

Everything past `maybeConfigure` is Ant's own behaviour — pattern
matching, refid resolution, archive handling, mapper application —
which is why Clojure-only seq tooling and Ant's resource-collection
zoo plug into one pipeline without us writing scanning code.
