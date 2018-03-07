(ns clj-ant.core
  (:require [clojure.reflect :as reflect]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.spec.alpha :as s])
  (:import (org.apache.tools.ant.taskdefs Copy)
           (java.io File)
           (org.apache.tools.ant.types FileSet FilterSet)
           (org.apache.tools.ant Project ProjectHelper NoBannerLogger ComponentHelper)
           (clojure.lang IFn)
           (java.util Map)))

;protected static Project createProject() {
;    final Project project = new Project();
;
;    final ProjectHelper helper = ProjectHelper.getProjectHelper();
;    project.addReference(ProjectHelper.PROJECTHELPER_REFERENCE, helper);
;    helper.getImportStack().addElement("AntBuilder"); // import checks that stack is not empty
;
;    final BuildLogger logger = new NoBannerLogger();
;
;    logger.setMessageOutputLevel(org.apache.tools.ant.Project.MSG_INFO);
;    logger.setOutputPrintStream(System.out);
;    logger.setErrorPrintStream(System.err);
;
;    project.addBuildListener(logger);
;
;    project.init();
;    project.getBaseDir();
;    return project;
;}

(defn invoke [task method args]
  (clojure.lang.Reflector/invokeInstanceMethod
    task
    method
    (to-array args)))

(defn create-project []
  (let [p  (Project.)
        ph (ProjectHelper/getProjectHelper)
        bl (NoBannerLogger.)]
    (.addReference p ProjectHelper/PROJECTHELPER_REFERENCE ph)
    (.addElement (.getImportStack ph) "clj-ant")
    (.setMessageOutputLevel bl Project/MSG_INFO)
    (.setOutputPrintStream bl System/out)
    (.setErrorPrintStream bl System/err)
    (.init p)
    (.getBaseDir p)
    p))

(def create-project-m (memoize create-project))

(defn display-name [o]
  (.getSimpleName (class o)))

(defn partition-args [args]
  (reduce
    (fn [[a n] [k v]]
      (cond
        (nil? v) [a (conj n k)]
        (keyword? k) [(assoc a k v) n]
        :else [a (conj (conj n k) v)]))
    [{} []]
    (partition-all 2 args)))

(defn parse-args [name args]
  (let [[attrs nested] (partition-args args)]
    (reduce
      (fn [a [k v]]
        (if (empty? v) a
                       (merge a {k v})))
      {:name name}
      [[:attrs attrs]
       [:nested nested]])))

(defn method->dsl-kw [m pre-len]
  (keyword (.toLowerCase (.substring (name (:name m)) pre-len))))

