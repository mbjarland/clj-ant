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
  (:require [clojure.java.io :as io])
  (:import [org.apache.tools.ant Project Target Location
                                 UnknownElement RuntimeConfigurable
                                 DefaultLogger BuildListener BuildEvent]
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
