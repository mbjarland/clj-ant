(ns clj-ant.gen
  "Generator for `clj-ant.tasks`.

  Walks Ant's `defaults.properties` (one for tasks, one for types),
  reflects on each class via Ant's own `IntrospectionHelper`, and
  emits a Clojure namespace where every task and type is a thin
  function with cljdoc-friendly Markdown docstrings. Attributes and
  nested elements come from IntrospectionHelper, enriched by the
  bundled Ant manual when a matching page exists.

  Usage:

      clj -X:gen
      clj -X:gen :out '\"src/clj/clj_ant/tasks.clj\"' :manual-dir '\"ant/manual\"'"
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util Properties]
            [java.util.regex Pattern]
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

;; Setters Ant attaches to every Task/ProjectComponent that aren't part
;; of the user surface. We deliberately keep "name" off this list:
;; Property/Target/Typedef/Macrodef/Attribute and many others override
;; setName for their own attribute, so it IS a real user-facing attr.
(def ^:private framework-attrs
  #{"description" "id" "location" "owningtarget" "project"
    "runtimeconfigurablewrapper" "taskname" "tasktype"})

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

(def ^:private html-entities
  {"nbsp" " "
   "amp" "&"
   "lt" "<"
   "gt" ">"
   "quot" "\""
   "apos" "'"
   "mdash" "-"
   "ndash" "-"
   "hellip" "..."
   "rarr" "->"
   "rArr" "=>"
   "ge" ">="
   "times" "x"
   "trade" "(TM)"
   "aacute" "a"
   "eacute" "e"
   "egrave" "e"
   "iacute" "i"
   "oacute" "o"
   "uacute" "u"
   "oelig" "oe"
   "rsquo" "'"
   "lsquo" "'"
   "rdquo" "\""
   "ldquo" "\""})

