(ns clj-ant.gen
  "Generator for `clj-ant.tasks`.

  Walks Ant's `defaults.properties` (one for tasks, one for types),
  reflects on each class via Ant's own `IntrospectionHelper`, and
  emits a Clojure namespace where every task and type is a thin
  function with rich docstring + `:arglists` metadata.

  IDE autocompletion (Cursive, CIDER, clojure-lsp) reads `:arglists`
  off the var, so keys you list in `[& {:keys [todir tofile ...]}]`
  show up as keyword completions at call sites. Combined with the
  attribute/element type info from IntrospectionHelper, the result
  is a self-documenting Ant API at the REPL.

  Usage:

      clj -X:gen
      clj -X:gen :out '\"src/clj/clj_ant/tasks.clj\"' :manual-dir '\"ant/manual\"'"
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util Properties]
           [java.util.jar JarFile JarEntry]
           [java.io File]
           [org.apache.tools.ant Project IntrospectionHelper]))

;; ---------------------------------------------------------------------------
;; Default-properties harvesting

(def task-defs-path "org/apache/tools/ant/taskdefs/defaults.properties")
(def type-defs-path "org/apache/tools/ant/types/defaults.properties")

(defn- load-defaults [resource-path]
  (let [p (Properties.)]
    (with-open [r (io/reader (io/resource resource-path))]
      (.load p r))
    (into (sorted-map)
          (map (fn [[k v]] [k v]) p))))

;; ---------------------------------------------------------------------------
;; Reflection via Ant's own IntrospectionHelper

;; The setters Ant adds to every Task/ProjectComponent — uninteresting noise.
(def ^:private framework-attrs
  #{"description" "id" "location" "owningtarget" "project"
    "runtimeconfigurablewrapper" "taskname" "tasktype" "name"})

(defn- friendly-type
  "Render a parameter Class as the kind of value Ant accepts. Ant's
  IntrospectionHelper accepts `String` for everything (it parses on
  set), but the *declared* setter type tells us what kind of value
  the user is conceptually supplying."
  [^Class c]
  (cond
    (nil? c)                   "String"
    (= Boolean/TYPE c)         "boolean"
    (= Boolean c)              "Boolean"
    (= Integer/TYPE c)         "int"
    (= Long/TYPE c)            "long"
    (= File c)                 "File"
    (.isEnum c)                (str (.getSimpleName c)
                                    " {"
                                    (str/join "|" (map str (.getEnumConstants c)))
                                    "}")
    :else (.getSimpleName c)))

(defn- entries
  "Java Map -> sorted seq of [k v] Clojure vectors (no Map.Entry surprises)."
  [^java.util.Map m]
  (->> m
       (map (fn [^java.util.Map$Entry e] [(.getKey e) (.getValue e)]))
       (sort-by first)))

(defn- introspect [klass-name]
  (try
    (let [klass     (Class/forName klass-name)
          ;; `IntrospectionHelper/getHelper` only needs a Class. We pass a
          ;; throwaway Project so EnumeratedAttribute and friends resolve.
          helper    (IntrospectionHelper/getHelper (Project.) klass)
          attrs     (->> (entries (.getAttributeMap helper))
                         (remove (fn [[k _]] (framework-attrs (str k)))))
          nested    (entries (.getNestedElementMap helper))]
      {:class    klass
       :attrs    attrs
       :nested   nested
       :supports-text? (.supportsCharacters helper)})
    (catch Throwable _ nil)))

;; ---------------------------------------------------------------------------
;; Manual HTML extraction

