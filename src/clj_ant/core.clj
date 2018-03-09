(ns clj-ant.core
  (:require [clojure.reflect :as reflect]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [clojure.data.xml :as xml])
  (:import (org.apache.tools.ant.taskdefs Copy)
           (java.io File ByteArrayInputStream)
           (org.apache.tools.ant.types FileSet FilterSet)
           (org.apache.tools.ant Project ProjectHelper NoBannerLogger ComponentHelper Main ProjectHelperRepository)
           (clojure.lang IFn)
           (java.util Map)
           (java.nio.charset StandardCharsets)
           (java.nio.file.spi FileSystemProvider)
           (java.nio.file FileSystem Path)
           (org.apache.tools.ant.helper ProjectHelper2)
           (java.lang.reflect Method)
           (cljant CljAntProjectHelper CljAntBuildFile CljAntMain)))

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
;    logger.setOutputPrintStream(System.out);(
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
  (let [t (Throwable.)
        [attrs nested] (partition-args args)]
    (xml/element name
                 (merge attrs {:trace (fn [] t)})
                 nested)))

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


(defn string-backed-file [str-data]
  (let [loc "source-file.clj:81"]
    (proxy [File] [(str "antclj:" loc)]
      (hashCode [] CljAntProjectHelper/MAGIC_CLJ_ANT)
      (toString [] (str "string-backed-file> " (proxy-super getPath)))
      (exists [] true)
      (isFile [] true)
      (isDirectory [] false)
      (getParent [] str-data)
      (toPath []
        (println "returning path")
        (proxy [Path] []
          (getFileSystem []
            (println "returning filesystem")
            (proxy [FileSystem] []              
              (provider []
                (println "returning proxy")
                (proxy [FileSystemProvider] []
                  (newInputStream [path options-arr]
                    (ByteArrayInputStream.
                      (.getBytes str-data StandardCharsets/UTF_8))))))))))))

(defn project-helper [build-file]
  (proxy [ProjectHelper2] []
    (parse [project build-file]
      (proxy-super project ()))
    ))

(defn set-private-field [obj field-name value]
  (let [f (.getDeclaredField (class obj) field-name)]
    (.setAccessible f true)
    (.set f obj value)))


(defn call-private-method
  [obj method-name & args]
  (let [^Method m (first (filter #(= method-name (.getName %))
                                 (.getDeclaredMethods (class obj))))]
    (.setAccessible m true)
    (.invoke m obj (into-array Object args))))



(defn run-ant [build-file-str]
  (let [bf  (CljAntBuildFile. "clj-ant-build" build-file-str (io/file "."))
        main (Main.)]
    (CljAntProjectHelper/register)
    (set-private-field main "buildFile" bf)
    (set-private-field main "readyToRun" true)
    (call-private-method  main "runBuild" nil))) ;(make-array String 0) nil nil)))
    ;(call-private-method ant "runBuild" nil)))

(comment
  ; maybe the easiest is to write your own url protocol handler
  ; https://stackoverflow.com/questions/26363573/registering-and-using-a-custom-java-net-url-protocol
  ; and let ProjectHelper2.parse parse your custom url
  ; 
  ; 0. override ProjectHelper2  
  ; 1. get instance of project helper repository
  ; 2. add your instance to the project helper registry
  ; 3. override Resource/getInputStream and return
  ;    (ByteArrayInputStream. (.getBytes str-data StandardCharsets/UTF_8))
  ; 3. call Main/runBuild(null)
  ; 4.
  ;
  ; override the following in ProjectHelper2
  ;public void setDocumentLocator(Locator locator) {
  ;  context.setLocator(locator);
  ;}
  ;
  ; implement your own override of locator which translates from the line number
  ; and system id of the xml to line and column in the clojure file 
  ;
  ;  file.getPath().getFileSystem().provider().newInputStream(path, options);

  )

(defn convert-to-xml [& args]
  ;(xml/emit
  (xml/indent-str
    (xml/element :project {} args)))



(defn ant [& args]
  (let [xml (apply convert-to-xml args)]
    (println "XML" xml)
    (run-ant xml)))

(defn function-two [& args]
  (throw (Throwable.)))

(defn function-one [& args]
  (apply function-two args))

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
