(ns clj-ant.core
  "Reach Ant's task and type ecosystem from Clojure.

  This is not a build tool -- it's an interface that lets you call
  Ant tasks and types as if they were Clojure functions, with the
  results readable as Clojure data. Each call returns a tree of
  plain maps:

      (copy :todir \"out\"
        (fileset :dir \"src\" :includes \"**/*.clj\"))
      ;; => a element map -- nothing runs yet

  Pass that tree to `execute!` (or `ant`) to invoke Ant on it. At
  that point clj-ant assembles Ant's own AST (`UnknownElement` +
  `RuntimeConfigurable`) directly -- the same tree Ant's XML parser
  produces -- so property expansion (`${foo}`), `refid`, `macrodef`,
  `presetdef`, `if`/`unless` attributes etc. all work for free,
  because they are implemented inside Ant's runtime configuration
  machinery, not its XML layer."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.xml :as xml]
            [clojure.core.protocols :as p])
  (:import [org.apache.tools.ant Project ProjectComponent Target Location IntrospectionHelper
                                 UnknownElement RuntimeConfigurable
                                 DefaultLogger BuildListener BuildEvent]
           [org.apache.tools.ant.types Resource ResourceCollection]
           [org.apache.tools.ant.types.resources FileProvider]
           [cljant ClojureTask]
           [java.io File PrintStream]))

;; ---------------------------------------------------------------------------
;; Element construction
;;
;; Tasks and types are nothing but functions that return a map. The map is
;; opaque data — in particular, calling (copy ...) does NOT trigger any Ant
;; activity. That only happens once the tree is handed to `execute!` / `ant`.

(defrecord Element [tag attrs children text])

;; A JavaChild wraps an actual Ant DataType / ResourceCollection
;; so it can be inlined as a child of a regular element. The runner
;; registers `object` on the project under a unique reference id
;; and emits a `<tag refid="..."/>` proxy in the AST. This lets us
;; hand huge collections (or pre-built FileSets, Paths, Mappers, ...)
;; to Ant without rebuilding them as data.
(defrecord JavaChild [object tag])

(defn element?
  "Truthy if `x` is a clj-ant element."
  [x]
  (instance? Element x))

(defn java-child?
  "Truthy if `x` is a `child`-wrapped real Ant object."
  [x]
  (instance? JavaChild x))

;; ---------------------------------------------------------------------------
;; Child coercion. The runner only ever sees Element / JavaChild instances,
;; so anything else a user passes as a child gets normalized here once.
;;
;; The user-facing rule is intentionally one sentence: "you can pass any
;; clj-ant element, any Java Ant DataType, any File or Resource, or a seq of
;; the above as a child." Everything below is the implementation of that
;; sentence.

(defn- ^:private build-lazy-rc
  "Reify a ResourceCollection over a (possibly lazy) Clojure seq of File
  / Resource / path-string. Pulls FileResource instances on demand."
  [files]
  (let [->res      (fn [x]
                     (cond
                       (instance? Resource x) x
                       (instance? File x)
                       (org.apache.tools.ant.types.resources.FileResource. ^File x)
                       :else
                       (org.apache.tools.ant.types.resources.FileResource.
                         ^File (io/file x))))
        size-cache (delay (count files))]
    (reify ResourceCollection
      (iterator [_]
        (let [s (atom (seq files))]
          (reify java.util.Iterator
            (hasNext [_] (boolean (seq @s)))
            (next [_] (let [v (->res (first @s))]
                        (swap! s next) v)))))
      (size [_] @size-cache)
      (isFilesystemOnly [_] true))))

