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
            [clojure.core.protocols :as p])
  (:import [org.apache.tools.ant Project Target Location IntrospectionHelper
                                 UnknownElement RuntimeConfigurable
                                 DefaultLogger BuildListener BuildEvent]
           [org.apache.tools.ant.types Resource ResourceCollection]
           [org.apache.tools.ant.types.resources FileProvider]
           [java.io File PrintStream]))

;; ---------------------------------------------------------------------------
;; Element construction
;;
;; Tasks and types are nothing but functions that return a map. The map is
;; opaque data — in particular, calling (copy ...) does NOT trigger any Ant
;; activity. That only happens once the tree is handed to `run!`.

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
  (let [->res (fn [x]
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
            (next    [_] (let [v (->res (first @s))]
                           (swap! s next) v)))))
      (size [_] @size-cache)
      (isFilesystemOnly [_] true))))

(defn as-child
  "Coerce `x` to a Element or JavaChild so it can be a child of an Ant
  element. The rule is:

    Element / JavaChild     -> as is
    map (element-shaped)    -> as is
    org.apache.tools.ant.types.ResourceCollection
                         -> JavaChild (refid proxy at execute time)
    File / Resource      -> single-element ResourceCollection
    seq of the above     -> lazy ResourceCollection over the seq

  You don't usually call this yourself -- `element` (and the generated
  task wrappers) call it on every positional arg. So this works
  out of the box:

      (a/ant (t/copy :todir \"out\" my-fileset))      ; FileSet
      (a/ant (t/copy :todir \"out\" (find-files)))    ; lazy seq of File
      (a/ant (t/copy :todir \"out\" (io/file \"x\"))) ; one File"
  [x]
  (cond
    (or (element? x) (java-child? x))    x
    (map? x)                          x
    (instance? ResourceCollection x)  (->JavaChild x :resources)
    (or (instance? File x)
        (instance? Resource x))       (->JavaChild (build-lazy-rc [x]) :resources)
    (sequential? x)                   (->JavaChild (build-lazy-rc x) :resources)
    :else (throw (ex-info (str "Cannot use as child: " (pr-str x))
                          {:value x :type (class x)}))))

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
  * sequential collections splice their contents in as children

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
        :error   Project/MSG_ERR
        :warn    Project/MSG_WARN
        :info    Project/MSG_INFO
        :verbose Project/MSG_VERBOSE
        :debug   Project/MSG_DEBUG))
    (.setOutputPrintStream  ^PrintStream out)
    (.setErrorPrintStream   ^PrintStream err)
    (.setEmacsMode (boolean emacs?))))

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
    p))

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
    (string? v)     v
    (keyword? v)    (name v)
    (symbol? v)     (name v)
    (sequential? v) (clojure.string/join ","
                                         (map #(cond
                                                 (keyword? %) (name %)
                                                 (symbol? %)  (name %)
                                                 :else        (str %))
                                              v))
    :else           (str v)))

;; Per-project monotonic counter for synthetic reference ids. Reset
;; per execution (each `execute!` makes its own Project). Using a counter
;; instead of identityHashCode avoids collisions when the user
;; re-injects the same object across many children.
(defn- next-ref-id! [^Project project]
  (let [k "_clj-ant.ref-counter"
        n (or (.getReference project k) (atom 0))]
    (when-not (.getReference project k)
      (.addReference project k n))
    (str "_clj-ant.ref-" (swap! n inc))))

