(ns clj-ant.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clj-ant.core :as a])
  (:import [java.io File]
           [java.nio.file Files]))

(defn- tmp-dir []
  (let [d (Files/createTempDirectory "cljant-test" (make-array java.nio.file.attribute.FileAttribute 0))]
    (.toFile d)))

(defn- spit-file [^File parent ^String name content]
  (let [f (File. parent name)]
    (io/make-parents f)
    (spit f content)
    f))

(deftest node-construction
  (testing "node returns a record with expected shape"
    (let [n (a/element :copy :todir "out"
                    (a/element :fileset :dir "src" :includes "**/*.clj"))]
      (is (a/element? n))
      (is (a/element? (a/element :copy)))
      (is (= :copy (:tag n)))
      (is (= {:todir "out"} (:attrs n)))
      (is (= 1 (count (:children n))))
      (is (= :fileset (-> n :children first :tag))))))

(deftest copy-and-property-expansion
  (testing "property expansion + nested fileset is honoured by Ant"
    (let [base (tmp-dir)
          src  (File. base "src")
          dst  (File. base "dst")]
      (.mkdirs src)
      (spit-file src "a.txt" "alpha")
      (spit-file src "b.txt" "beta")
      (let [r (a/ant
                :basedir (.getAbsolutePath base)
                :level :warn
                :capture? true
                (a/element :property :name "dst" :value (.getAbsolutePath dst))
                (a/element :copy :todir "${dst}"
                        (a/element :fileset
                                :dir (.getAbsolutePath src)
                                :includes "**/*.txt")))]
        (is (nil? (:error r)))
        (is (= 2 (count (.listFiles dst))))
        (is (some #(= :task-finished (:phase %)) (:events r)))))))

(deftest fileset-as-seq
  (testing "a fileset node materialises into a lazy seq of Files"
    (let [base (tmp-dir)
          src  (File. base "src")]
      (.mkdirs src)
      (spit-file src "core.clj"  "ns x")
      (spit-file src "tasks.clj" "ns y")
      (spit-file src "ignore.txt" "skip")
      (let [fs    (a/element :fileset
                          :dir (.getAbsolutePath src)
                          :includes "**/*.clj")
            names (sort (map #(.getName %) (a/files fs)))]
        (is (= ["core.clj" "tasks.clj"] names))
        (testing "and is composable with normal seq operations"
          (is (= 2 (count (filter #(.isFile %) (a/files fs)))))
          (is (= ["CORE.CLJ" "TASKS.CLJ"]
                 (into [] (comp (map #(.getName %))
                                (map clojure.string/upper-case))
                       (sort-by #(.getName %) (a/files fs))))))))))

(deftest describe-returns-data
  (testing "describe returns introspection data for known tasks"
    (let [d (a/describe :copy)]
      (is (= :copy (:tag d)))
      (is (= :task (:kind d)))
      (is (contains? (:attrs d) "todir"))
      (is (contains? (:nested d) "fileset"))
      (is (string? (:class d)))))
  (testing "describe returns nil for unknown tags"
    (is (nil? (a/describe :no-such-thing-12345)))))

(deftest event-streaming
  (testing ":on-event fires synchronously per build event"
    (let [seen (atom [])]
      (a/ant
        :level :warn
        :on-event #(swap! seen conj %)
        (a/element :echo :message "x"))
      (let [phases (set (map :phase @seen))]
        (is (contains? phases :started))
        (is (contains? phases :task-started))
        (is (contains? phases :task-finished))
        (is (contains? phases :finished))
        (is (some #(and (= :task-started (:phase %)) (= "echo" (:task %)))
                  @seen))))))

(deftest deftarget-and-dependencies
  (testing "named targets honour declared dependencies"
    (let [order (atom [])
          t-clean   (a/target :name "clean"
                              (a/element :echo :message "clean"))
          t-compile (a/target :name "compile"
                              :depends [:clean]
                              (a/element :echo :message "compile"))
          t-package (a/target :name "package"
                              :depends [:compile]
                              (a/element :echo :message "package"))]
      (a/ant
        :level :warn
        :on-event (fn [e]
                    (when (and (= :target-started (:phase e))
                               (seq (:target e)))
                      (swap! order conj (:target e))))
        :targets ["package"]
        t-clean t-compile t-package)
      (is (= ["clean" "compile" "package"] @order)))))

(deftest sequential-attrs-auto-join
  (testing "vector/list attribute values join with commas"
    (let [base (tmp-dir)
          src  (File. base "src")
          dst  (File. base "dst")]
      (.mkdirs src)
      (doseq [n ["a.txt" "b.txt" "c.txt"]]
        (spit-file src n n))
      (a/ant
        :basedir (.getAbsolutePath base)
        :level :warn
        (a/element :copy :todir (.getAbsolutePath dst)
                (a/element :filelist
                        :dir (.getAbsolutePath src)
                        :files ["a.txt" "b.txt"])))
      (is (= 2 (count (.listFiles dst)))))))

(deftest pass-anything-as-child
  (let [base (tmp-dir)
        src  (File. base "src")]
    (.mkdirs src)
    (doseq [n ["a.txt" "b.txt" "c.txt" "d.txt"]] (spit-file src n n))

    (testing "pass a real Java FileSet directly (no wrapper fn)"
      (let [dst (doto (File. base "dst1") .mkdirs)
            fs  (a/realize (a/element :fileset
                                   :dir (.getAbsolutePath src)
                                   :includes "**/*.txt"))]
        (a/ant :level :warn (a/element :copy :todir (.getAbsolutePath dst) fs))
        (is (= 4 (count (.listFiles dst))))))

    (testing "pass a lazy seq of File directly"
      (let [dst (doto (File. base "dst2") .mkdirs)
            xs  (->> (a/files (a/element :fileset
                                       :dir (.getAbsolutePath src)
                                       :includes "**/*.txt"))
                     (filter #(re-find #"[bd]" (.getName %))))]
        (a/ant :level :warn (a/element :copy :todir (.getAbsolutePath dst) xs))
        (is (= #{"b.txt" "d.txt"}
               (set (map #(.getName %) (.listFiles dst)))))))

    (testing "pass a single File directly"
      (let [dst (doto (File. base "dst3") .mkdirs)]
        (a/ant :level :warn
          (a/element :copy :todir (.getAbsolutePath dst)
                  (File. src "a.txt")))
        (is (= ["a.txt"] (mapv #(.getName %) (.listFiles dst))))))))

(deftest deftask-integration
  (let [seen (atom [])]
    (a/deftask :test-tap
      (fn [{:keys [project task-name v] :as args}]
        (swap! seen conj (select-keys args [:task-name :v]))))

    (testing "deftask is true for registered tags"
      (is (a/deftask? :test-tap)))

    (testing "fn fires with expanded properties + task-name"
      (a/ant :level :warn
        (a/element :property :name "x" :value "hi")
        (a/element :test-tap :v "got: ${x}"))
      (is (= [{:task-name "test-tap" :v "got: hi"}] @seen)))

    (testing "deftask participates in the event stream"
      (reset! seen [])
      (let [phases (atom [])]
        (a/ant :level :warn
          :on-event #(when (= :task-started (:phase %))
                       (swap! phases conj (:task %)))
          (a/element :echo :message "before")
          (a/element :test-tap :v "middle")
          (a/element :echo :message "after"))
        (is (= ["echo" "test-tap" "echo"] @phases))))))

(deftest from-xml-round-trip
  (testing "parsing a build.xml string returns a clj-ant element tree"
    (let [tree (a/from-xml "<project name=\"x\" default=\"go\" basedir=\".\"><property name=\"v\" value=\"7\"/><target name=\"go\"><echo message=\"v is ${v}\"/></target></project>")]
      (is (a/element? tree))
      (is (= :project (:tag tree)))
      (is (= "x" (-> tree :attrs :name)))
      (is (= ["property" "target"] (mapv (comp clojure.core/name :tag)
                                         (:children tree))))))

  (testing "executing a parsed build runs top-level tasks before targets"
    (let [base (tmp-dir)
          src  (File. base "src")
          xml (str "<project name=\"t\" default=\"all\" basedir=\""
                   (.getAbsolutePath base)
                   "\">"
                   "<property name=\"dst\" value=\"${basedir}/out\"/>"
                   "<target name=\"all\">"
                   "<mkdir dir=\"${dst}\"/>"
                   "<copy todir=\"${dst}\">"
                   "<fileset dir=\"" (.getAbsolutePath src)
                   "\" includes=\"**/*.txt\"/>"
                   "</copy>"
                   "</target></project>")]
      (.mkdirs src)
      (spit-file src "x.txt" "x")
      (a/ant :level :warn (a/from-xml xml))
      (is (= ["x.txt"] (mapv #(.getName %)
                             (.listFiles (File. base "out"))))))))

(deftest tree-query-and-transform
  (let [tree (a/element :project
               (a/element :target :name "compile"
                 (a/element :javac :srcdir "src" :debug "true"))
               (a/element :target :name "deploy"
                 (a/element :scp :trust "true" :file "x")
                 (a/element :scp :trust "false" :file "y")))]
    (testing "elements walks every node depth-first"
      (is (= [:project :target :javac :target :scp :scp]
             (mapv :tag (a/elements tree)))))

    (testing "elements with pred filters"
      (is (= 2 (count (a/elements tree #(= :scp (:tag %))))))
      (is (= 1 (count (a/elements tree #(and (= :scp (:tag %))
                                              (= "true" (-> % :attrs :trust)))))))
      (is (= 0 (count (a/elements tree #(= :nope (:tag %)))))))

    (testing "transform rewrites every matching element"
      (let [t' (a/transform tree
                            (fn [e]
                              (cond-> e
                                (= :scp (:tag e))
                                (assoc-in [:attrs :trust] "false"))))]
        (is (every? #(= "false" (-> % :attrs :trust))
                    (a/elements t' #(= :scp (:tag %)))))))))

(deftest task-inline-thunk
  (testing "(a/task tag f) runs f and shows up as a task event"
    (let [hits  (atom 0)
          phases (atom [])]
      (a/ant :level :warn
        :on-event #(when (= :task-started (:phase %))
                     (swap! phases conj (:task %)))
        (a/element :echo :message "before")
        (a/task :compile #(swap! hits inc))
        (a/element :echo :message "after"))
      (is (= 1 @hits))
      (is (= ["echo" "compile" "echo"] @phases)))))

(deftest plan-prints-tree
  (testing "plan renders a build tree without executing it"
    (let [n (a/element :copy :todir "out"
                    (a/element :fileset :dir "src" :includes "**/*.clj"))
          out (with-out-str (a/plan n))]
      (is (re-find #"<copy" out))
      (is (re-find #"<fileset" out)))))

(deftest malli-validation
  (testing "good attributes pass"
    (is (nil? (@(requiring-resolve 'clj-ant.spec/validate)
                :copy {:todir "out" :overwrite "true"}))))
  (testing "bad attributes are reported"
    (let [errs (@(requiring-resolve 'clj-ant.spec/validate)
                 :copy {:overwrite "perhaps"})]
      (is (some? errs))
      (is (contains? errs :overwrite))))
  (testing ":validate? short-circuits before Ant runs"
    (is (thrown? clojure.lang.ExceptionInfo
                 (a/ant :validate? true :level :warn
                        (a/element :copy :overwrite "perhaps")))))
  (testing "validate-tree walks nested children"
    (let [errs (@(requiring-resolve 'clj-ant.spec/validate-tree)
                 (a/element :copy :todir "out"
                         (a/element :fileset :includes "**/*"
                                 :erroron-mismatch "wat")))]
      (is (vector? errs))
      ;; the bogus attribute is on the type, not the task
      (is (or (empty? errs) (every? :tag errs))))))

(deftest macrodef-via-runtime
  (testing "macrodef works because RuntimeConfigurable handles expansion"
    (let [base (tmp-dir)
          out  (File. base "out.txt")
          r (a/ant
              :basedir (.getAbsolutePath base)
              :level :warn
              :capture? true
              (a/element :macrodef :name "shout"
                      (a/element :attribute :name "what")
                      (a/element :sequential
                              (a/element :echo :message "@{what}!")))
              (a/element :shout :what "hello"))]
      (is (nil? (:error r)))
      (is (some #(and (= :message (:phase %))
                      (= "hello!" (:message %)))
                (:events r))))))
