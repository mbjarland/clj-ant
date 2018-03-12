(ns clj-ant.generate
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.reflect :as reflect]
    [pl.danieljanus.tagsoup :as ts]
    [com.rpl.specter :as spctr])
  (:import (java.util Properties Date)))

(def task-defs-path "org/apache/tools/ant/taskdefs/defaults.properties")
(def type-defs-path "org/apache/tools/ant/types/defaults.properties")
(def generated-path "target/generated/clj_ant/core_generated.clj")

(def manual-path "ant/manual/")

(def header
  (str "(in-ns 'clj-ant.core)" \newline
       \newline))

(defn load-properties [path]
  (let [p (Properties.)]
    (with-open [r (io/reader
                    (io/resource path))]
      (.load p r)
      (into {} p))))

(defn build-doc [tag renamed-tag class]
  (str "\"Some documentation for " tag "\""))



(defn- method->dsl-kw [m pre-len]
  (keyword (.toLowerCase (.substring (name (:name m)) pre-len))))

(defn- accessors [clazz]
  (let [s (:members (reflect/type-reflect clazz :ancestors true))]
    (reduce
      (fn [a m]
        (let [m (dissoc m :return-type :exception-types :declaring-class :flags)]
          (condp #(str/starts-with? %2 %1) (name (:name m))
            "add" (assoc-in a [:adders (count (:adders a))] m)
            "set" (assoc-in a [:setters (method->dsl-kw m 3)] m)
            "create" (assoc-in a [:creators (method->dsl-kw m 6)] m)
            a)))
      {:adders []}
      (filter #((:flags %) :public) s))))

;(defn my-fn
;  {:arglists '([{:keys [tofile dir fromfile]} & nested-elements])}
;  [& {:keys [tofile dir fromfile] :as args}]
;  (prn :args args)
;  (prn :tofile tofile :dir dir :fromfile fromfile))

(defn arg-list [class-name]
  (let [clazz   (Class/forName class-name)
        setters (keys (:setters (accessors clazz)))]
    (str/join
      \space
      (sort
        (filter
          #(not (#{"description" "location" "owningtarget"
                   "project" "runtimeconfigurablewrapper"
                   "taskname" "tasktype"} %))
          (map name setters))))))

(defn gen-fn-source [tag renamed-tag class-name]
  (str "(defn " renamed-tag \newline
       "  " (build-doc tag renamed-tag class-name) \newline
       "   {:arglists '([{:keys [" (arg-list class-name) "]} & nested])}" \newline
       "  [& args]" \newline
       "  (ant-xml :" tag " args))" \newline
       \newline
       \newline))

(defn generate-from-props [coll]
  (let [replacements {"ant" "antant"}
        replace      (fn [name] (or (replacements name) name))]
    (reduce
      (fn [a [t c]]
        (try
          (str a (gen-fn-source t (replace t) c))
          (catch Exception e
            (do
              (println "error generating source for" c)
              a))))
      ""
      coll)))

(defn generate []
  (let [props (into (sorted-map) (merge (load-properties type-defs-path)
                                        (load-properties task-defs-path)))]
    (spit generated-path
          (str header
               \newline
               ";; generated at: " (Date.)
               \newline
               \newline
               (generate-from-props props)))))


