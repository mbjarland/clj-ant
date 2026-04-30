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
  (:require [malli.core :as m]
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
(def ^:private framework-attrs
  #{"description" "id" "location" "owningtarget" "project"
    "runtimeconfigurablewrapper" "taskname" "tasktype" "name"})

(defn- entries-of [^java.util.Map m]
  (->> m
       (map (fn [^java.util.Map$Entry e] [(.getKey e) (.getValue e)]))
       (sort-by first)))

(defn- klass-for-tag [^Project project tag]
  (let [n (name tag)]
    (or (.get (.getTaskDefinitions project) n)
        (.get (.getDataTypeDefinitions project) n))))

;; ---------------------------------------------------------------------------
;; Cached per-tag schema build

(def ^:private schema-cache (atom {}))

(defn- build-schema [tag closed?]
  (let [project (Project.) _ (.init project)
        klass   (klass-for-tag project tag)]
    (when klass
      (let [helper (IntrospectionHelper/getHelper project klass)
            attrs  (->> (entries-of (.getAttributeMap helper))
                        (remove (fn [[k _]] (framework-attrs (str k)))))]
        (into [:map {:closed (boolean closed?)}]
              (for [[^String k ^Class c] attrs]
                ;; XML attribute names are case-insensitive in Ant. Keep the
                ;; lowercased key as canonical and tolerate either form.
                [(keyword (.toLowerCase k))
                 {:optional true}
                 (ant-attr-schema c)]))))))

(defn schema-for
  "Return a cached malli schema for a tag (e.g. `:copy`). Returns nil
  for unknown tags. With `:closed? true`, the schema rejects unknown
  attributes (useful for catching typos like `:tdoir`)."
  ([tag] (schema-for tag {}))
  ([tag {:keys [closed?] :as opts}]
   (let [k [tag (boolean closed?)]]
     (or (get @schema-cache k)
         (when-some [s (build-schema tag closed?)]
           (swap! schema-cache assoc k s)
           s)))))

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
    :closed?  reject unknown attributes too (default false)."
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
  `{:tag :path :errors}` maps. Empty vector means everything checks
  out.

  Options forwarded to `validate` (notably `:closed?`)."
  ([element] (validate-tree element {}))
  ([element opts]
   (letfn [(walk [path n]
             (let [here (when-some [e (validate (:tag n) (:attrs n) opts)]
                          [{:tag (:tag n) :path (vec path) :errors e}])]
               (concat
                 here
                 (mapcat #(walk (conj path (:tag n)) %) (:children n)))))]
     (vec (walk [] element)))))
