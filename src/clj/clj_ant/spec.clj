(ns clj-ant.spec
  "Malli schemas for Ant tasks and types, derived at runtime from
  Ant's own `IntrospectionHelper`.

  Why malli? Schemas here are values, not macros — which means we can
  manufacture them on the fly from reflection data without a single
  `defmacro` and without stuffing things into a global registry the
  generator has to know about. They are also easy to *transform*,
  which matters for Ant: the underlying setters all accept `String`
  but the conceptual type might be `boolean`, `int`, `File`, or an
  enum. Malli's `string-transformer` plus a few custom decoders gives
  us validation without forcing users to type strings everywhere.

  Two entry points:

    (schema-for :copy)        ; -> a [:map ...] schema
    (validate  :copy attrs)   ; -> nil if valid, else humanized errors"
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt])
  (:import [org.apache.tools.ant Project IntrospectionHelper]
           [java.io File]))

;; ---------------------------------------------------------------------------
;; Class -> malli schema

(defn- enum-schema [^Class c]
  (into [:enum] (map str (.getEnumConstants c))))

(defn- ant-attr-schema
  "Map a setter parameter Class to a malli schema. Ant accepts strings
  for everything, so we always allow a string fallback for raw input;
  decoding via `mt/string-transformer` will then coerce."
  [^Class c]
  (cond
    (nil? c)                   :string
    (= Boolean/TYPE c)         [:or :boolean [:enum "true" "false" "yes" "no" "on" "off"]]
    (= Boolean c)              [:or :boolean [:enum "true" "false" "yes" "no" "on" "off"]]
    (= Integer/TYPE c)         [:or :int :string]
    (= Long/TYPE c)            [:or :int :string]
    (= File c)                 [:or [:fn #(instance? File %)] :string]
    (.isEnum c)                (enum-schema c)
    :else                      :string))

;; A handful of attributes Ant adds to every Task/ProjectComponent. Hide them
;; from user-facing schemas — they're not part of the surface API.
;;
;; NOTE: we deliberately DO include "name" -- Property/Target/Typedef/
;; Macrodef/Attribute and many others override setName for their own
;; use, so it's a real user attribute on those classes. The framework's
;; Task.setName for build-listener naming is the same setter; if a
;; concrete class doesn't override it, callers shouldn't be setting
;; :name there anyway.
(def ^:private framework-attrs
  #{"description" "id" "location" "owningtarget" "project"
    "runtimeconfigurablewrapper" "taskname" "tasktype"})

(defn- entries-of [^java.util.Map m]
  (->> m
       (map (fn [^java.util.Map$Entry e] [(.getKey e) (.getValue e)]))
       (sort-by first)))

(defn- load-class ^Class [class-name]
  (try (Class/forName class-name) (catch Throwable _ nil)))

(defn- wrapper-meta [tag]
  (try
    (when-some [v (requiring-resolve
                    (symbol "clj-ant.tasks" (name tag)))]
      (meta v))
    (catch Throwable _ nil)))

(defn- wrapper-recorded-classes
  "Every class the generator recorded for a nested-only tag. More
  than one when the tag is context-ambiguous (e.g. <attribute>)."
  [tag]
  (let [m (wrapper-meta tag)]
    (->> (or (:clj-ant/classes m)
             (when-some [c (:clj-ant/class m)] [c]))
         (keep load-class)
         vec
         not-empty)))

(defn- klasses-for-tag
  "Best-known class set for a tag. With `parent` (a tag), prefer the
  class the generator recorded for that specific (parent, tag) pair
  if any -- so <attribute> under <macrodef> validates against
  MacroDef$Attribute, not the Manifest$Attribute union. Without a
  parent, return every recorded class for the union path."
  ([^Project project tag] (klasses-for-tag project tag nil))
  ([^Project project tag parent]
   (let [n (name tag)]
     (or (when-some [c (.get (.getTaskDefinitions project) n)]     [c])
         (when-some [c (.get (.getDataTypeDefinitions project) n)] [c])
         ;; If the parent is known and this tag has a recorded
         ;; (parent-class -> child-class) mapping, that wins -- the
         ;; runtime would resolve to the same class.
         (when parent
           (when-some [parent-class
                       (or (.get (.getTaskDefinitions project) (name parent))
                           (.get (.getDataTypeDefinitions project) (name parent))
                           (first (wrapper-recorded-classes parent)))]
             (when-some [bp (:clj-ant/by-parent (wrapper-meta tag))]
               (when-some [child-class-name (get bp (.getName ^Class parent-class))]
                 (when-some [k (load-class child-class-name)]
                   [k])))))
         (wrapper-recorded-classes tag)))))

;; ---------------------------------------------------------------------------
;; Cached per-tag schema build

(def ^:private schema-cache (atom {}))

(defn- attrs-of [^Project project ^Class klass]
  (let [helper (IntrospectionHelper/getHelper project klass)]
    (->> (entries-of (.getAttributeMap helper))
         (remove (fn [[k _]] (framework-attrs (str k)))))))

(defn- build-schema [tag closed? parent]
  (let [project (Project.) _ (.init project)
        klasses (klasses-for-tag project tag parent)]
    (when (seq klasses)
      ;; For ambiguous tags (multiple classes), build a UNION schema:
      ;; an attr key is valid if any class accepts it, and its schema
      ;; is :or over all the classes that accept it. Closed-mode
      ;; rejection then only fires when the key is in NONE of them --
      ;; e.g. macrodef's :default and manifest's :value both pass on
      ;; <attribute>, while a typo like :defalt is still caught.
      (let [;; key (lowercased) -> [class1-schema class2-schema ...]
            schemas-by-key
            (reduce (fn [acc klass]
                      (reduce (fn [a [^String k ^Class c]]
                                (update a (keyword (.toLowerCase k))
                                        (fnil conj []) (ant-attr-schema c)))
                              acc
                              (attrs-of project klass)))
                    {}
                    klasses)]
        (into [:map {:closed (boolean closed?)}]
              (for [[k schemas] (sort-by first schemas-by-key)]
                [k {:optional true}
                 (if (= 1 (count (set schemas)))
                   (first schemas)
                   (into [:or] (distinct schemas)))]))))))

(defn schema-for
  "Return a cached malli schema for a tag (e.g. `:copy`). Returns nil
  for unknown tags. With `:closed? true`, the schema rejects unknown
  attributes (useful for catching typos like `:tdoir`).

  Pass `:parent <parent-tag>` to scope the schema to the specific
  class that tag resolves to under that parent -- relevant for
  context-ambiguous tags like `:attribute` (different classes under
  <macrodef> vs <manifest>). Without a parent, the schema is the
  union over every recorded class."
  ([tag] (schema-for tag {}))
  ([tag {:keys [closed? parent]}]
   (let [k [tag (boolean closed?) parent]]
     (or (get @schema-cache k)
         (when-some [s (build-schema tag closed? parent)]
           (swap! schema-cache assoc k s)
           s)))))

(defn- schema-keys [schema]
  (->> (rest schema)
       (keep (fn [entry]
               (when (vector? entry)
                 (first entry))))
       set))

(defn- edit-distance [^String a ^String b]
  (let [la (count a)
        lb (count b)]
    (loop [i 0
           prev (vec (range (inc lb)))]
      (if (= i la)
        (peek prev)
        (let [ca (.charAt a i)
              curr (loop [j 0
                          row [(inc i)]]
                     (if (= j lb)
                       row
                       (let [cost (if (= ca (.charAt b j)) 0 1)
                             insert (inc (peek row))
                             delete (inc (prev (inc j)))
                             replace (+ (prev j) cost)]
                         (recur (inc j)
                                (conj row (min insert delete replace))))))]
          (recur (inc i) curr))))))

(defn- attr-suggestions [tag attrs opts]
  (when-some [schema (schema-for tag opts)]
    (let [allowed (schema-keys schema)
          attrs*  (->> attrs
                       (map (fn [[k _]] [(keyword (str/lower-case (name k))) k]))
                       (into {}))]
      (not-empty
        (into {}
              (keep (fn [[k original-k]]
                      (when-not (contains? allowed k)
                        (let [suggestions (->> allowed
                                               (map (fn [candidate]
                                                      [candidate (edit-distance
                                                                   (name k)
                                                                   (name candidate))]))
                                               (filter #(<= (second %) 2))
                                               (sort-by (juxt second (comp name first)))
                                               (take 3)
                                               (mapv first))]
                          (when (seq suggestions)
                            [original-k suggestions])))))
              attrs*)))))

(defn- plainly-required? [s]
  (boolean (re-matches #"(?i)\s*yes\.?\s*" (or s ""))))

(defn- missing-required-attrs [tag attrs]
  (let [meta (wrapper-meta tag)]
    (when-not (< 1 (count (:clj-ant/classes meta)))
      (let [m (:clj-ant/attrs meta)
            present (->> attrs
                         keys
                         (map (comp keyword str/lower-case name))
                         set)]
        (not-empty
          (into {}
                (keep (fn [[k {:keys [required]}]]
                        (when (and (plainly-required? required)
                                   (not (contains? present k)))
                          [k [(str "missing required attribute"
                                   (when required (str " (Required: " required ")")))]])))
                m))))))

;; ---------------------------------------------------------------------------
;; Validation surface

(def ^:private string-coercer
  "A malli transformer that lets users pass plain values OR strings.
  Useful because Ant accepts both."
  (mt/string-transformer))

(defn validate
  "Validate an attribute map against the schema for `tag`. Returns nil
  if valid, a humanized error map otherwise. Returns nil if the tag is
  unknown -- we don't want validation to refuse to run for tasks
  loaded via `<taskdef>`.

  Options:
    :closed?  reject unknown attributes too (default false).
    :parent   <parent-tag> used to disambiguate context-sensitive
              nested tags. Lets <attribute> under <macrodef>
              validate against MacroDef$Attribute even though the
              same tag means Manifest$Attribute under <manifest>."
  ([tag attrs] (validate tag attrs {}))
  ([tag attrs opts]
   (when-some [schema (schema-for tag opts)]
     (let [attrs* (->> attrs
                       (map (fn [[k v]] [(keyword (.toLowerCase (name k))) v]))
                       (into {}))
           coerced (m/decode schema attrs* string-coercer)]
       (when-not (m/validate schema coerced)
         (me/humanize (m/explain schema coerced)))))))

(defn validate!
  "Like `validate`, but throws when invalid. Returns nil on success."
  ([tag attrs] (validate! tag attrs {}))
  ([tag attrs opts]
   (when-some [errs (validate tag attrs opts)]
     (throw (ex-info (str "Invalid attributes for <" (name tag) ">: " errs)
                     {:tag tag :errors errs :attrs attrs})))))

(defn validate-tree
  "Walk a element tree and collect all validation errors as a vector of
  `{:tag :path :errors}` maps. With `:closed? true`, issues may also
  include `:suggestions` for likely misspelled attribute names. Empty
  vector means everything checks out.

  Attributes with manual metadata of exactly `Required: Yes` are reported
  as missing before Ant runs. Conditional requirements such as `Yes, unless
  ...` are deliberately left to Ant.

  Threads parent context down so context-ambiguous nested tags
  (`:attribute` under <macrodef> vs <manifest>, `:element` under
  <scriptdef> vs <macrodef>, ...) validate against the correct
  class for their parent.

  Options forwarded to `validate` (notably `:closed?`)."
  ([element] (validate-tree element {}))
  ([element opts]
   (letfn [(walk [parent path n]
             (let [opts* (assoc opts :parent parent)
                   validation-errors (validate (:tag n) (:attrs n) opts*)
                   required-errors (missing-required-attrs (:tag n) (:attrs n))
                   errors (merge-with into validation-errors required-errors)
                   here (when (seq errors)
                          (let [suggestions (when (:closed? opts*)
                                              (attr-suggestions (:tag n)
                                                                (:attrs n)
                                                                opts*))]
                            [(cond-> {:tag (:tag n)
                                      :path (vec path)
                                      :errors errors}
                               suggestions (assoc :suggestions suggestions))]))]
                (concat
                  here
                  (mapcat #(walk (:tag n) (conj path (:tag n)) %) (:children n)))))]
     (vec (walk nil [] element)))))
