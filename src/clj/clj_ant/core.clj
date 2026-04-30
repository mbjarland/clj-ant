(ns clj-ant.core
  "Fluent Ant from Clojure.

  This namespace exposes a single mental model: an Ant build is a tree
  of plain Clojure data. Each node is a map of the form

      {:tag :copy
       :attrs {:todir \"out\"}
       :children [...]
       :text \"...\"}

  and is constructed by calling the corresponding task or type
  function, e.g.

      (copy :todir \"out\"
        (fileset :dir \"src\" :includes \"**/*.clj\"))

  Nothing executes until the tree is passed to `run!`. `run!` builds
  Ant's own intermediate representation (`UnknownElement` +
  `RuntimeConfigurable`) directly — the same tree Ant's XML parser
  would build — and asks Ant to execute it. This means property
  expansion (`${foo}`), `refid`, `macrodef`, `presetdef`, `if`/`unless`
  attributes, etc. all work for free, because they are implemented
  inside Ant's runtime configuration machinery, not its parser."
  (:require [clojure.java.io :as io]
            [clojure.core.protocols :as p])
  (:import [org.apache.tools.ant Project Target Location IntrospectionHelper
                                 UnknownElement RuntimeConfigurable
                                 DefaultLogger BuildListener BuildEvent]
           [org.apache.tools.ant.types Resource ResourceCollection]
           [org.apache.tools.ant.types.resources FileProvider]
           [java.io File PrintStream]))

;; ---------------------------------------------------------------------------
;; Node construction
;;
;; Tasks and types are nothing but functions that return a map. The map is
;; opaque data — in particular, calling (copy ...) does NOT trigger any Ant
;; activity. That only happens once the tree is handed to `run!`.

(defrecord Node [tag attrs children text])

(defn node?
  "Truthy if `x` is a clj-ant node."
  [x]
  (instance? Node x))

(defn- split-args
  "Pulls keyword/value attribute pairs off the front of a positional arg
  list, leaving anything else as children. Mirrors hiccup-style calling
  conventions while keeping the data form a plain map."
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

        (or (node? x) (map? x))
        (recur attrs text (conj children x) (rest xs))

        (sequential? x)
        (recur attrs text (into children x) (rest xs))

        (nil? x)
        (recur attrs text children (rest xs))

        :else
        (throw (ex-info (str "Unsupported child of an Ant node: " (pr-str x))
                        {:value x}))))))

(defn node
  "Build a clj-ant node for tag `tag-kw`. The remaining args follow
  hiccup-ish positional rules:

  * keyword/value pairs are attributes
  * strings concatenate into the element's text body
  * maps or other nodes become children
  * sequential collections splice their contents in as children

  Returned value is a `Node` record — a plain map you can inspect,
  walk, diff, transform with `update`, store in an atom, etc."
  [tag-kw & args]
  (let [[attrs text children] (split-args args)]
    (->Node tag-kw attrs (vec children) text)))

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
;; nodes here.

(defn- ->unknown-element
  ^UnknownElement [{:keys [tag attrs children text]}
                   ^Project project ^Target target]
  (let [tag-name (name tag)
        ue       (doto (UnknownElement. tag-name)
                   (.setProject project)
                   (.setOwningTarget target)
                   (.setQName tag-name)
                   (.setTaskName tag-name)
                   (.setLocation Location/UNKNOWN_LOCATION))
        wrap     (RuntimeConfigurable. ue tag-name)]
    (doseq [[k v] attrs]
      (.setAttribute wrap (name k) (str v)))
    (when text
      (.addText wrap (str text)))
    (doseq [c children]
      (let [child (->unknown-element c project target)]
        (.addChild ue child)
        (.addChild wrap (.getWrapper child))))
    ;; Task.setRuntimeConfigurableWrapper is public; this is the same
    ;; hook ProjectHelper2 uses when assembling the AST from XML.
    (.setRuntimeConfigurableWrapper ue wrap)
    ue))

;; ---------------------------------------------------------------------------
;; Execution