(defprotocol ICoercible
  "Coerce a value to a child usable inside an element tree.

  The default extensions cover everything the runner already
  handles: Element / JavaChild records (passed through), Ant
  ResourceCollection / Resource / File (wrapped as a refid'd
  proxy), node-shaped maps (passed through), and Sequential of
  any of the above (wrapped as a lazy ResourceCollection).

  To plug in your own type without forking, extend the protocol:

      (extend-protocol clj-ant.core/ICoercible
        my.lib.SomeFileBag
        (-as-child [bag]
          (clj-ant.core/->JavaChild
            (.toAntResourceCollection bag) :resources)))

  After that, instances flow through `as-child` (and therefore
  any task wrapper) like any other built-in shape."
  (-as-child [x]
    "Convert `x` to an Element / JavaChild / node-shaped map.
    Implementations should return a value the runner can drop
    into the children list."))

(defn- ^:no-doc rc-of-one [x]
  (->JavaChild (build-lazy-rc [x]) :resources))

(extend-protocol ICoercible
  Element
  (-as-child [x] x)

  JavaChild
  (-as-child [x] x)

  ResourceCollection
  (-as-child [x] (->JavaChild x :resources))

  File
  (-as-child [x] (rc-of-one x))

  Resource
  (-as-child [x] (rc-of-one x))

  ;; Catches Vector / List / LazySeq / Cons / etc.
  clojure.lang.Sequential
  (-as-child [x] (->JavaChild (build-lazy-rc x) :resources))

  ;; Element and JavaChild are records so they hit those impls
  ;; first; this one fires for plain hand-built maps that carry
  ;; a :tag (the from-xml shape, the bb-pod shape).
  clojure.lang.IPersistentMap
  (-as-child [x] x)

  nil
  (-as-child [_]
    (throw (ex-info "Cannot use nil as a child" {})))

  Object
  (-as-child [x]
    (throw (ex-info (str "Cannot use as child: " (pr-str x))
                    {:value x :type (class x)}))))

(defn as-child
  "Coerce `x` to a child usable inside an element tree. Dispatches
  through the `ICoercible` protocol -- extend that protocol for
  your own types.

  Built-in dispatches:

    Element / JavaChild        -> as is
    org.apache.tools.ant.types.ResourceCollection
                               -> JavaChild (refid proxy at execute time)
    File / Resource            -> single-element ResourceCollection
    Sequential of the above    -> lazy ResourceCollection over the seq
    map (element-shaped)       -> as is

  You don't usually call this yourself -- `element` (and the
  generated task wrappers) call it on every positional arg. So
  this works out of the box:

      (a/ant (t/copy :todir \"out\" my-fileset))      ; FileSet
      (a/ant (t/copy :todir \"out\" (find-files)))    ; lazy seq of File
      (a/ant (t/copy :todir \"out\" (io/file \"x\"))) ; one File"
  [x]
  (-as-child x))

(defn- split-args
  "Pulls keyword/value attribute pairs off the front of a positional arg
  list, leaving anything else as children. Mirrors hiccup-style calling
  conventions while keeping the data form a plain map.

  Children are coerced via `as-child`, so users can mix data elements,
  raw Java DataTypes, Files, and lazy seqs of Files freely."
  [args]
  (loop [attrs {} text nil children [] xs args]
    (let [x (first xs)]
      (cond
        (empty? xs)
        [attrs text children]

        (and (keyword? x) (seq (rest xs)))
        (recur (assoc attrs x (second xs)) text children (drop 2 xs))

        (string? x)
        (recur attrs (str (or text "") x) children (rest xs))

        (nil? x)
        (recur attrs text children (rest xs))

        (sequential? x)
        ;; A sequential whose first element looks like a child element
        ;; splices (the (mapv #(element :file ...) names) idiom). A
        ;; sequential of anything else (Files, Resources, paths) is
        ;; treated as a single resource-collection child.
        (let [fst (first x)]
          (if (or (element? fst) (java-child? fst) (map? fst))
            (recur attrs text (into children x) (rest xs))
            (recur attrs text (conj children (as-child x)) (rest xs))))

        :else
        (recur attrs text (conj children (as-child x)) (rest xs))))))

(defn element
  "Build a clj-ant element for tag `tag-kw`. The remaining args follow
  hiccup-ish positional rules:

  * keyword/value pairs are attributes
  * strings concatenate into the element's text body
  * maps or other elements become children
  * sequential collections of elements/maps splice in as children
  * sequential collections of files/resources/paths become one resource child

  Returned value is an `Element` record — a plain map you can inspect,
  walk, diff, transform with `update`, store in an atom, etc."
  [tag-kw & args]
  (let [[attrs text children] (split-args args)]
    (->Element tag-kw attrs (vec children) text)))

;; ---------------------------------------------------------------------------
;; Project lifecycle

(defn ^:no-doc default-logger
  ^DefaultLogger [{:keys [out err level emacs?]
                   :or   {out    System/out
                          err    System/err
                          level  :info
                          emacs? false}}]
  (doto (DefaultLogger.)
    (.setMessageOutputLevel
      (case level
        :error Project/MSG_ERR
        :warn Project/MSG_WARN
        :info Project/MSG_INFO
        :verbose Project/MSG_VERBOSE
        :debug Project/MSG_DEBUG))
    (.setOutputPrintStream ^PrintStream out)
    (.setErrorPrintStream ^PrintStream err)
    (.setEmacsMode (boolean emacs?))))

(declare make-project)

(def ^{:doc     "If non-nil, `execute!` reuses this Project instead of
  building a fresh one. Bind via `with-project` / `with-session`
  for REPL-driven workflows where the per-call init adds up."
       :dynamic true}
  *project* nil)

(defmacro with-project
  "Run body with `project` bound as the default Project for every
  enclosed `execute!` / `ant` call.

      (let [p (a/make-project {:level :info})]
        (a/with-project p
          (a/ant (t/echo :message \"first\"))
          (a/ant (t/echo :message \"second\"))))   ; same JVM Project

  Properties set in earlier calls remain visible in later ones --
  that's Ant's normal Project semantics. Pass an explicit
  `:project` option to override for a single call.

  Prefer `with-session` for new code; this stays for existing users
  who hold a raw Project."
  [project & body]
  `(binding [*project* ~project] ~@body))

(defrecord ^:no-doc Session [^Project project])

(defn session
  "Create a long-lived session backed by a single Ant Project.
  Reuse across many execute!/ant calls to amortise the per-call
  init + logger setup + taskdef registration cost.

      (def s (a/session {:level :info}))
      (a/ant :session s (t/echo :message \"first\"))
      (a/ant :session s (t/echo :message \"second\"))
      (a/close-session s)

  Or, with automatic cleanup:

      (a/with-session [s {:level :info}]
        (a/ant (t/echo :message \"first\"))
        (a/ant (t/echo :message \"second\")))

  Properties carry over between calls in the same session -- that's
  Ant's normal Project semantics. Pass `:project` explicitly to a
  single call to opt out for that call.

  `opts` are the same options accepted by `make-project`."
  ([] (session {}))
  ([opts] (->Session (make-project (or opts {})))))

(defn close-session
  "Release a session. Currently the Project is just dropped from
  the dynamic binding; held resources clean up under GC. Kept for
  forward compatibility -- if we ever attach long-lived resources
  to a Session (caches, listeners) this is the cleanup hook."
  [_session]
  nil)

(defmacro with-session
  "Bind a fresh session for the duration of body. The session is
  closed automatically on exit. `opts` are the options accepted by
  `make-project`.

      (a/with-session [s {:basedir \"out\" :level :info}]
        (a/ant (t/property :name \"v\" :value \"1\"))
        (a/ant (t/echo :message \"v=${v}\")))   ; v carries over"
  [[sym opts] & body]
  `(let [~sym (session ~opts)]
     (try
       (binding [*project* (:project ~sym)] ~@body)
       (finally (close-session ~sym)))))

;; Generation counter on the global ClojureTask registry. Each
;; `deftask` call bumps it. Projects track the gen they last synced
;; against so `execute!` can skip the per-call replay when nothing
;; has changed (the common case after the first call).
(defonce ^:private registry-gen (atom 0))

;; Names registered via deftask. ClojureTask/REGISTRY also briefly contains
;; inline task fns during execute!, so Project sync must not treat every
;; registry entry as a permanent task definition.
(defonce ^:private deftask-tags (atom #{}))

(defonce ^:private inline-task-counter (atom 0))

(defn- ^:no-doc registered-deftask-tags []
  (filter #(.containsKey ClojureTask/REGISTRY %) @deftask-tags))

(defn make-project
  "Build a fresh `org.apache.tools.ant.Project`, attach a logger, and
  call `init` so all built-in tasks/types are registered.

  Options:

    :basedir   String or File. Default: current working directory.
    :name      Project name string. Default: \"clj-ant\".
    :level     :error | :warn | :info | :verbose | :debug. Default :info.
    :out, :err PrintStream for the default logger.
    :emacs?    Strip the [taskname] adornments. Default false.
    :listeners Coll of `BuildListener` instances to attach.
    :props     Map of user properties to seed."
  ^Project [{:keys [basedir name listeners props]
             :or   {basedir "." name "clj-ant"}
             :as   opts}]
  (let [p (doto (Project.)
            (.setName name)
            (.setBaseDir (io/file basedir))
            (.addBuildListener (default-logger opts))
            (.init))]
    (doseq [^BuildListener l listeners]
      (.addBuildListener p l))
    (doseq [[k v] props]
      (.setUserProperty p (clojure.core/name k) (str v)))
    ;; Register every Clojure-defined task on this project, then
    ;; remember the registry generation we synced against. Future
    ;; `execute!` calls compare the project's stamp to the global
    ;; gen and skip the doseq when nothing has changed.
    (doseq [tn (registered-deftask-tags)]
      (.addTaskDefinition p tn ClojureTask))
    (.addReference p "_clj-ant.registry-gen" (atom @registry-gen))
    p))

(defn- ^:no-doc sync-deftasks!
  "Sync the project's task definitions against the global
  ClojureTask/REGISTRY *only* if the registry has changed since the
  last sync. Idempotent and cheap on the steady-state path."
  [^Project project]
  (let [stamp (.getReference project "_clj-ant.registry-gen")
        cur   @registry-gen]
    (when (or (nil? stamp) (not= cur @stamp))
      (doseq [tn (registered-deftask-tags)]
        (.addTaskDefinition project tn ClojureTask))
      (if stamp
        (reset! stamp cur)
        (.addReference project "_clj-ant.registry-gen" (atom cur))))))

;; ---------------------------------------------------------------------------
;; build.xml round-trip

(defn- xml->element
  "Convert one node from clojure.xml's representation to ours."
  [{:keys [tag attrs content]}]
  (let [child-maps  (filterv map? content)
        text-pieces (filter string? content)
        text        (when (seq text-pieces)
                      (let [s (apply str text-pieces)]
                        ;; Drop whitespace-only inter-element text.
                        (when (some #(not (Character/isWhitespace %)) s)
                          s)))]
    (->Element tag (or attrs {}) (mapv xml->element child-maps) text)))

(defn- ->source-dir
  "If `src` is a file/path on disk, return its parent directory.
  Returns nil for streams, readers, or XML strings -- there's no
  source to anchor on."
  ^File [src]
  (cond
    (instance? File src) (.getAbsoluteFile (.getParentFile (.getAbsoluteFile ^File src)))
    (and (string? src) (not (re-find #"^\s*<" src)))
    (recur (io/file src))
    :else nil))

(defn from-xml
  "Parse an Ant build file into a clj-ant element tree.

  `src` may be:
    * a path to a file on disk
    * a `java.io.File`, `InputStream`, or `Reader`
    * an XML string (detected by leading `<`)

  Returns the root `:project` element. Pass it to `execute!` or `ant`
  to run the file -- the runner unwraps it transparently and lifts
  the project's name/basedir/default attributes into execute options.

      (a/ant (a/from-xml \"build.xml\"))
      (a/ant :targets [\"clean\"] (a/from-xml \"build.xml\"))

  Relative paths inside the build file resolve from the **source
  file's directory**, matching Ant's own semantics. (For string and
  stream sources the source dir is unknown, so paths fall back to
  the process cwd.) The captured source dir is exposed under
  `:clj-ant/source-dir` on the returned element's attrs map, so
  static analysers can tell on-disk roots apart from
  string-literal ones.

  For static analysis just walk the returned tree like any other
  element."
  [src]
  (let [src-dir (->source-dir src)
        parsed  (cond
                  (and (string? src) (re-find #"^\s*<" src))
                  (xml/parse (java.io.ByteArrayInputStream.
                               (.getBytes ^String src "UTF-8")))
                  :else (xml/parse src))
        elt     (xml->element parsed)]
    (cond-> elt
            src-dir (update :attrs assoc :clj-ant/source-dir src-dir))))

(defn- xml-name [x]
  (cond
    (keyword? x) (if-some [ns (namespace x)] (str ns ":" (name x)) (name x))
    (symbol? x) (if-some [ns (namespace x)] (str ns ":" (name x)) (name x))
    :else (str x)))

(defn- xml-escape [s attr?]
  (cond-> (str/replace (str s) "&" "&amp;")
          true (str/replace "<" "&lt;")
          true (str/replace ">" "&gt;")
          attr? (str/replace "\"" "&quot;")))

(declare attr->string)

(defn to-xml
  "Render a clj-ant element tree to compact Ant XML.

  This is the inverse of `from-xml` for normal data trees. It renders
  `Element` records and element-shaped maps, escapes XML text and
  attribute values, and omits clj-ant's internal namespaced attrs such as
  `:clj-ant/source-dir`.

  JVM-backed children (`JavaChild`, raw `FileSet`s, lazy resource seqs)
  cannot be represented faithfully as XML and will throw.

      (to-xml (element :echo :message \"hi\"))
      ;; returns <echo message=\"hi\"/>"
  [tree]
  (letfn [(render [n]
            (cond
              (java-child? n)
              (throw (ex-info "Cannot render JVM-backed child as XML"
                              {:child-type :java-child}))

              (or (element? n) (and (map? n) (contains? n :tag)))
              (let [tag      (xml-name (:tag n))
                    attrs    (->> (:attrs n)
                                  (remove (fn [[k _]] (= "clj-ant" (namespace k))))
                                  (sort-by (comp xml-name first)))
                    attr-str (apply str
                                    (for [[k v] attrs]
                                      (str " " (xml-name k) "=\""
                                           (xml-escape (attr->string v) true)
                                           "\"")))
                    text     (:text n)
                    children (:children n)]
                (if (or text (seq children))
                  (str "<" tag attr-str ">"
                       (when text (xml-escape text false))
                       (apply str (map render children))
                       "</" tag ">")
                  (str "<" tag attr-str "/>")))

              :else
              (throw (ex-info "Cannot render value as XML element"
                              {:value n :type (some-> n class .getName)}))))]
    (render tree)))

;; ---------------------------------------------------------------------------
;; Clojure-defined tasks
;;
;; `deftask` registers a Clojure fn as an Ant task. The fn becomes
;; indistinguishable from a built-in inside an element tree --
;; ${...} property expansion runs on its attributes, the build
;; logger fires task-started / task-finished, <antcall> can target
;; it, <macrodef> can wrap it. The bridge class lives in
;; src/java/cljant/ClojureTask.java and is the only Java in clj-ant.

(defn deftask
  "Register a Clojure fn as an Ant task named `tag`.

  The fn is called on execute with one argument: a map containing

    :project    the org.apache.tools.ant.Project
    :task-name  the registered tag name (a String)
    :text       the element's text body, if any
    <attr>      one keyword entry per attribute; values are strings
                with `${...}` property references already expanded.

  Side-effecting: the registration is global. Every Project clj-ant
  builds via `make-project` after this call has the task installed.
  Re-registering the same `tag` replaces the previous fn.

  Example:
      (a/deftask :slack-notify
        (fn [{:keys [channel msg webhook]}]
          (slack/post webhook channel msg)))

      (a/ant
        (a/element :slack-notify :webhook url
                   :channel \"#deploys\"
                   :msg \"shipped ${version}\"))"
  [tag f]
  (let [n (clojure.core/name tag)]
    (.put ClojureTask/REGISTRY n f)
    (swap! deftask-tags conj n))
  (swap! registry-gen inc)
  tag)

(defn deftask?
  "Truthy if `tag` has been registered via `deftask`."
  [tag]
  (let [n (clojure.core/name tag)]
    (and (contains? @deftask-tags n)
         (.containsKey ClojureTask/REGISTRY n))))

(defn task
  "Inline a Clojure thunk as an Ant task. Returns an element you can
  drop into any element tree. The fn runs in target order with full
  event-stream participation -- you'll see :task-started /
  :task-finished for the chosen tag.

      (a/ant
        (t/echo :message \"before\")
        (a/task :compile #(b/javac {:src-dirs [\"src\"]
                                     :class-dir \"out\" :basis @basis}))
        (a/task :jar     #(b/jar {:class-dir \"out\"
                                   :jar-file \"target/app.jar\"}))
        (t/scp :file \"target/app.jar\" :todir \"deploy@host:/srv/\"))

  Use this when you need arbitrary Clojure code between Ant tasks
  -- tools.build operations, slack notifications, query an API,
  mutate an atom -- and the args are richer than Ant's
  string-attribute model can carry.

  Unlike `deftask`, the fn is NOT added to a global registry.
  Instead, it's attached to the element as `:clj-ant/inline-fn`
  metadata; the runner registers it on the project just before
  execution and removes it in `finally`. So a 1000-call REPL
  session leaves no entries behind. Use `deftask` when you want a
  reusable named task across many builds."
  ([f] (task nil f))
  ([tag f]
   (let [;; Anonymous tasks get a distinctively-prefixed name so they
         ;; cannot collide with any Ant task, user deftask, or another
         ;; anonymous inline task in a concurrent execute!. Explicit tags
         ;; pass through verbatim; the runner refuses at execute time if
         ;; they would shadow something.
         tag (or tag
                 (keyword (str "clj-ant-inline-"
                               (swap! inline-task-counter inc))))]
     (with-meta (element tag)
                {:clj-ant/inline-fn f}))))

(defn- ^:no-doc collect-inline-tasks
  "Walk `elements` and return [{:tag <kw> :fn <fn>} ...] for every
  element that carries an :clj-ant/inline-fn meta (created via
  `task`). Used by execute! to register/unregister around a build."
  [elements]
  (letfn [(walk [acc x]
            (cond
              (and (element? x)
                   (-> x meta :clj-ant/inline-fn))
              (let [acc (conj acc {:tag (:tag x)
                                   :fn  (-> x meta :clj-ant/inline-fn)})]
                (reduce walk acc (:children x)))

              (element? x)
              (reduce walk acc (:children x))

              (and (map? x) (contains? x :tag))
              (reduce walk acc (:children x))

              :else acc))]
    (reduce walk [] elements)))

;; ---------------------------------------------------------------------------
;; Data → UnknownElement
;;
;; This is the whole interop surface. Everything else in clj-ant just feeds
;; elements here.

(defn- attr->string
  "Coerce a Clojure attribute value to the string form Ant ultimately
  consumes. Conventions:

    - String: identity (the common case; `${...}` expansion runs at
      configure time as long as we hand Ant a String).
    - Keyword/Symbol: name (so `:depends [:clean]` works without
      manual stringification).
    - Sequential: comma-join of element names (Ant's convention for
      list attributes like `<filelist files=\"a,b,c\"/>`).
    - File / anything else: `(str v)`."
  ^String [v]
  (cond
    (string? v) v
    (keyword? v) (name v)
    (symbol? v) (name v)
    (sequential? v) (clojure.string/join ","
                                         (map #(cond
                                                 (keyword? %) (name %)
                                                 (symbol? %) (name %)
                                                 :else (str %))
                                              v))
    :else (str v)))

(defn- target-name
  "Return the non-blank Ant target name for an element, or nil."
  [attrs]
  (when-some [v (:name attrs)]
    (let [n (attr->string v)]
      (when-not (str/blank? n) n))))

(defn- require-target-name!
  "Fail fast for target elements without a usable :name."
  [element]
  (or (target-name (:attrs element))
      (throw (ex-info "Target elements require a non-empty :name attribute"
                      {:tag   (:tag element)
                       :attrs (:attrs element)}))))

;; Per-project monotonic counter for synthetic reference ids. Counter
;; lives on the project so it survives across calls when the project
;; is reused via with-session; the *cleanup* of the actual refid
;; entries happens per execute! (see *execute-ctx* below).
(def ^:private ^:dynamic *execute-ctx*
  "Per-execute! mutable context. When bound, calls that allocate
  per-build state (synthetic refids, named targets we add via
  addOrReplaceTarget) record their additions here so the `finally`
  block in execute! can prune them. Without this, reused sessions
  would accumulate refids and obsolete targets across calls."
  nil)

(defn- next-ref-id! [^Project project]
  (let [k      "_clj-ant.ref-counter"
        n      (or (.getReference project k)
                   (let [a (atom 0)]
                     (.addReference project k a)
                     a))
        ref-id (str "_clj-ant.ref-" (swap! n inc))]
    (when-some [ctx *execute-ctx*]
      (swap! (:refs-added ctx) conj ref-id))
    ref-id))

(defn- java-child->ue
  ^UnknownElement [^JavaChild jc ^Project project ^Target target]
  (let [{:keys [object tag]} jc
        ref-id   (next-ref-id! project)
        _        (.addReference project ref-id object)
        tag-name (name (or tag :resources))
        ue       (doto (UnknownElement. tag-name)
                   (.setProject project)
                   (.setOwningTarget target)
                   (.setQName tag-name)
                   (.setTaskName tag-name)
                   (.setLocation Location/UNKNOWN_LOCATION))
        wrap     (doto (RuntimeConfigurable. ue tag-name)
                   (.setAttribute "refid" ref-id))]
    (.setRuntimeConfigurableWrapper ue wrap)
    ue))

(defn- ->unknown-element
  ^UnknownElement [n ^Project project ^Target target]
  (if (java-child? n)
    (java-child->ue n project target)
    (let [{:keys [tag attrs children text]} n
          tag-name (name tag)
          ue       (doto (UnknownElement. tag-name)
                     (.setProject project)
                     (.setOwningTarget target)
                     (.setQName tag-name)
                     (.setTaskName tag-name)
                     (.setLocation Location/UNKNOWN_LOCATION))
          wrap     (RuntimeConfigurable. ue tag-name)]
      (doseq [[k v] attrs]
        (.setAttribute wrap (name k) (attr->string v)))
      (when text
        (.addText wrap (str text)))
      ;; Coerce on the way through. Children that didn't go via
      ;; `element` / `split-args` (from-xml-built maps, pod-built
      ;; maps, hand-rolled data) may carry raw payloads that
      ;; ->unknown-element can't handle directly. as-child turns
      ;; them into proper Element / JavaChild before recursion.
      (doseq [c children]
        (let [child (->unknown-element (as-child c) project target)]
          (.addChild ue child)
          (.addChild wrap (.getWrapper child))))
      ;; Task.setRuntimeConfigurableWrapper is public; this is the same
      ;; hook ProjectHelper2 uses when assembling the AST from XML.
      (.setRuntimeConfigurableWrapper ue wrap)
      ue)))

;; ---------------------------------------------------------------------------
;; Execution

(defn execute!
  "Execute one or more elements against a fresh (or supplied) project.

  Top-level forms become tasks of an implicit unnamed target which is
  then executed. Returns a map containing the project, the target, the
  list of UnknownElements that ran, plus any captured events.

  Options:

    :project    An existing org.apache.tools.ant.Project. If absent, a
                fresh one is built from the same options.
    :capture?   If true, attach a recording BuildListener and return
                the captured events under :events. Default false.

  Plus any options accepted by `make-project`."
  [elements & {:as opts}]
  (binding [*execute-ctx* {:refs-added    (atom #{})
                           :targets-added (atom #{})}]
    (let [elements         (cond
                             (element? elements) [elements]
                             (sequential? elements) (vec elements)
                             (map? elements) [elements]
                             :else (throw (ex-info "execute! expects an element or seq of elements"
                                                   {:value elements})))
          ;; A single :project element (e.g. from `from-xml`) is a
          ;; syntactic wrapper -- lift its basedir/name/default into
          ;; execute options and run its children. Caller opts win.
          ;;
          ;; Basedir resolution matches Ant: relative basedir attrs are
          ;; resolved against :clj-ant/source-dir (the directory of
          ;; the on-disk build.xml, captured by from-xml). Missing
          ;; basedir falls back to the source-dir. Without a source
          ;; dir we keep the legacy "process cwd" behaviour.
          [elements opts]
          (if (and (= 1 (count elements))
                   (= :project (:tag (first elements))))
            (let [root      (first elements)
                  a         (:attrs root)
                  src-dir   (:clj-ant/source-dir a)
                  resolved-basedir
                            (when-some [bd (or (:basedir a) (and src-dir (.getPath ^File src-dir)))]
                              (let [f (io/file bd)]
                                (if (or (.isAbsolute f) (nil? src-dir))
                                  f
                                  (io/file src-dir bd))))
                  from-file (cond-> {}
                                    resolved-basedir (assoc :basedir resolved-basedir)
                                    (:name a) (assoc :name (:name a))
                                    (:default a) (assoc :default (:default a)))]
              [(vec (:children root)) (merge from-file opts)])
            [elements opts])
          _                (when (:validate? opts)
                             (let [vt   (requiring-resolve 'clj-ant.spec/validate-tree)
                                   vopt (select-keys opts [:closed?])
                                   errs (mapcat #(vt % vopt) elements)]
                               (when (seq errs)
                                 (throw (ex-info
                                          (str "Validation failed: " (count errs)
                                               " issue(s)")
                                          {:errors (vec errs)})))))
          ;; Project resolution precedence (most explicit wins):
          ;;   :project opt  >  :session opt  >  *project* binding  >
          ;;   make-project opts (fresh)
          ;; Matches the docstring promise that an explicit :project on
          ;; a single call opts out of an enclosing session.
          project          ^Project (or (:project opts)
                                        (when-some [s (:session opts)] (:project s))
                                        *project*
                                        (make-project opts))
          ;; Sync deftask registrations only if the global registry has
          ;; changed since this project last saw it. With many calls to
          ;; the same with-project, this is the steady-state fast path.
          _                (sync-deftasks! project)
          ;; Inline tasks created via `task`. We add their fns to the
          ;; registry just for this run and remove them in `finally`,
          ;; so a long REPL session doesn't leak per-call inline tasks.
          ;;
          ;; Collision policy: an explicit tag that already maps to a
          ;; non-ClojureTask definition (built-in task or some other
          ;; deftask under a *different* class) is refused outright --
          ;; better to fail loudly than to silently rebind <echo> for
          ;; the rest of the build, or to clobber a deftask on cleanup.
          ;; A tag that already maps to ClojureTask (i.e. an existing
          ;; deftask of the same name) IS refused too: we would
          ;; otherwise overwrite its fn during the build and remove its
          ;; entry on cleanup.
          ;;
          ;; Save/restore: anonymous tags use a distinct prefix and
          ;; can't collide. We still snapshot prior task definitions
          ;; per inline tag so any future widening of the policy is
          ;; safe by construction.
          inline-tasks     (collect-inline-tasks elements)
          _                (doseq [{:keys [tag]} inline-tasks]
                             (let [n     (clojure.core/name tag)
                                   prior (.get (.getTaskDefinitions project) n)]
                               (when prior
                                 (throw
                                   (ex-info
                                     (str "(a/task " (pr-str tag) " ...) collides with an "
                                          "existing task definition (" (.getName ^Class prior)
                                          "). Use a different tag, or `deftask` if you want "
                                          "a permanent named task.")
                                     {:tag tag :existing (.getName ^Class prior)})))
                               (when (.containsKey ClojureTask/REGISTRY n)
                                 (throw
                                   (ex-info
                                     (str "(a/task " (pr-str tag) " ...) collides with an "
                                          "existing deftask under the same name.")
                                     {:tag tag})))))
          prior-defs       (reduce (fn [m {:keys [tag]}]
                                     (let [n (clojure.core/name tag)]
                                       (assoc m n (.get (.getTaskDefinitions project) n))))
                                   {}
                                   inline-tasks)
          _                (doseq [{:keys [tag fn]} inline-tasks]
                             (let [n (clojure.core/name tag)]
                               (.put ClojureTask/REGISTRY n (clojure.core/fn [_] (fn)))
                               (.addTaskDefinition project n ClojureTask)))
          events           (when (:capture? opts) (atom []))
          on-event         (:on-event opts)
          emit             (fn [m]
                             (when events (swap! events conj m))
                             (when on-event (on-event m))
                             nil)
          rec              (when (or events on-event)
                             (reify BuildListener
                               (buildStarted [_ _] (emit {:phase :started}))
                               (buildFinished [_ e]
                                 (emit {:phase :finished
                                        :error (some-> ^BuildEvent e .getException
                                                       .getMessage)}))
                               (targetStarted [_ e]
                                 (emit {:phase  :target-started
                                        :target (some-> ^BuildEvent e .getTarget .getName)}))
                               (targetFinished [_ e]
                                 (emit {:phase  :target-finished
                                        :target (some-> ^BuildEvent e .getTarget .getName)
                                        :error  (some-> ^BuildEvent e .getException
                                                        .getMessage)}))
                               (taskStarted [_ e]
                                 (emit {:phase :task-started
                                        :task  (some-> ^BuildEvent e .getTask .getTaskName)}))
                               (taskFinished [_ e]
                                 (emit {:phase :task-finished
                                        :task  (some-> ^BuildEvent e .getTask .getTaskName)
                                        :error (some-> ^BuildEvent e .getException
                                                       .getMessage)}))
                               (messageLogged [_ e]
                                 (emit {:phase   :message
                                        :message (.getMessage ^BuildEvent e)
                                        :level   (.getPriority ^BuildEvent e)}))))
          ;; Split target elements from regular task/type elements. Targets get
          ;; their own Target instance + addOrReplaceTarget; regular elements
          ;; go onto an implicit unnamed target that runs by default.
          target-elements  (filter #(= :target (:tag %)) elements)
          task-elements    (remove #(= :target (:tag %)) elements)
          implicit         (doto (Target.)
                             (.setName "")
                             (.setProject project))
          _                (.addOrReplaceTarget project implicit)
          ;; Register named targets.
          named-tgts       (mapv (fn [n]
                                   (let [{:keys [attrs children]} n
                                         name     (require-target-name! n)
                                         t        (doto (Target.)
                                                    (.setName name)
                                                    (.setProject project)
                                                    (.setDescription (:description attrs)))
                                         deps     (:depends attrs)
                                         deps-str (cond
                                                    (nil? deps) nil
                                                    (sequential? deps)
                                                    (clojure.string/join ","
                                                                         (map clojure.core/name deps))
                                                    :else (str deps))]
                                     (when deps-str (.setDepends t deps-str))
                                     (when-some [v (:if attrs)] (.setIf t (str v)))
                                     (when-some [v (:unless attrs)] (.setUnless t (str v)))
                                     (.addOrReplaceTarget project t)
                                     (when-some [ctx *execute-ctx*]
                                       (swap! (:targets-added ctx) conj (.getName t)))
                                     (doseq [c children]
                                       (.addTask t (->unknown-element c project t)))
                                     t))
                                 target-elements)
          ues              (mapv #(->unknown-element % project implicit) task-elements)
          _                (doseq [^UnknownElement ue ues] (.addTask implicit ue))
          ;; Pick what to actually run. Tasks at the top level of a
          ;; build.xml -- <property>, <typedef>, <import> -- live on
          ;; the implicit unnamed target. They have to run BEFORE any
          ;; named target so subsequent targets see their effects.
          explicit-targets (or (:targets opts)
                               (when (seq named-tgts)
                                 [(or (:default opts)
                                      (.getName ^Target (first named-tgts)))]))
          targets-to-run   (cond
                             (and (seq ues) (seq explicit-targets))
                             (cons "" explicit-targets)
                             (seq explicit-targets) explicit-targets
                             :else [""])]
      (when rec (.addBuildListener project rec))
      (when-some [d (:default opts)] (.setDefault project (str d)))
      (.fireBuildStarted project)
      (let [error (try
                    (let [v (java.util.Vector.)]
                      (doseq [t targets-to-run] (.add v (str t)))
                      (.executeTargets project v))
                    nil
                    (catch Throwable t
                      ;; Surface enough context to figure out which
                      ;; element blew up. Ant's BuildException knows
                      ;; the Location and message but not the Clojure
                      ;; tree; we hold the input. Wrap so the user
                      ;; sees both, while keeping the original
                      ;; throwable as :cause for full stack access.
                      (let [root (loop [e t]
                                   (if-some [c (.getCause e)] (recur c) e))]
                        (ex-info (str "Ant build failed: "
                                      (or (.getMessage t) (.getMessage root)))
                                 {:clj-ant/error       true
                                  :clj-ant/elements    elements
                                  :clj-ant/targets     (vec targets-to-run)
                                  :ant/exception-class (.getName (class root))
                                  :ant/message         (.getMessage root)}
                                 t))))]
        (try
          (.fireBuildFinished project error)
          (cond-> {:project project
                   :target  implicit
                   :targets named-tgts
                   :tasks   ues}
                  events (assoc :events @events)
                  (some? error) (assoc :error error))
          ;; Detach everything we attached to the project during this
          ;; call -- listener, inline-task definitions, synthetic refids
          ;; we minted for JavaChild, and named targets we addOrReplace'd.
          ;; Without this, with-session reuse would grow the project's
          ;; references and target tables monotonically across calls.
          (finally
            (when rec (.removeBuildListener project rec))
            (doseq [{:keys [tag]} inline-tasks]
              (let [n (clojure.core/name tag)]
                (.remove ClojureTask/REGISTRY n)
                (if-some [prior (get prior-defs n)]
                  (.addTaskDefinition project n prior)
                  (.remove (.getTaskDefinitions project) n))))
            (doseq [ref-id @(:refs-added *execute-ctx*)]
              (.. project getReferences (remove ref-id)))
            (doseq [tn @(:targets-added *execute-ctx*)]
              ;; Skip the implicit unnamed target -- it's just a
              ;; convenient anchor for top-level tasks; keeping it on
              ;; the project across calls is harmless and avoids
              ;; constant rebuilding.
              (when (not= "" tn)
                (.. project getTargets (remove tn))))))))))

;; ---------------------------------------------------------------------------
;; Targets
;;
;; A target element carries a name, depends list, optional description,
;; if/unless, and a sequence of child task elements. The runner above
;; turns these into real Ant Target instances.

(defn target
  "Build a target element. The name attribute is required.

      (target :name \"compile\"
              :depends [:clean]
              :description \"build the jar\"
              (mkdir :dir \"classes\")
              (javac :srcdir \"src\" :destdir \"classes\"))"
  [& args]
  (let [e (apply element :target args)]
    (require-target-name! e)
    e))

(defmacro deftarget
  "def a target element bound to `nm`, with :name set from the symbol's
  name. Body is the same shape as `target` minus the :name attribute.

      (deftarget compile
        :depends [:clean]
        (mkdir :dir \"classes\")
        (javac :srcdir \"src\" :destdir \"classes\"))

  Then run with:

      (a/ant :targets [\"compile\"] compile clean)
      (a/ant compile clean)              ; runs `compile` since it is first"
  [nm & body]
  `(def ~nm
     (target :name ~(name nm) ~@body)))

;; ---------------------------------------------------------------------------
;; prepare / run -- compile/execute split for tight loops
;;
;; `execute!` does several project-independent walks every call:
;;   coerce children, validate, collect inline tasks, partition
;;   target vs task. None of that depends on the live Project, so
;;   it can run once up-front and the result can be replayed cheaply
;;   against a (typically reused) session.

(defrecord ^:no-doc Plan [elements opts])

(defn- ^:no-doc deep-coerce
  "Walk an element tree, applying `as-child` to every child. The
  result is a tree where every child is already an Element /
  JavaChild -- nothing for `->unknown-element` to coerce on the
  hot path."
  [x]
  (cond
    (or (element? x) (and (map? x) (contains? x :tag)))
    (assoc x :children (mapv #(deep-coerce (as-child %)) (:children x)))
    :else x))

(defn prepare
  "Coerce, validate, and freeze an element tree. Returns a Plan
  ready to be `run` against a session many times. Combine with
  `with-session` for the lowest steady-state cost in tight REPL
  loops:

      (a/with-session [s {:level :info}]
        (let [p (a/prepare elements :validate? true)]
          (dotimes [_ 100] (a/run p :session s))))

  The big perf win is session reuse; `prepare` is the smaller
  additional optimisation on top, removing per-call coercion +
  validation walks."
  [elements & {:as opts}]
  (let [es      (cond
                  (element? elements) [elements]
                  (sequential? elements) (vec elements)
                  (map? elements) [elements]
                  :else (throw (ex-info "prepare expects an element or seq of elements"
                                        {:value elements})))
        coerced (mapv deep-coerce es)]
    (when (:validate? opts)
      (let [vt   (requiring-resolve 'clj-ant.spec/validate-tree)
            vopt (select-keys opts [:closed?])
            errs (mapcat #(vt % vopt) coerced)]
        (when (seq errs)
          (throw (ex-info (str "Validation failed: " (count errs)
                               " issue(s)")
                          {:errors (vec errs)})))))
    (->Plan coerced (assoc opts :validate? false :clj-ant/prepared? true))))

(defn lint
  "Validate an element tree without running Ant. Returns a vector of issue
  maps from `clj-ant.spec/validate-tree`; empty means no issues were found.

  Unlike `execute! :validate? true`, lint defaults to `:closed? true` so it
  catches misspelled attributes and can include `:suggestions`:

      (lint (element :copy :tdoir \"out\"))
      => [{:tag :copy
           :path []
           :errors {:tdoir [\"disallowed key\"]}
           :suggestions {:tdoir [:todir]}}]"
  [elements & {:as opts}]
  (let [es    (cond
                (element? elements) [elements]
                (sequential? elements) (vec elements)
                (map? elements) [elements]
                :else (throw (ex-info "lint expects an element or seq of elements"
                                      {:value elements})))
        vt    (requiring-resolve 'clj-ant.spec/validate-tree)
        opts* (merge {:closed? true} opts)]
    (vec (mapcat #(vt (deep-coerce %) opts*) es))))

(defn explain
  "Alias for `lint`."
  [elements & opts]
  (apply lint elements opts))

(defn run
  "Execute a previously-`prepare`d Plan. Re-runs are cheap when paired
  with `with-session`."
  [^Plan plan & {:as opts}]
  (apply execute! (:elements plan)
         (mapcat identity (merge (:opts plan) opts))))

;; ---------------------------------------------------------------------------
;; Async + cancellation
;;
;; `execute!` is synchronous -- handy for scripts, awkward for long
;; builds where the caller wants to do something else while it runs
;; or cancel mid-flight. `execute-async!` puts the build on a
;; dedicated thread, returns a Run handle that's IDeref-able for the
;; result and `cancel!`-able for abort. Cancel uses Thread.interrupt;
;; Ant's IO-bound tasks (scp/get/sshexec) honour it cleanly,
;; CPU-bound ones don't always -- same caveat as any JVM cancel.

;; A Run is a thin wrapper over a clojure.core/promise. The promise
;; holds the result map; we delegate IDeref / IBlockingDeref /
;; IPending straight to it so callers use it like any other
;; promise/future-like value (`@run`, `(deref run ms tv)`,
;; `(realized? run)`). The other two fields are bookkeeping for
;; `cancel!` -- a promise carrying the build thread (so we can
;; .interrupt it) and an atom flagging caller intent.

(deftype ^:no-doc Run [^clojure.lang.IDeref result
                       ^clojure.lang.IDeref thread
                       ^clojure.lang.Atom cancelled?]
  clojure.lang.IDeref
  (deref [_] @result)
  clojure.lang.IBlockingDeref
  (deref [_ ms timeout-val] (deref result ms timeout-val))
  clojure.lang.IPending
  (isRealized [_] (realized? result)))

(defn execute-async!
  "Run `nodes` on a daemon background thread. Returns a Run handle
  that behaves like a `promise`/`future`:

      @run                   ; blocks for the result map
      (deref run ms timeout) ; bounded wait, returns timeout-val
      (realized? run)        ; true once finished/cancelled/failed
      (cancel! run)          ; interrupts the build thread

  All the options accepted by `execute!` work here too -- including
  `:on-event` for streaming events while the build runs.

  Implementation note: just `clojure.core/promise` + a daemon
  `Thread`. No `Executors`, no `Future.cancel` to interact with.
  The thread is daemon so a stray async run does not pin the JVM
  open after `main` returns -- a real bug in scripts, hard to
  spot otherwise."
  [nodes & {:as opts}]
  (let [result     (promise)
        thread-p   (promise)
        cancelled? (atom false)
        body       (fn []
                     (deliver thread-p (Thread/currentThread))
                     (deliver result
                              (try
                                ;; Annotate caller intent on the result.
                                ;; Ant tasks vary in honouring interrupts:
                                ;; <get>/<scp>/<sshexec> abort cleanly,
                                ;; <sleep> swallows the interrupt and the
                                ;; build finishes normally. Either way the
                                ;; user asked for cancel, so :cancelled?
                                ;; should be on the result map.
                                (let [r (apply execute! nodes
                                               (mapcat identity opts))]
                                  (cond-> r @cancelled? (assoc :cancelled? true)))
                                (catch InterruptedException _
                                  (reset! cancelled? true)
                                  {:cancelled? true})
                                (catch Throwable t
                                  (if @cancelled?
                                    {:cancelled? true :error t}
                                    {:error t})))))]
    (doto (Thread. ^Runnable body "clj-ant-async")
      (.setDaemon true)
      (.start))
    (->Run result thread-p cancelled?)))

(defn cancel!
  "Cancel a Run started via `execute-async!` -- sets the cancelled
  flag and `Thread.interrupt`s the build thread. IO tasks (scp,
  get, sshexec) abort cleanly; CPU-bound tasks (large copies,
  javac) often don't observe the interrupt and run to completion.
  Either way, `:cancelled? true` is on the eventual result map.
  Returns the Run."
  [^Run run]
  (reset! (.-cancelled? run) true)
  (when-some [^Thread t (deref (.-thread run) 0 nil)]
    (.interrupt t))
  run)

(defn cancelled?
  "True once `cancel!` has been called on this Run (regardless of
  whether the build itself observed the interrupt)."
  [^Run run]
  @(.-cancelled? run))

;; ---------------------------------------------------------------------------
;; Watch mode -- re-run a plan when files under `paths` change.
;; Polling-based: portable across Linux / macOS / Windows without
;; platform-specific quirks (macOS WatchService is broken for
;; recursive watches; inotify on Linux has limits). 500 ms poll is
;; cheap and lets us reuse the same as-child / file-walk machinery
;; the rest of the lib uses.

(defn- ^:no-doc dir-snapshot
  "Map of file path -> mtime for every regular file under `paths`."
  [paths]
  (into {}
        (for [^String p paths
              ^File f   (file-seq (io/file p))
              :when (.isFile f)]
          [(.getAbsolutePath f) (.lastModified f)])))

(defn watch
  "Run `nodes` once, then re-run whenever any file under `:paths`
  changes (added / modified / removed). Returns a 0-arg `stop!` fn.

      (def stop (a/watch [(t/javac :srcdir \"src\" :destdir \"out\")]
                          :paths   [\"src\"]
                          :poll-ms 300))
      ;; ...edit, save, see rebuild on stdout...
      (stop)

  Options:
    :paths         coll of dir paths to watch (required)
    :poll-ms       polling interval, default 500
    :on-rebuild    fn called with the (cleaned) result map after
                   each rebuild. Defaults to printing :error if any.
    :on-event      forwarded to execute! for live event streaming.
    :session       reuse a session across rebuilds (recommended --
                   amortises Project init across every rerun).

  In-flight builds aren't cancelled when a new change arrives;
  the watcher waits for the current build to finish before starting
  the next. (Cancellation mid-build is supported via `execute-async!`
  + `cancel!`; combining the two is left to the caller for now.)"
  [nodes & {:keys [paths poll-ms on-rebuild]
            :or   {poll-ms    500
                   on-rebuild #(when-some [e (:error %)]
                                 (println "watch: error:"
                                          (or (when (instance? Throwable e)
                                                (.getMessage ^Throwable e))
                                              e)))}
            :as   opts}]
  (when-not (seq paths)
    (throw (ex-info "watch: :paths is required" {})))
  (let [stopped? (atom false)
        run-opts (mapcat identity (dissoc opts :paths :poll-ms :on-rebuild))
        run!     (fn []
                   (try
                     (on-rebuild (apply execute! nodes run-opts))
                     (catch Throwable t
                       (on-rebuild {:error t}))))
        loop!    (future
                   (run!)
                   (loop [prev (dir-snapshot paths)]
                     (when-not @stopped?
                       (Thread/sleep poll-ms)
                       (let [cur (dir-snapshot paths)]
                         (when (not= prev cur)
                           (run!))
                         (recur cur)))))]
    (fn stop! []
      (reset! stopped? true)
      (future-cancel loop!)
      :stopped)))

;; ---------------------------------------------------------------------------
;; Top-level convenience

(defn ant
  "Run a sequence of Ant tasks inline. Each top-level form is added as a
  task of an implicit unnamed target. Options are forwarded to
  `execute!` / `make-project` and may be supplied either as leading
  keyword/value pairs or as one leading map.

  Example:

      (ant
        {:basedir \"out\"}
        (mkdir :dir \"classes\")
        (copy  :todir \"classes\"
          (fileset :dir \"src\" :includes \"**/*.clj\")))

  Note that the task functions like `mkdir`, `copy`, `fileset` live in
  `clj-ant.tasks` (auto-generated). For ad-hoc/unknown elements, use
  `(element :my-tag ...)` from this namespace."
  [& args]
  (let [[opts rest-args]
        (if (and (map? (first args)) (not (contains? (first args) :tag)))
          [(first args) (rest args)]
          (loop [opts {} xs args]
            (if (and (keyword? (first xs)) (not (element? (second xs))))
              (recur (assoc opts (first xs) (second xs)) (drop 2 xs))
              [opts xs])))]
    (apply execute! (vec rest-args) (mapcat identity opts))))

;; ---------------------------------------------------------------------------
;; Streaming resource collections. Most code can just pass a seq of
;; Files directly -- the runner's `as-child` will wrap it for you with
;; safe defaults. Reach for `lazy-resources` when you have a million-
;; entry seq and the default eager `(count files)` for `.size` is too
;; expensive, or when you need to set `isFilesystemOnly` to false for
;; HTTP/zip-entry resources.

(defn lazy-resources
  "Wrap a (possibly lazy/infinite) seq of `java.io.File` / `Resource`
  / path-string as a real Ant `ResourceCollection`. Iteration is on
  demand: the resulting object pulls one `FileResource` at a time
  from the seq, so a 10M-entry input never materialises into 10M
  live objects up front.

  Returns a value you can drop into any task that accepts a resource
  collection (copy, jar, zip, tar, …).

      (a/ant
        (t/copy :todir \"out\"
          (a/lazy-resources (find-millions-of-files)
                            {:size 10000000})))

  Options:
    :size              size hint, returned by ResourceCollection.size().
                       Default: `(count files)` -- forces full
                       realisation. Pass an explicit size to skip
                       the count when you already know it.
    :filesystem-only?  default true. Set false for HTTP / zip /
                       tar / non-filesystem resources."
  ([files] (as-child files))
  ([files {:keys [size filesystem-only?]
           :or   {filesystem-only? true}}]
   (let [->res (fn [x]
                 (cond
                   (instance? Resource x) x
                   (instance? File x)
                   (org.apache.tools.ant.types.resources.FileResource. ^File x)
                   :else
                   (org.apache.tools.ant.types.resources.FileResource.
                     ^File (io/file x))))
         rc    (reify ResourceCollection
                 (iterator [_]
                   (let [s (atom (seq files))]
                     (reify java.util.Iterator
                       (hasNext [_] (boolean (seq @s)))
                       (next [_] (let [v (->res (first @s))]
                                   (swap! s next) v)))))
                 (size [_] (or size (count files)))
                 (isFilesystemOnly [_] (boolean filesystem-only?)))]
     (->JavaChild rc :resources))))

;; ---------------------------------------------------------------------------
;; Realisation: turn data elements into live Ant objects without executing.
;;
;; A few Ant types (FileSet, Path, FileList, Resources, Restrict, ...) are
;; valuable to Clojure code on their own, independent of any task. We let
;; users materialise such a element, ask it for its resources, and feed the
;; result through the normal Clojure seq machinery.

(defn- ^:no-doc has-property-ref?
  "True if any attribute string contains a `${...}` reference. We can
  only take the no-UnknownElement fast path when nothing needs
  property expansion."
  [attrs]
  (boolean (some #(and (string? %) (re-find #"\$\{" %))
                 (vals attrs))))

(defn- ^:no-doc fast-realize
  "Build a real Ant DataType directly via reflection on a fileset-shaped
  element, skipping the UnknownElement / RuntimeConfigurable layer.
  Returns nil when the element shape isn't safely fast-pathable
  (children, ${...} refs, refid, unknown tags) -- caller should fall
  back to `realize`."
  [element ^Project project]
  (when (and (element? element)
             (empty? (:children element))
             (nil? (:text element))
             (not (has-property-ref? (:attrs element)))
             (not (contains? (:attrs element) :refid)))
    (let [n     (name (:tag element))
          klass (or (.get (.getDataTypeDefinitions project) n)
                    (.get (.getTaskDefinitions project) n))]
      (when klass
        (let [obj    (.newInstance ^Class klass)
              helper (IntrospectionHelper/getHelper project klass)]
          (when (instance? ProjectComponent obj)
            (.setProject ^ProjectComponent obj project))
          (doseq [[k v] (:attrs element)]
            (.setAttribute helper project obj
                           (clojure.core/name k) (str v)))
          obj)))))

(defn realize
  "Materialise a element into a live Ant object (Task or DataType) without
  executing it. Property references are expanded; `refid`s resolve;
  nested elements are configured. Returns the underlying Ant object
  (e.g. `org.apache.tools.ant.types.FileSet`).

  Options:
    :project   an existing Project, or nil to create one. Other keys
               are forwarded to `make-project`.
    :fast?     skip the UnknownElement layer when the element is a
               simple top-level type with no children / `${...}` refs
               / refid. Default true. Saves ~25-30 ms per call on
               warm projects -- meaningful for `(a/files ...)` in
               tight loops."
  ^Object [element & {:as opts}]
  (let [project ^Project (or (:project opts) (make-project (or opts {})))]
    (or (when (not= false (:fast? opts))
          (fast-realize element project))
        (let [target (doto (Target.) (.setName "") (.setProject project))
              ue     (->unknown-element element project target)]
          (.maybeConfigure ue)
          (.getRealThing ue)))))

(defn- resource->file ^File [^Resource r]
  (if (instance? FileProvider r)
    (.getFile ^FileProvider r)
    (File. (.getName r))))

(defn resources
  "Lazily realise a resource-collection element and return a seq of
  `org.apache.tools.ant.types.Resource`. Works on any element whose backing
  Ant type implements `ResourceCollection` (fileset, filelist, path,
  files, dirset, restrict, intersect, …)."
  [element & {:as opts}]
  (let [obj (apply realize element (mapcat identity opts))]
    (when-not (instance? ResourceCollection obj)
      (throw (ex-info (str "Not a resource collection: " (:tag element))
                      {:tag (:tag element) :class (class obj)})))
    (iterator-seq (.iterator ^ResourceCollection obj))))

(defn files
  "Lazy seq of `java.io.File` from a resource-collection element. Resources
  that don't resolve to a filesystem path (HTTP, zip entry, …) are
  passed through `clojure.java.io/file` on their name."
  [element & {:as opts}]
  (map resource->file (apply resources element (mapcat identity opts))))

;; ---------------------------------------------------------------------------
;; Datafy: make execution results pleasant to inspect at the REPL.

(extend-protocol p/Datafiable
  Element
  (datafy [n] (into {} n))

  UnknownElement
  (datafy [ue] {:tag   (.getTag ue)
                :line  (.. ue (getLocation) (getLineNumber))
                :class (some-> (.getRealThing ue) class .getName)
                :name  (.getTaskName ue)})

  Resource
  (datafy [r] (cond-> {:name       (.getName r)
                       :exists?    (.isExists r)
                       :size       (.getSize r)
                       :directory? (.isDirectory r)}
                      (instance? FileProvider r)
                      (assoc :file (.getFile ^FileProvider r)))))

;; ---------------------------------------------------------------------------
;; Plan: print a tree of what would execute, without executing.

;; ---------------------------------------------------------------------------
;; Tree querying & transformation
;;
;; Element trees are plain data, so `tree-seq` already handles
;; reading them. These helpers package the two operations every
;; auditor or refactor script wants: walk-and-find, walk-and-rewrite.

(defn- ^:no-doc node-like?
  "Recognises every shape `as-child` produces: Element records,
  JavaChild records, and node-shaped maps (anything with a :tag).
  Vectors of strings, raw seqs of Files etc. are NOT node-like --
  they're leaf payloads inside a parent's :children."
  [x]
  (or (element? x)
      (java-child? x)
      (and (map? x) (contains? x :tag))))

(defn elements
  "Lazy depth-first seq of every element in `tree`.

  Walks Elements, JavaChild wrappers, and node-shaped maps -- the
  same vocabulary `as-child` produces. Non-node payloads (raw
  vectors of File paths, etc.) are leaves and skipped over.

  With `pred`, only nodes matching pred:

      (elements tree #(= :scp (:tag %)))
      (elements tree #(and (= :javac (:tag %))
                           (= \"false\" (-> % :attrs :debug))))"
  ([tree] (filter node-like? (tree-seq node-like? :children tree)))
  ([tree pred] (filter pred (elements tree))))

(defn transform
  "Walk `tree` depth-first, applying `f` to each node. `f` must
  return a node (or nil to drop it). Children are transformed
  before parents see them, so `f` always observes already-rewritten
  descendants.

  Tolerates the full child vocabulary -- Element records, JavaChild
  wrappers, node-shaped maps, and non-node leaves (which pass
  through unchanged).

      ;; lowercase every :todir attribute everywhere in the tree
      (transform tree
                 (fn [n]
                   (cond-> n
                     (-> n :attrs :todir)
                     (update-in [:attrs :todir]
                                clojure.string/lower-case))))"
  [tree f]
  (cond
    (or (element? tree) (and (map? tree) (contains? tree :tag)))
    (let [kids (->> (:children tree)
                    (map #(transform % f))
                    (remove nil?)
                    vec)]
      (f (assoc tree :children kids)))

    (java-child? tree) (f tree)

    :else tree))

;; ---------------------------------------------------------------------------
;; Runtime introspection. Useful for tooling, REPL exploration, and for
;; users to discover what attributes a task accepts without hopping out
;; to the manual.

(defn- ^:no-doc wrapper-meta
  "Lookup generated metadata for a task/type wrapper, if the generated
  namespace is available on the classpath."
  [tag]
  (try
    (when-some [v (requiring-resolve
                    (symbol "clj-ant.tasks" (name tag)))]
      (meta v))
    (catch Throwable _ nil)))

(defn- ^:no-doc attr-keyword [k]
  (keyword (str/lower-case (name k))))

(defn- ^:no-doc attr-record [manual-attrs [k c]]
  (let [{:keys [description required]} (get manual-attrs (attr-keyword k))]
    (cond-> {:type c}
            description (assoc :description description)
            required (assoc :required required))))

(defn- ^:no-doc nested-recorded-classes
  "Lookup every class the generator recorded for a nested-only tag
  (more than one when the tag is context-ambiguous). Returns nil if
  the wrapper isn't there."
  [tag]
  (try
    (when-some [m (wrapper-meta tag)]
      (->> (or (:clj-ant/classes m)
               (when-some [c (:clj-ant/class m)] [c]))
           (keep #(try (Class/forName %) (catch Throwable _ nil)))
           vec
           not-empty))
    (catch Throwable _ nil)))

(def ^:private describe-cache (atom {}))

(defn describe
  "Return a data description of a task or type by tag. Useful for
  building UIs, validators, or just satisfying curiosity at the REPL.

      (describe :copy)
      => {:tag :copy
          :class \"org.apache.tools.ant.taskdefs.Copy\"
          :classes [\"...Copy\"]
          :kind  :task
          :description \"Copies a file or resource collection ...\"
          :manual-url \"https://ant.apache.org/manual/Tasks/copy.html\"
          :attrs {\"todir\" {:type File
                             :description \"The directory to copy to.\"
                             :required \"...\"}
                   \"tofile\" {:type File, ...}
                   ...}
          :nested {:fileset FileSet, ...}
          :text? true|false}

  Works for nested-only tags too (`:attribute`, `:tokenfilter`,
  `:replacestring`, ...). For ambiguous tags (`:attribute` is
  MacroDef$Attribute under <macrodef> and Manifest$Attribute under
  <manifest>) the result merges every observed class:

    :class    -> the canonical class (first one seen by the generator)
    :classes  -> every recorded class
    :attrs    -> union across all classes
    :nested   -> union across all classes"
  [tag]
  (let [cache-key (keyword (name tag))]
    (or (get @describe-cache cache-key)
        (when-some [d
                    (let [project      (Project.) _ (.init project)
                          n            (name tag)
                          wm           (wrapper-meta tag)
                          manual-attrs (:clj-ant/attrs wm)
                          top-kind     (cond
                                         (.containsKey (.getTaskDefinitions project) n) :task
                                         (.containsKey (.getDataTypeDefinitions project) n) :type)
                          top-klass    (case top-kind
                                         :task (.get (.getTaskDefinitions project) n)
                                         :type (.get (.getDataTypeDefinitions project) n)
                                         nil)
                          klasses      (or (when top-klass [top-klass])
                                           (nested-recorded-classes tag))
                          kind         (or top-kind (when (seq klasses) :nested))]
                      (when (seq klasses)
                        (let [helpers (mapv #(IntrospectionHelper/getHelper project %) klasses)
                              ;; Merge attrs and nested across all classes. For ambiguous
                              ;; tags this lists every key any of the classes accepts;
                              ;; the runtime-effective set still depends on parent.
                              merged  (fn [getter]
                                        (->> helpers
                                             (mapcat #(seq (getter %)))
                                             (map (fn [^java.util.Map$Entry e]
                                                    [(.getKey e) (.getValue e)]))
                                             ;; first-occurrence wins on conflicts
                                             (reduce (fn [acc [k v]]
                                                       (if (contains? acc k) acc (assoc acc k v)))
                                                     (sorted-map))))]
                          (cond-> {:tag     (keyword n)
                                   :class   (.getName ^Class (first klasses))
                                   :classes (mapv #(.getName ^Class %) klasses)
                                   :kind    kind
                                   :attrs   (into (sorted-map)
                                                  (map (fn [entry]
                                                         [(key entry) (attr-record manual-attrs entry)]))
                                                  (merged #(.getAttributeMap %)))
                                   :nested  (merged #(.getNestedElementMap %))
                                   :text?   (boolean (some #(.supportsCharacters %) helpers))}
                                  (:clj-ant/description wm) (assoc :description (:clj-ant/description wm))
                                  (:clj-ant/manual-url wm) (assoc :manual-url (:clj-ant/manual-url wm))))))]
          (swap! describe-cache assoc cache-key d)
          d))))

(defn plan
  "Pretty-print a element tree to *out*. Useful for sanity-checking a
  tree before running it. Returns the element unchanged so it can be
  threaded into `ant` / `execute!`."
  ([element] (plan element 0) element)
  ([element depth]
   (let [pad (apply str (repeat (* 2 depth) \space))]
     (println (str pad "<" (name (:tag element))
                   (apply str
                          (for [[k v] (:attrs element)]
                            (str " " (name k) "=" (pr-str (str v)))))
                   (if (or (seq (:children element)) (:text element)) ">" "/>")))
     (when-some [t (:text element)]
       (println (str pad "  " t)))
     (doseq [c (:children element)]
       (plan c (inc depth)))
     (when (or (seq (:children element)) (:text element))
       (println (str pad "</" (name (:tag element)) ">"))))
   element))