(defn- java-child->ue
  ^UnknownElement [^JavaChild jc ^Project project ^Target target]
  (let [{:keys [object tag]} jc
        ref-id  (next-ref-id! project)
        _       (.addReference project ref-id object)
        tag-name (name (or tag :resources))
        ue      (doto (UnknownElement. tag-name)
                  (.setProject project)
                  (.setOwningTarget target)
                  (.setQName tag-name)
                  (.setTaskName tag-name)
                  (.setLocation Location/UNKNOWN_LOCATION))
        wrap    (doto (RuntimeConfigurable. ue tag-name)
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
      (doseq [c children]
        (let [child (->unknown-element c project target)]
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
  (let [elements   (cond
                  (element? elements)       [elements]
                  (sequential? elements) (vec elements)
                  (map? elements)        [elements]
                  :else (throw (ex-info "execute! expects an element or seq of elements"
                                        {:value elements})))
        _       (when (:validate? opts)
                  (let [vt   (requiring-resolve 'clj-ant.spec/validate-tree)
                        vopt (select-keys opts [:closed?])
                        errs (mapcat #(vt % vopt) elements)]
                    (when (seq errs)
                      (throw (ex-info
                               (str "Validation failed: " (count errs)
                                    " issue(s)")
                               {:errors (vec errs)})))))
        project ^Project (or (:project opts) (make-project opts))
        events  (when (:capture? opts) (atom []))
        on-event (:on-event opts)
        emit    (fn [m]
                  (when events   (swap! events conj m))
                  (when on-event (on-event m))
                  nil)
        rec     (when (or events on-event)
                  (reify BuildListener
                    (buildStarted   [_ _] (emit {:phase :started}))
                    (buildFinished  [_ e]
                      (emit {:phase :finished
                             :error (some-> ^BuildEvent e .getException
                                            .getMessage)}))
                    (targetStarted  [_ e]
                      (emit {:phase :target-started
                             :target (some-> ^BuildEvent e .getTarget .getName)}))
                    (targetFinished [_ e]
                      (emit {:phase :target-finished
                             :target (some-> ^BuildEvent e .getTarget .getName)
                             :error (some-> ^BuildEvent e .getException
                                             .getMessage)}))
                    (taskStarted    [_ e]
                      (emit {:phase :task-started
                             :task  (some-> ^BuildEvent e .getTask .getTaskName)}))
                    (taskFinished   [_ e]
                      (emit {:phase :task-finished
                             :task  (some-> ^BuildEvent e .getTask .getTaskName)
                             :error (some-> ^BuildEvent e .getException
                                             .getMessage)}))
                    (messageLogged  [_ e]
                      (emit {:phase   :message
                             :message (.getMessage  ^BuildEvent e)
                             :level   (.getPriority ^BuildEvent e)}))))
        ;; Split target elements from regular task/type elements. Targets get
        ;; their own Target instance + addOrReplaceTarget; regular elements
        ;; go onto an implicit unnamed target that runs by default.
        target-elements (filter #(= :target (:tag %)) elements)
        task-elements   (remove #(= :target (:tag %)) elements)
        implicit  (doto (Target.)
                    (.setName "")
                    (.setProject project))
        _         (.addOrReplaceTarget project implicit)
        ;; Register named targets.
        named-tgts (mapv (fn [n]
                           (let [{:keys [attrs children]} n
                                 t (doto (Target.)
                                     (.setName        (str (:name attrs)))
                                     (.setProject     project)
                                     (.setDescription (:description attrs)))
                                 deps (:depends attrs)
                                 deps-str (cond
                                            (nil? deps) nil
                                            (sequential? deps)
                                            (clojure.string/join ","
                                                                 (map clojure.core/name deps))
                                            :else (str deps))]
                             (when deps-str (.setDepends t deps-str))
                             (when-some [v (:if attrs)]     (.setIf t (str v)))
                             (when-some [v (:unless attrs)] (.setUnless t (str v)))
                             (.addOrReplaceTarget project t)
                             (doseq [c children]
                               (.addTask t (->unknown-element c project t)))
                             t))
                         target-elements)
        ues       (mapv #(->unknown-element % project implicit) task-elements)
        _         (doseq [^UnknownElement ue ues] (.addTask implicit ue))
        ;; Pick what to actually run.
        targets-to-run (or (:targets opts)
                           (if (seq named-tgts)
                             ;; If user defined targets but didn't pick any,
                             ;; honour the project's default if given, else
                             ;; just run the first declared target.
                             [(or (:default opts)
                                  (.getName ^Target (first named-tgts)))]
                             [""]))]
    (when rec (.addBuildListener project rec))
    (when-some [d (:default opts)] (.setDefault project (str d)))
    (.fireBuildStarted project)
    (let [error (try
                  (let [v (java.util.Vector.)]
                    (doseq [t targets-to-run] (.add v (str t)))
                    (.executeTargets project v))
                  nil
                  (catch Throwable t t))]
      (.fireBuildFinished project error)
      (cond-> {:project project
               :target  implicit
               :targets named-tgts
               :tasks   ues}
        events       (assoc :events @events)
        (some? error) (assoc :error error)))))

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
  (apply element :target args))

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
;; Top-level convenience

(defn ant
  "Run a sequence of Ant tasks inline. Each top-level form is added as a
  task of an implicit unnamed target. Keyword options must come first
  and are forwarded to `execute!` / `make-project`.

  Example:

      (ant
        :basedir \"out\"
        (mkdir :dir \"classes\")
        (copy  :todir \"classes\"
          (fileset :dir \"src\" :includes \"**/*.clj\")))

  Note that the task functions like `mkdir`, `copy`, `fileset` live in
  `clj-ant.tasks` (auto-generated). For ad-hoc/unknown elements, use
  `(element :my-tag ...)` from this namespace."
  [& args]
  (let [[opts rest-args]
        (loop [opts {} xs args]
          (if (and (keyword? (first xs)) (not (element? (second xs))))
            (recur (assoc opts (first xs) (second xs)) (drop 2 xs))
            [opts xs]))]
    (apply execute! (vec rest-args) (mapcat identity opts))))

;; ---------------------------------------------------------------------------
;; Advanced child-injection knobs. The everyday path is to just pass a
;; value to a element -- the runner calls `as-child` and figures out what
;; to do. These helpers are kept around for the rare case where you
;; need to override the default behaviour (e.g. a non-filesystem-only
;; resource collection, or a hand-rolled Resources). They are not
;; documented in the README on purpose.

(defn ^:no-doc lazy-resources
  "Like passing a seq of files directly, but lets you override the
  size hint and the isFilesystemOnly flag. Returns a JavaChild ready
  to drop into a element.

  Options:
    :size              skip the eager (count files) and use this.
    :filesystem-only?  default true. Set false for HTTP/zip/etc."
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
         rc (reify ResourceCollection
              (iterator [_]
                (let [s (atom (seq files))]
                  (reify java.util.Iterator
                    (hasNext [_] (boolean (seq @s)))
                    (next    [_] (let [v (->res (first @s))]
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

(defn realize
  "Materialise a element into a live Ant object (Task or DataType) without
  executing it. Property references are expanded; `refid`s resolve;
  nested elements are configured. Returns the underlying Ant object
  (e.g. `org.apache.tools.ant.types.FileSet`).

  Options:
    :project   an existing Project, or nil to create one. Other keys
               are forwarded to `make-project`."
  ^Object [element & {:as opts}]
  (let [project ^Project (or (:project opts) (make-project (or opts {})))
        target  (doto (Target.) (.setName "") (.setProject project))
        ue      (->unknown-element element project target)]
    (.maybeConfigure ue)
    (.getRealThing ue)))

(defn- ^File resource->file [^Resource r]
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
  (datafy [ue] {:tag    (.getTag ue)
                :line   (.. ue (getLocation) (getLineNumber))
                :class  (some-> (.getRealThing ue) class .getName)
                :name   (.getTaskName ue)})

  Resource
  (datafy [r] (cond-> {:name (.getName r)
                       :exists? (.isExists r)
                       :size (.getSize r)
                       :directory? (.isDirectory r)}
                (instance? FileProvider r)
                (assoc :file (.getFile ^FileProvider r)))))

;; ---------------------------------------------------------------------------
;; Plan: print a tree of what would execute, without executing.

;; ---------------------------------------------------------------------------
;; Runtime introspection. Useful for tooling, REPL exploration, and for
;; users to discover what attributes a task accepts without hopping out
;; to the manual.

(defn- entries-of [^java.util.Map m]
  (into (sorted-map)
        (map (fn [^java.util.Map$Entry e] [(.getKey e) (.getValue e)]))
        m))

(defn describe
  "Return a data description of a task or type by tag. Useful for
  building UIs, validators, or just satisfying curiosity at the REPL.

      (describe :copy)
      => {:tag :copy
          :class \"org.apache.tools.ant.taskdefs.Copy\"
          :kind  :task
          :attrs {:todir File, :tofile File, ...}
          :nested {:fileset FileSet, ...}
          :text? true|false}"
  [tag]
  (let [project (Project.) _ (.init project)
        n       (name tag)
        kind    (cond
                  (.containsKey (.getTaskDefinitions project) n)     :task
                  (.containsKey (.getDataTypeDefinitions project) n) :type)
        klass   (case kind
                  :task (.get (.getTaskDefinitions project) n)
                  :type (.get (.getDataTypeDefinitions project) n)
                  nil)]
    (when klass
      (let [helper (IntrospectionHelper/getHelper project klass)]
        {:tag    (keyword n)
         :class  (.getName klass)
         :kind   kind
         :attrs  (entries-of (.getAttributeMap helper))
         :nested (entries-of (.getNestedElementMap helper))
         :text?  (.supportsCharacters helper)}))))

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