(defn- read-description
  "Pull the prose under `<h3>Description</h3>` from a task's manual page,
  if present locally. Strips inline tags, collapses whitespace. Returns
  nil if not available."
  [^File manual-dir tag]
  (when (and manual-dir (.isDirectory manual-dir))
    (let [candidates [(File. (File. manual-dir "Tasks") (str tag ".html"))
                      (File. (File. manual-dir "Types") (str tag ".html"))]]
      (when-some [^File f (some #(when (.exists ^File %) %) candidates)]
        (let [html (slurp f)
              ;; Grab text between <h3>Description</h3> and the next <h3>.
              m    (re-find #"(?s)<h3[^>]*>\s*Description\s*</h3>(.*?)<h3" html)
              raw  (when m (second m))]
          (when raw
            (-> raw
                ;; remove tags
                (str/replace #"(?s)<[^>]+>" " ")
                ;; entities
                (str/replace #"&nbsp;" " ")
                (str/replace #"&amp;" "&")
                (str/replace #"&lt;" "<")
                (str/replace #"&gt;" ">")
                (str/replace #"&quot;" "\"")
                ;; whitespace
                (str/replace #"\s+" " ")
                str/trim)))))))

;; ---------------------------------------------------------------------------
;; Rendering

(defn- wrap [^String s width indent]
  (let [pad (apply str (repeat indent \space))]
    (loop [words (str/split s #"\s+") line "" out []]
      (if-some [w (first words)]
        (let [cand (if (empty? line) w (str line " " w))]
          (if (> (count cand) width)
            (recur (rest words) w (conj out line))
            (recur (rest words) cand out)))
        (str/join (str \newline pad) (filter seq (conj out line)))))))

(defn- safe-symbol
  "Pick a clojure-friendly symbol for an Ant tag.

  We only rewrite tag names that collide with Clojure *special forms* —
  `def`, `if`, `let`, `do`, etc. — because `defn` can't bind those.
  Everything else stays as the literal XML name; clashes with
  `clojure.core` vars are handled by `:refer-clojure :exclude` on the
  generated namespace, so users still call `t/copy`, `t/replace`, etc."
  [tag]
  (let [special-forms #{"def" "if" "do" "let" "quote" "var" "fn" "loop"
                        "recur" "throw" "try" "catch" "finally" "new"
                        "set!" "monitor-enter" "monitor-exit"}]
    (symbol (if (special-forms tag)
              (str tag "-task")
              tag))))

(defn- attr-keyword
  "Ant matches setters case-insensitively. Most attributes are lowercase
  XML keys, but the introspection helper sometimes returns CamelCase from
  the underlying setter name. We lowercase for the keyword form so users
  type `:todir`, not `:toDir`."
  [k]
  (keyword (.toLowerCase ^String (name k))))

(defn- attribute-doc-line [[k ^Class c]]
  (format "    %-26s %s"
          (str (attr-keyword k))
          (friendly-type c)))

(defn- nested-doc-line [[k ^Class c]]
  (format "    %-26s (%s)"
          (str (attr-keyword k))
          (.getSimpleName c)))

(defn- task-fn-source
  [{:keys [tag class? sym kind description info]}]
  (let [{:keys [attrs nested supports-text?]} info
        attr-keys (mapv (comp symbol name attr-keyword first) attrs)
        attr-block  (when (seq attrs)
                      (str "  Attributes:" \newline
                           (str/join \newline (map attribute-doc-line attrs))))
        nested-block (when (seq nested)
                       (str "  Nested elements:" \newline
                            (str/join \newline (map nested-doc-line nested))))
        text-block  (when supports-text?
                      "  Body text: this element accepts a free-form text body.")
        link        (case kind
                      :task (str "  https://ant.apache.org/manual/Tasks/" tag ".html")
                      :type (str "  https://ant.apache.org/manual/Types/" tag ".html"))
        desc        (or description
                        (str "Ant " (clojure.core/name kind) " "
                             tag ". (No description bundled.)"))
        docstring   (->> [(wrap desc 76 2)
                          ""
                          attr-block
                          nested-block
                          text-block
                          ""
                          link
                          ""
                          (format "  Defined by: %s" class?)]
                         (remove nil?)
                         (str/join \newline))
        arglists    (if (seq attr-keys)
                      (list 'quote
                            (list ['& {:keys attr-keys :as 'attrs}
                                   '& 'nested]))
                      (list 'quote (list ['& 'nested])))]
    (with-out-str
      (println (str "(defn " sym))
      (println (str "  \"" (str/replace docstring "\"" "\\\"") "\""))
      (println (str "  {:arglists " (pr-str arglists)
                    ", :clj-ant/tag " (pr-str tag)
                    ", :clj-ant/class \"" class? "\"}"))
      (println "  [& args]")
      (println (str "  (clojure.core/apply c/element "
                    (pr-str (keyword tag)) " args))"))
      (println))))

;; ---------------------------------------------------------------------------
;; Entry point

(defn generate!
  "Render `src/clj/clj_ant/tasks.clj`. Options (all keys optional):

      :out         output path. Default \"src/clj/clj_ant/tasks.clj\".
      :manual-dir  directory containing Tasks/ and Types/ HTML pages.
                   Default \"ant/manual\". If not present, descriptions
                   fall back to a placeholder."
  [{:keys [out manual-dir]
    :or   {out         "src/clj/clj_ant/tasks.clj"
           manual-dir  "ant/manual"}}]
  (let [manual (let [d (io/file manual-dir)] (when (.isDirectory d) d))
        tasks  (load-defaults task-defs-path)
        types  (load-defaults type-defs-path)
        render (fn [tag klass-name kind]
                 (when-some [info (introspect klass-name)]
                   (task-fn-source
                     {:tag         tag
                      :class?      klass-name
                      :sym         (safe-symbol tag)
                      :kind        kind
                      :description (read-description manual tag)
                      :info        info})))
        all-tags    (concat (keys tasks) (keys types))
        all-syms    (set (map safe-symbol all-tags))
        core-syms   (set (map name (keys (ns-publics 'clojure.core))))
        shadowed    (->> all-syms (map name) (filter core-syms) sort vec)
        bodies (concat
                 (keep (fn [[t c]] (render t c :task)) tasks)
                 (keep (fn [[t c]] (render t c :type)) types))
        header (str "(ns clj-ant.tasks\n"
                    "  \"Auto-generated Ant task and type wrappers.\n\n"
                    "  Each function returns a clj-ant element (plain data); pass\n"
                    "  the result to `clj-ant.core/ant` (or `execute!`) to run.\n\n"
                    "  Generated by `clj -X:gen` from\n"
                    "  org.apache.tools.ant version "
                    (org.apache.tools.ant.Main/getShortAntVersion)
                    ".\"\n"
                    "  (:refer-clojure :exclude ["
                    (str/join " " shadowed)
                    "])\n"
                    "  (:require [clj-ant.core :as c]))\n\n")]
    (io/make-parents out)
    (spit out (str header (apply str bodies)))
    (println "wrote" out
             "with" (count bodies) "task/type wrappers")
    {:out (.getAbsolutePath (io/file out))
     :count (count bodies)}))