(defn execute!
  "Execute one or more nodes against a fresh (or supplied) project.

  Top-level forms become tasks of an implicit unnamed target which is
  then executed. Returns a map containing the project, the target, the
  list of UnknownElements that ran, plus any captured build events.

  Options:

    :project    An existing org.apache.tools.ant.Project. If absent, a
                fresh one is built from the same options.
    :capture?   If true, attach a recording BuildListener and return
                the captured events under :events. Default false.

  Plus any options accepted by `make-project`."
  [nodes & {:as opts}]
  (let [nodes   (cond
                  (node? nodes)       [nodes]
                  (sequential? nodes) (vec nodes)
                  (map? nodes)        [nodes]
                  :else (throw (ex-info "execute! expects a node or seq of nodes"
                                        {:value nodes})))
        project ^Project (or (:project opts) (make-project opts))
        events  (when (:capture? opts) (atom []))
        rec     (when events
                  (reify BuildListener
                    (buildStarted   [_ _] nil)
                    (buildFinished  [_ _] nil)
                    (targetStarted  [_ _] nil)
                    (targetFinished [_ _] nil)
                    (taskStarted    [_ e]
                      (swap! events conj
                             {:phase :task-started
                              :task  (some-> ^BuildEvent e .getTask .getTaskName)})
                      nil)
                    (taskFinished   [_ e]
                      (swap! events conj
                             {:phase :task-finished
                              :task  (some-> ^BuildEvent e .getTask .getTaskName)
                              :error (.getException ^BuildEvent e)})
                      nil)
                    (messageLogged  [_ e]
                      (swap! events conj
                             {:phase   :message
                              :message (.getMessage  ^BuildEvent e)
                              :level   (.getPriority ^BuildEvent e)})
                      nil)))
        target  (doto (Target.)
                  (.setName "")
                  (.setProject project))
        _       (.addOrReplaceTarget project target)
        ues     (mapv #(->unknown-element % project target) nodes)]
    (when rec (.addBuildListener project rec))
    (doseq [^UnknownElement ue ues]
      (.addTask target ue))
    (.fireBuildStarted project)
    (let [error (try
                  (.executeTarget project "")
                  nil
                  (catch Throwable t t))]
      (.fireBuildFinished project error)
      (cond-> {:project project
               :target  target
               :tasks   ues}
        events       (assoc :events @events)
        (some? error) (assoc :error error)))))

;; ---------------------------------------------------------------------------
;; Top-level convenience

(defn ant
  "Run an Ant build defined inline. Each top-level form is added as a
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
  `(node :my-tag ...)` from this namespace."
  [& args]
  (let [[opts rest-args]
        (loop [opts {} xs args]
          (if (and (keyword? (first xs)) (not (node? (second xs))))
            (recur (assoc opts (first xs) (second xs)) (drop 2 xs))
            [opts xs]))]
    (apply execute! (vec rest-args) (mapcat identity opts))))

;; ---------------------------------------------------------------------------
;; Realisation: turn data nodes into live Ant objects without executing.
;;
;; A few Ant types (FileSet, Path, FileList, Resources, Restrict, ...) are
;; valuable to Clojure code on their own, independent of any task. We let
;; users materialise such a node, ask it for its resources, and feed the
;; result through the normal Clojure seq machinery.

(defn realize
  "Materialise a node into a live Ant object (Task or DataType) without
  executing it. Property references are expanded; `refid`s resolve;
  nested elements are configured. Returns the underlying Ant object
  (e.g. `org.apache.tools.ant.types.FileSet`).

  Options:
    :project   an existing Project, or nil to create one. Other keys
               are forwarded to `make-project`."
  ^Object [node & {:as opts}]
  (let [project ^Project (or (:project opts) (make-project (or opts {})))
        target  (doto (Target.) (.setName "") (.setProject project))
        ue      (->unknown-element node project target)]
    (.maybeConfigure ue)
    (.getRealThing ue)))

(defn- ^File resource->file [^Resource r]
  (if (instance? FileProvider r)
    (.getFile ^FileProvider r)
    (File. (.getName r))))

(defn resources
  "Lazily realise a resource-collection node and return a seq of
  `org.apache.tools.ant.types.Resource`. Works on any node whose backing
  Ant type implements `ResourceCollection` (fileset, filelist, path,
  files, dirset, restrict, intersect, …)."
  [node & {:as opts}]
  (let [obj (apply realize node (mapcat identity opts))]
    (when-not (instance? ResourceCollection obj)
      (throw (ex-info (str "Not a resource collection: " (:tag node))
                      {:tag (:tag node) :class (class obj)})))
    (iterator-seq (.iterator ^ResourceCollection obj))))

(defn files
  "Lazy seq of `java.io.File` from a resource-collection node. Resources
  that don't resolve to a filesystem path (HTTP, zip entry, …) are
  passed through `clojure.java.io/file` on their name."
  [node & {:as opts}]
  (map resource->file (apply resources node (mapcat identity opts))))

;; ---------------------------------------------------------------------------
;; Datafy: make build results pleasant to inspect at the REPL.

(extend-protocol p/Datafiable
  Node
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
  "Pretty-print a node tree to *out*. Useful for sanity-checking a
  build before running it. Returns the node unchanged so it can be
  threaded into `ant` / `execute!`."
  ([node] (plan node 0) node)
  ([node depth]
   (let [pad (apply str (repeat (* 2 depth) \space))]
     (println (str pad "<" (name (:tag node))
                   (apply str
                          (for [[k v] (:attrs node)]
                            (str " " (name k) "=" (pr-str (str v)))))
                   (if (or (seq (:children node)) (:text node)) ">" "/>")))
     (when-some [t (:text node)]
       (println (str pad "  " t)))
     (doseq [c (:children node)]
       (plan c (inc depth)))
     (when (or (seq (:children node)) (:text node))
       (println (str pad "</" (name (:tag node)) ">"))))
   node))