(defn accessors [clazz]
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

(def accessors-m (memoize accessors))

(defmulti coerce-type (fn [to-type value] [to-type (class value)]))
(defmethod coerce-type [File String] [_ value] (io/as-file value))
(defmethod coerce-type :default [_ value] value)

(defn set-attribute [acs task attr-name value]
  ;(println "setting attribute" attr-name "on" (display-name task) "to" value)
  (let [setter      (attr-name (:setters acs))
        setter-name (str (:name setter))
        param-type  (resolve (first (:parameter-types setter)))] ;;todo multi param
    (invoke task setter-name (to-array [(coerce-type param-type value)]))))

(defn add-nested-create [acs task nested]
  ;(println "adding nested create" (str \' (:name nested) \') "on" (display-name task))
  (let [dsl-name    (keyword (:name nested))
        method-name (-> acs :creators dsl-name :name str)
        created     (invoke task method-name [])
        [attrs _] (parse-args (:args nested))
        created-acs (accessors-m (class created))]
    ;; and now set attrs on the created instance
    (doseq [[attr-name value] attrs]
      (set-attribute created-acs created attr-name value))
    ;;TODO: nested
    ))                                                      ;;assume no params...TODO: better

(defn add-nested-type [acs task nested]
  ;(println "adding nested type" (display-name nested) "on" (display-name task))
  (let [adders       (:adders acs)
        nested-class (class nested)
        adder        (first                                 ;;if we find more than one we are in trouble
                       (filter
                         (fn [a]
                           (let [types (:parameter-types a)]
                             (and (= (count types) 1)       ;only consider one-param adders
                                  (= nested-class (resolve (first types))))))
                         adders))
        adder-name   (str (:name adder))]
    ;    (prn :adder adder :name adder-name :type (class adder-name) :after (str adder-name) nested-clazz)
    (invoke task adder-name [nested])))

(defn add-nested [acs task nested]
  (if (instance? Map nested)
    (add-nested-create acs task nested)
    (add-nested-type acs task nested)))

(defn prepare-type [clazz args]
  (let [[attrs nested] (parse-args args)
        task (.newInstance clazz)
        acs  (accessors-m clazz)
        prj  (create-project-m)]
    (when (get-in acs [:setters :project])
      (set-attribute acs task :project prj))
    (doseq [[attr-name value] attrs]
      (set-attribute acs task attr-name value))
    (doseq [n nested]
      (add-nested acs task n))
    task))

(defn execute-task [clazz args]
  (let [task (prepare-type clazz args)]
    (println "executing task" (display-name task))
    (.execute task)))


;<copy todir="../backup/dir">
;  <fileset dir="src_dir"/>
;  <filterset>
;    <filter token="TITLE" value="Foo Bar"/>
;  </filterset>
;</copy>


(comment
  ; ==>
  (copy :todir "../backup/dir"
        (fileset :dir "src_dir")
        (filterset
          (filter :token "TITLE" :value "Foo Bar")))

  ;<copy todir= "../dest/dir" >
  ;  <fileset dir= "src_dir" >
  ;    <exclude name= "**/*.java" />
  ;  </fileset>
  ;</copy>

  )

;  public synchronized NameEntry createExclude() {

(def mappings
  {:copy    Copy
   :fileset FileSet})

(defn exclude [& args]
  (parse-args :exclude args))

(defn include [& args]
  (parse-args :include args))

(defn filterset [& args]
  (parse-args :filterset args))

(defn fileset [& args]
  (parse-args :fileset args))

(comment
  (def REMEMBER "./src/main/org/apache/tools/ant/types/defaults.properties")
  
  )
(defn copy
  "Example code:

    (ant
      (copy todir=\"/tmp\"
        (fileset dir=\".\" includes=\"**/*.java\")))

  valid attributes:
  
    :preservelastmodified :tofile :todir :overwrite :force
    :filtering :flatten :includeEmptyDirs :failonerror :quiet
    :verbose :encoding :outputencoding :enablemultiplemappings
    :granularity

  valid nested elements:

    fileset filenamemapper resourcecollection
    
  https://ant.apache.org/manual/Tasks/copy.html"
  [& args]
  (parse-args :copy args))

(comment
  (defn file? [o] (instance? File o))

  (s/def ::attr-value (s/or ::string-value string?
                            ::file-value file?))
  (s/def ::attr (s/cat ::name keyword?
                       ::value ::attr-value))
  (s/def ::nested (s/cat ::n map?))
  (s/def ::args (s/cat ::attrs (s/* ::attr) ::nesteds (s/* ::nested)))

  ; https://stackoverflow.com/questions/43256665/realistic-clojure-spec-for-function-with-named-arguments
  (s/fdef copy
          :args (s/cat
                  ::attrs
                  (s/* (s/cat ::name
                              (s/keys :opt-un [::todir ::tofile ::preservelastmodified ::overwrite
                                               ::force ::filtering ::flatten ::includeEmptyDirs
                                               ::failonerror ::quiet ::verbose ::encoding
                                               ::outputencoding ::enablemultiplemappings
                                               ::g])
                              ::value ::attr-value))
                  ::nesteds (s/* ::nested))
          :ret map?)
  )

;; Main.java:762
(def ant [& args]
  (let [ch (ComponentHelper/getComponentHelper (create-project))
        _  (.initDefaultDefinitions ch)]
  )

(comment

  (time
    (copy :todir "/tmp"
          (fileset :dir "."
                   (include :name "ant/**/*.sh")
                   (exclude :name "**/etc/**")
                   (exclude :name "**/lib/**")
                   (exclude :name "**/manual/**")
                   (exclude :name "**/src/**"))))

  )





















