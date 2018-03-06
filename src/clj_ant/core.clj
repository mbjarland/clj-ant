(ns clj-ant.core
  (:require [clojure.reflect :as reflect]
            [clojure.java.io :as io])
  (:import (org.apache.tools.ant.taskdefs Copy)
           (java.io File)
           (org.apache.tools.ant.types FileSet FilterSet)
           (org.apache.tools.ant Project ProjectHelper NoBannerLogger)
           (clojure.lang IFn)))

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

(comment
  #clojure.reflect.Method{:name            setQuiet,
                          :return-type     void,
                          :declaring-class org.apache.tools.ant.taskdefs.Copy,
                          :parameter-types [boolean],
                          :exception-types [],
                          :flags           #{:public}}
  )

(defn method-data [m]
  (let [name (name (:name m))
        pre  (apply str (take 3 name))]
    (when (and (#{"add" "set"} pre)
               ((:flags m) :public))
      (-> m
          (assoc :type (if (= pre "add") :adder :setter))
          (assoc :attr (when (= pre "set") (keyword (.toLowerCase (.substring name 3)))))))))


(defn setters-and-adders [clazz]
  (let [s (:members (reflect/type-reflect clazz :ancestors true))]
    (reduce
      (fn [[sm av] m]
        (if (:attr m)
          [(assoc sm (:attr m) m) av]
          [sm (conj av m)]))
      [{} []]
      (keep method-data s))))

(def setters-and-adders-m (memoize setters-and-adders))

(defn destructure-args [args]
  (let [[attrs nested] (partition-by #(not (keyword? (first %)))
                                     (partition-all 2 args))]
    [(apply hash-map (flatten attrs)) (flatten nested)]))

(defmulti coerce-type (fn [to-type value] [to-type (class value)]))
(defmethod coerce-type [File String] [_ value] (io/as-file value))
(defmethod coerce-type :default [_ value] value)

(defn set-attribute [setters task attr-name value]
  (let [setter      (attr-name setters)
        setter-name (str (:name setter))
        param-type  (resolve (first (:parameter-types setter)))] ;;todo multi param
    (clojure.lang.Reflector/invokeInstanceMethod
      task
      setter-name
      (to-array [(coerce-type param-type value)]))))

(defn add-nested [adders task n]
  (if (instance? IFn n)
    (n task)
    (let [nested-clazz (class n)
          adder        (first
                         (filter
                           (fn [m]
                             (let [param-type (resolve (first (:parameter-types m)))]
                               (= nested-clazz param-type)))
                           adders))
          adder-name   (str (:name adder))]
      ;    (prn :adder adder :name adder-name :type (class adder-name) :after (str adder-name) nested-clazz)
      (clojure.lang.Reflector/invokeInstanceMethod
        task
        adder-name
        (to-array [n])))))

(defn prepare-task [clazz args]
  (let [[attrs nested] (destructure-args args)
        task (.newInstance clazz)
        [setters adders] (setters-and-adders-m clazz)]
    (when (:project setters)
      (println "setting project on" (class task))
      (set-attribute setters task :project (create-project)))
    (doseq [[attr-name value] attrs]
      (set-attribute setters task attr-name value))
    (doseq [n nested]
      (add-nested adders task n))
    task))

(defn prepare-modifier [modifier args]
  (let [[attrs nested] (destructure-args args)
        [setters adders] (setters-and-adders-m (class modifier))]
    (doseq [[attr-name value] attrs]
      (set-attribute setters modifier attr-name value))
    (doseq [n nested]
      (add-nested adders nested n))))

(defn execute-task [clazz args]
  (let [task (prepare-task clazz args)]
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
  (fn [parent]
    (prepare-modifier (.createExclude parent) args)))

(defn include [& args]
  (fn [parent]
    (prepare-modifier (.createInclude parent) args)))

(defn filterset [& args]
  (prepare-task FilterSet args))

(defn fileset [& args]
  (prepare-task FileSet args))

(defn copy [& args]
  (execute-task Copy args))






















