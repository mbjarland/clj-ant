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

(defn- partition-args [args]
  (reduce
    (fn [[a n] [k v]]
      (cond
        (nil? v) [a (conj n k)]
        (keyword? k) [(assoc a k v) n]
        :else [a (conj (conj n k) v)]))
    [{} []]
    (partition-all 2 args)))

(defn- ant-xml [name args]
  (let [t (Throwable.)
        [attrs nested] (partition-args args)]
    (xml/element name
                 (merge attrs {:trace (fn [] t)})
                 nested)))

(defn- copy
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
  (ant-xml :copy args))

(defn- run-ant [build-file-str ant-args extra-props classloader]
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

(defn- convert-to-xml [ant-args & nested]
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


(load "core_generated")
; anyway, scsh is quite complex and does a lot of cool stuff, there's
; like a couple of nice ideas you can steal from it
; https://github.com/ChaosEternal/guile-scsh
; https://gist.github.com/noisesmith/06102f38f14bad40ebe4a04f79f5dfda
; https://gist.github.com/noisesmith/684e09a77ca4390f03fd2e53af1d5464


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