(defn- decode-entity [entity]
  (or (get html-entities entity)
      (when-let [[_ hex] (re-matches #"#x([0-9A-Fa-f]+)" entity)]
        (String/valueOf (Character/toChars (Long/parseLong hex 16))))
      (when-let [[_ dec] (re-matches #"#([0-9]+)" entity)]
        (String/valueOf (Character/toChars (Long/parseLong dec))))
      (str "&" entity ";")))

(def ^:private manual-aliases
  {"blgenclient" "Tasks/BorlandGenerateClient.html"
   "javadoc2" "Tasks/javadoc.html"
   "renameext" "Tasks/renameextensions.html"})

(defn- polish-text [s]
  (some-> s
          (str/replace #"\s+([,;:!?])" "$1")
          (str/replace #"\s+\.(?=\s|$)" ".")
          (str/replace #",(?=\S)" ", ")
          (str/replace #"\(\s+" "(")
          (str/replace #"\s+\)" ")")
          str/trim
          not-empty))

(defn- html->text [html]
  (some-> html
          (str/replace #"(?is)<!--.*?-->" " ")
          (str/replace #"(?is)<(br|/p|/div|/li|/tr|/h[1-6])\b[^>]*>" " ")
          (str/replace #"(?is)<[^>]+>" "")
          (str/replace #"&(#x[0-9A-Fa-f]+|#[0-9]+|[A-Za-z]+);"
                       #(decode-entity (second %)))
          (str/replace #"\s+" " ")
          polish-text))

(defn- href-entry [^File base href]
  (let [[path section] (str/split (or href "") #"#" 2)]
    (when (and (str/ends-with? path ".html")
               (not (str/starts-with? path "http:"))
               (not (str/starts-with? path "https:")))
      (let [f (io/file base path)]
        (when (.exists f)
          (cond-> {:file f}
            (not-empty section) (assoc :section section)))))))

(defn- read-description-from-html [html]
  (or (some-> (re-find #"(?is)<h3[^>]*>\s*Description\s*</h3>(.*?)(?=<h3\b|<h2\b|</body>)" html)
              second
              html->text)
      ;; Type pages often start with prose directly after <h2> and then
      ;; jump straight into the attribute table without a Description heading.
      (some-> (re-find #"(?is)<h2[^>]*>.*?</h2>(.*?)(?=<table\b[^>]*class=[\"'][^\"']*\battr\b|<h3\b|<h2\b|</body>)" html)
              second
              html->text)))

(defn- first-attr-table [html]
  (some-> (re-find #"(?is)<table\b[^>]*class=[\"'][^\"']*\battr\b[^\"']*[\"'][^>]*>(.*?)</table>" html)
          second))

(defn- table-rows [table-html]
  (map second (re-seq #"(?is)<tr\b[^>]*>(.*?)</tr>" table-html)))

(defn- row-cells [row-html]
  (mapv (fn [[_ attrs body]]
          {:attrs attrs
           :text  (html->text body)})
        (re-seq #"(?is)<td\b([^>]*)>(.*?)</td>" row-html)))

(defn- rowspan [attrs]
  (if-let [[_ n] (re-find #"(?i)rowspan\s*=\s*[\"']?([0-9]+)" (or attrs ""))]
    (Long/parseLong n)
    1))

(defn- attr-name-key [s]
  (when-some [name (some-> s
                           (str/replace #"\s+" " ")
                           str/trim
                           not-empty)]
    (keyword (.toLowerCase ^String name))))

(defn- manual-name-key [s]
  (some-> s
          html->text
          str/lower-case
          (str/replace #"\s+" "")
          not-empty))

(defn- href-name-key [href]
  (some-> href
          (str/split #"#" 2)
          first
          (str/replace #"^.*/" "")
          (str/replace #"\.html$" "")
          manual-name-key))

(defn- overview-index [^File manual-dir]
  (let [overview (io/file manual-dir "tasksoverview.html")]
    (if-not (.exists overview)
      {}
      (let [html (slurp overview)]
        (reduce
          (fn [acc row]
            (if-let [[_ href label] (re-find #"(?is)<a\b[^>]*\bhref\s*=\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>" row)]
              (if-let [entry (href-entry manual-dir href)]
                (let [desc (:text (second (row-cells row)))
                      entry' (cond-> entry desc (assoc :overview-description desc))
                      keys (->> (conj (str/split (or (html->text label) "") #"/")
                                      (href-name-key href))
                                (keep manual-name-key))]
                  (reduce #(assoc %1 %2 entry') acc keys))
                acc)
              acc))
          {}
          (table-rows html))))))

(defn- html-files [^File manual-dir]
  (->> ["Tasks" "Types"]
       (map #(io/file manual-dir %))
       (filter #(.isDirectory ^File %))
       (mapcat file-seq)
       (filter #(and (.isFile ^File %)
                     (str/ends-with? (.getName ^File %) ".html")))))

(defn- section-index [^File manual-dir]
  (into {}
        (for [^File f (html-files manual-dir)
              [_ id] (re-seq #"(?is)<h2\b[^>]*\bid\s*=\s*[\"']([^\"']+)[\"']" (slurp f))
              :let [k (manual-name-key id)]
              :when k]
          [k {:file f :section id}])))

(def ^:private manual-index
  (memoize
    (fn [^File manual-dir]
      {:overview (overview-index manual-dir)
       :sections (section-index manual-dir)})))

(defn- direct-entry [^File manual-dir tag]
  (let [candidates [(File. (File. manual-dir "Tasks") (str tag ".html"))
                    (File. (File. manual-dir "Types") (str tag ".html"))]]
    (some #(when (.exists ^File %) {:file %}) candidates)))

(defn- manual-entry [^File manual-dir tag]
  (when (and manual-dir (.isDirectory manual-dir))
    (let [k (manual-name-key tag)
          {:keys [overview sections]} (manual-index manual-dir)]
      (or (direct-entry manual-dir tag)
          (get sections k)
          (get overview k)
          (some->> (get manual-aliases k)
                   (href-entry manual-dir))))))

(defn- redirect-href [html]
  (some-> (re-find #"(?is)<body[^>]*>\s*This document's new home is\s*<a\b[^>]*\bhref\s*=\s*[\"']([^\"']+)[\"']" html)
          second))

(defn- resolve-redirects [entry]
  (loop [entry entry
         seen #{}]
    (let [^File f (:file entry)]
      (if (or (nil? f) (seen (.getCanonicalPath f)))
        entry
        (let [html (slurp f)]
          (if-let [href (redirect-href html)]
            (if-let [target (href-entry (.getParentFile f) href)]
              (recur (merge entry target) (conj seen (.getCanonicalPath f)))
              entry)
            entry))))))

(defn- html-section [html section]
  (if (not-empty section)
    (re-find (re-pattern (str "(?is)<h2\\b[^>]*\\bid\\s*=\\s*[\\\"']"
                              (Pattern/quote section)
                              "[\\\"'][^>]*>.*?(?=<h2\\b|</body>)"))
             html)
    html))

(defn- manual-url [^File file section]
  (let [parent (some-> file .getParentFile .getName)]
    (when (and parent (.getName file))
      (str "https://ant.apache.org/manual/"
           parent "/" (.getName file)
           (when (not-empty section) (str "#" section))))))

(defn- read-attrs-from-html [html]
  (when-some [table (first-attr-table html)]
    (loop [rows (table-rows table)
           carried-required nil
           acc (sorted-map)]
      (if-some [row (first rows)]
        (let [cells (row-cells row)
              attr-cell (first cells)
              desc-cell (second cells)
              required-cell (nth cells 2 nil)
              attr-key (attr-name-key (:text attr-cell))
              required (or (:text required-cell) (:text carried-required))
              carried-required'
              (cond
                required-cell
                (let [remaining (dec (rowspan (:attrs required-cell)))]
                  (when (pos? remaining)
                    {:text (:text required-cell) :remaining remaining}))

                (and carried-required (pos? (:remaining carried-required)))
                (let [remaining (dec (:remaining carried-required))]
                  (when (pos? remaining)
                    (assoc carried-required :remaining remaining)))

                :else nil)
              acc' (if (and attr-key (:text desc-cell))
                     (assoc acc attr-key
                            (cond-> {:description (:text desc-cell)}
                              required (assoc :required required)))
                     acc)]
          (recur (rest rows) carried-required' acc'))
        (not-empty acc)))))

(defn- read-manual-info
  "Read task/type prose and attribute table docs from the bundled Ant manual."
  [^File manual-dir tag]
  (when-some [{:keys [^File file section overview-description]} (some-> (manual-entry manual-dir tag)
                                                                         resolve-redirects)]
    (let [html (slurp file)
          relevant-html (or (html-section html section) html)
          description (or (read-description-from-html relevant-html)
                          overview-description)
          attrs (read-attrs-from-html relevant-html)]
      (cond-> {:manual-file (.getPath file)}
        section (assoc :manual-section section)
        (manual-url file section) (assoc :manual-url (manual-url file section))
        description (assoc :description description)
        attrs (assoc :attrs attrs)))))

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

(defn- string-literal [s]
  (str "\""
       (-> s
           (str/replace "\\" "\\\\")
           (str/replace "\"" "\\\""))
       "\""))

(defn- table-cell [s]
  (-> (or s "")
      (str/replace #"\s+" " ")
      (str/replace "|" "\\|")
      str/trim))

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

(defn- attribute-doc-line [manual-attrs [k ^Class c]]
  (let [kw (attr-keyword k)
        {:keys [description required]} (get manual-attrs kw)]
    (format "| `%s` | `%s` | %s | %s |"
            kw
            (table-cell (friendly-type c))
            (table-cell description)
            (table-cell required))))

(defn- nested-doc-line [[k ^Class c]]
  (format "- `%s` (`%s`)"
          (attr-keyword k)
          (.getSimpleName c)))

(defn- task-fn-source
  [{:keys [tag class? sym kind manual info other-classes by-parent]}]
  (let [{:keys [attrs nested supports-text?]} info
        manual-attrs (:attrs manual)
        recorded-manual-attrs (into (sorted-map)
                                    (keep (fn [[k _]]
                                            (let [kw (attr-keyword k)]
                                              (when-some [doc (get manual-attrs kw)]
                                                [kw doc]))))
                                    attrs)
        attr-block  (when (seq attrs)
                       (str "**Attributes**" \newline \newline
                             "| Attribute | Type | Description | Required |" \newline
                             "| --- | --- | --- | --- |" \newline
                             (str/join \newline (map #(attribute-doc-line manual-attrs %)
                                                      attrs))))
        nested-block (when (seq nested)
                       (str "**Nested elements**" \newline \newline
                             (str/join \newline (map nested-doc-line nested))))
        text-block  (when supports-text?
                      "**Body text**\n\nAccepts a free-form text body.")
        link-url    (:manual-url manual)
        link        (or link-url
                        (case kind
                          :task   (str "https://ant.apache.org/manual/Tasks/" tag ".html")
                          :type   (str "https://ant.apache.org/manual/Types/" tag ".html")
                          :nested "Nested-only element discovered via introspection."))
        ;; Some nested tags are ambiguous: <attribute> on macrodef is
        ;; MacroDef$Attribute, on manifest is Manifest$Attribute.
        ;; The runner picks the right class at execute time based on
        ;; parent context; the wrapper just carries the data. The
        ;; docstring lists every class we saw so users know.
        ambiguity   (when (seq other-classes)
                      (str "**Parent context**\n\n"
                           "This tag has multiple meanings depending on parent context. "
                           "Other classes seen:" \newline \newline
                           (str/join \newline
                                     (map #(str "- `" % "`") other-classes))
                           \newline \newline
                           "The runner picks the right class at execute time; "
                           "attribute docs above are for the first one."))
        desc        (or (:description manual)
                        (str "Ant " (clojure.core/name kind) " "
                             tag ". (No description bundled.)"))
        docstring   (->> [(wrap desc 88 0)
                          attr-block
                          nested-block
                          text-block
                          ambiguity
                          (str "**Reference**" \newline \newline link)
                          (format "**Defined by**\n\n`%s`" class?)]
                          (remove nil?)
                          (str/join (str \newline \newline)))
        arglists    (list 'quote (list ['& 'args]))]
    (with-out-str
      (println (str "(defn " sym))
      (println (str "  " (string-literal docstring)))
      (println (str "  {:arglists " (pr-str arglists)
                     ", :clj-ant/tag " (pr-str tag)
                     ", :clj-ant/class \"" class? "\""
                     ", :clj-ant/classes "
                     (pr-str (vec (cons class? (or other-classes []))))
                     (when-some [description (:description manual)]
                       (str ", :clj-ant/description " (pr-str description)))
                     (when link-url
                       (str ", :clj-ant/manual-url " (pr-str link-url)))
                     (when (seq recorded-manual-attrs)
                       (str ", :clj-ant/attrs " (pr-str recorded-manual-attrs)))
                     (when by-parent
                       (str ", :clj-ant/by-parent " (pr-str by-parent)))
                    "}"))
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
        ;; Walk every top-level type's nested-element graph to pick up
        ;; nested-only tags (tokenfilter, replacestring, jvmarg,
        ;; sysproperty, argument, targetfile, ...). defaults.properties
        ;; lists what's reachable at the top level; recursive
        ;; introspection lists what's reachable at all.
        ;;
        ;; Three concerns kept separate during the walk:
        ;;   * cycle prevention   -- tracked in `seen-classes`
        ;;   * tag aliases        -- (tag -> ordered set of classes)
        ;;   * parent context     -- (tag -> {parent-class -> child-class})
        ;;
        ;; A class can be reached under multiple tag names (Argument
        ;; lives behind <arg>, <jvmarg>, <argument>, ...). A tag can
        ;; resolve to multiple classes depending on parent
        ;; (<attribute> is MacroDef$Attribute under <macrodef>, but
        ;; Manifest$Attribute under <manifest>). Recording the
        ;; (parent, tag) -> child triple lets the validator pick the
        ;; right schema when the parent is known, instead of unioning
        ;; everything (which under-validates context-specific misuse).
        [nested tag->classes tag->by-parent]
        (let [project       (doto (Project.) .init)
              seen-classes  (java.util.HashSet.)
              tag->classes  (java.util.LinkedHashMap.)
              tag->by-parent (java.util.LinkedHashMap.)]
          (letfn [(visit [parent-class-name tag class-name]
                    (when class-name
                      (try
                        (let [klass     (Class/forName class-name)
                              new-class (.add seen-classes klass)]
                          (when (and tag
                                     (not (.containsKey tasks tag))
                                     (not (.containsKey types tag)))
                            ;; Record alias.
                            (let [classes (or (.get tag->classes tag)
                                              (java.util.LinkedHashSet.))]
                              (.add classes class-name)
                              (.put tag->classes tag classes))
                            ;; Record parent->child mapping.
                            (when parent-class-name
                              (let [bp (or (.get tag->by-parent tag)
                                           (java.util.LinkedHashMap.))]
                                (.putIfAbsent bp parent-class-name class-name)
                                (.put tag->by-parent tag bp))))
                          (when new-class
                            (doseq [^java.util.Map$Entry e
                                    (.getNestedElementMap
                                      (IntrospectionHelper/getHelper
                                        project klass))]
                              (visit class-name
                                     (.getKey e)
                                     (.getName ^Class (.getValue e))))))
                        (catch Throwable _ nil))))]
            (doseq [[t c] (concat tasks types)] (visit nil t c))
            [(into (sorted-map)
                   (map (fn [[t cs]] [t (first cs)])
                        tag->classes))
             (into (sorted-map)
                   (map (fn [[t cs]] [t (vec cs)])
                        tag->classes))
             (into (sorted-map)
                   (map (fn [[t bp]] [t (into (sorted-map) bp)])
                        tag->by-parent))]))
        render (fn [tag klass-name kind]
                 (when-some [info (introspect klass-name)]
                   (task-fn-source
                     {:tag           tag
                      :class?        klass-name
                      :sym           (safe-symbol tag)
                      :kind          kind
                      :manual        (read-manual-info manual tag)
                      :info          info
                      :other-classes (when-some [cs (get tag->classes tag)]
                                       (seq (remove #(= % klass-name) cs)))
                      ;; Only emit the parent-context map for tags that
                      ;; are genuinely ambiguous (more than one distinct
                      ;; child class) -- otherwise it's noise.
                      :by-parent     (when-some [bp (get tag->by-parent tag)]
                                       (when (> (count (set (vals bp))) 1)
                                         bp))})))
        all-tags    (concat (keys tasks) (keys types) (keys nested))
        all-syms    (set (map safe-symbol all-tags))
        core-syms   (set (map name (keys (ns-publics 'clojure.core))))
        shadowed    (->> all-syms (map name) (filter core-syms) sort vec)
        bodies (concat
                 (keep (fn [[t c]] (render t c :task))   tasks)
                 (keep (fn [[t c]] (render t c :type))   types)
                 (keep (fn [[t c]] (render t c :nested)) nested))
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
