(ns clj-ant.core
  (:require [clojure.reflect :as reflect]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [clojure.data.xml :as xml])
  (:import (org.apache.tools.ant.taskdefs Copy)
           (java.io File ByteArrayInputStream)
           (org.apache.tools.ant.types FileSet)
           (org.apache.tools.ant Project ProjectHelper NoBannerLogger CljAntMain)
           (java.util Map)
           (java.nio.charset StandardCharsets)
           (java.nio.file.spi FileSystemProvider)
           (java.nio.file FileSystem Path)
           (org.apache.tools.ant.helper ProjectHelper2)
           (java.lang.reflect Method)
           (cljant CljAntProjectHelper CljAntBuildFile)))

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

(defn exclude [& args]
  (parse-args :exclude args))

(defn include [& args]
  (parse-args :include args))

(defn filterset [& args]
  (parse-args :filterset args))

(defn fileset [& args]
  (parse-args :fileset args))

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


(defn run-ant [build-file-str ant-args extra-props classloader]
  (let [bf   (CljAntBuildFile. "clj-ant-build" build-file-str (io/file "."))
        main (CljAntMain. bf)]
    (CljAntProjectHelper/register)
    (.startAnt main (into-array String (:options ant-args)) extra-props classloader)))

;(set-private-field main "buildFile" bf)
;(set-private-field main "readyToRun" true)
;(call-private-method  main "runBuild" nil))) ;(make-array String 0) nil nil)))
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

(defn convert-to-xml [ant-args & nested]
  (let [prj-attrs (select-keys ant-args [:default :name :basedir])]
    ;(xml/emit
    (xml/indent-str
      (xml/element :project prj-attrs nested))))



(defn ant
  "The main entry point to the clj-ant api. The ant function is
  the only function which actually executes any ant tasks/types
  in the clj-ant api. Specifically in an expression like:

  (ant
    (copy :todir \"/tmp\"
      (fileset :dir \"src\"
        (include :name \"main/**/*.clj\"))))

  the functions 'copy', 'fileset', and 'include' only return data
  structures which are then executed by the ant function. 

  Please note that every effort has been made to make this api self
  documenting and applicable for repl driven development. Thus things
  like `(doc fileset)` should return a decent explanation of the
  available arguments and nested elements.

  In addition to nested function calls, ant accepts the following
  keyword arguments:

  Arguments normally defined on the <project ...> element in ant:

    :default - string. Default target to call. Note that tasks added
    directly under the ant element will be added to an implicit target
    and executed when no default or explicit target is defined.

    :name - string. The name of the project.

    :basedir - string or File. The base directory from which all
    path calculation are done .

    https://ant.apache.org/manual/using.html#projects

  :options
    A coll of string options which would normally be provided to
    ant as command line options. Example:

    (ant :options [\"-d\"])

    would turn on debugging output. Please run:

    (ant :options [\"--help\"])

    for a full list of available options.

   Will return a map with the following structure:

   {:tasks - coll of executed task instances
    :targets -  
  "
  [& args]
  (let [[attrs nested] (partition-args args)
        prj-keys  [:default :name :basedir]
        prj-attrs (select-keys attrs prj-keys)
        ant-attrs (apply dissoc attrs prj-keys)
        xml       (apply convert-to-xml prj-attrs nested)]
    (run-ant xml ant-attrs nil nil)))

; anyway, scsh is quite complex and does a lot of cool stuff, there's
; like a couple of nice ideas you can steal from it
; https://github.com/ChaosEternal/guile-scsh
; https://gist.github.com/noisesmith/06102f38f14bad40ebe4a04f79f5dfda
; https://gist.github.com/noisesmith/684e09a77ca4390f03fd2e53af1d5464

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

(comment
  (def REMEMBER "./src/main/org/apache/tools/ant/types/defaults.properties")

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

