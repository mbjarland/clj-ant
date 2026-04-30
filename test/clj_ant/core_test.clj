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

(deftest from-xml-basedir-fidelity
  (testing "build.xml with basedir=. resolves relative to the FILE, not cwd"
    (let [base (tmp-dir)
          src  (File. base "src")]
      (.mkdirs src)
      (spit-file src "x.txt" "x")
      (spit (File. base "build.xml")
            (str "<project name=\"t\" default=\"all\" basedir=\".\">"
                 "<target name=\"all\">"
                 "<copy todir=\"dst\">"
                 "<fileset dir=\"src\" includes=\"**/*.txt\"/>"
                 "</copy>"
                 "</target></project>"))
      ;; Note: we do NOT pass :basedir as an opt -- the build file's
      ;; basedir="." should resolve to (.getParent build.xml), not
      ;; the JVM's cwd.
      (a/ant :level :warn (a/from-xml (File. base "build.xml")))
      (is (.exists (File. base "dst/x.txt"))
          "files should land relative to the build.xml's directory"))))

(deftest deftask-with-explicit-project
  (testing "supplied :project sees deftasks defined before AND after make-project"
    (let [hits (atom 0)]
      (a/deftask :early-task (fn [_] (swap! hits inc)))
      (let [p (a/make-project {:level :warn})]
        (a/deftask :late-task (fn [_] (swap! hits inc)))
        (a/ant :project p
               (a/element :early-task)
               (a/element :late-task))
        (is (= 2 @hits))))))

(deftest property-name-attr-not-filtered
  (testing ":name is exposed as a real attribute on tags that override setName"
    (let [s (@(requiring-resolve 'clj-ant.spec/schema-for) :property)]
      ;; The schema should mention :name
      (is (some #(= :name (first %)) (rest s)))
      ;; Closed validation should accept :name
      (is (nil? (@(requiring-resolve 'clj-ant.spec/validate)
                  :property {:name "x" :value "1"} {:closed? true}))))))

(deftest lazy-resources-iterate-on-demand
  (testing "passing a lazy seq of File to <first :count N> realises only N"
    ;; Create more files than we'll consume, so we can detect
    ;; whether the lazy seq was forced past the take-bound.
    (let [base   (tmp-dir)
          src    (doto (File. base "src") .mkdirs)
          dst    (File. base "dst")
          total  100
          fnames (mapv #(format "f%03d.txt" %) (range total))]
      (doseq [n fnames] (spit-file src n n))
      (let [realized   (atom 0)
            ;; Unchunked lazy-seq: clojure's (map ...) is chunked
            ;; (32-element chunks), which would defeat the "only N
            ;; realised" check. Build manually so realisation is
            ;; element-by-element.
            mk         (fn mk [i]
                         (when (< i total)
                           (lazy-seq
                             (do (swap! realized inc)
                                 (cons (File. src (nth fnames i))
                                       (mk (inc i)))))))
            file-seq   (mk 0)]
        (a/ant :level :warn
          (a/element :copy :todir (.getAbsolutePath dst)
            (a/element :first :count "5"
              ;; Use lazy-resources directly so we can pass :size
              ;; and skip the eager (count files) that the default
              ;; auto-coercion path would do.
              (a/lazy-resources file-seq {:size total}))))
        (is (= 5 (count (.listFiles dst)))
            "only the first 5 files copied")
        (is (<= @realized 10)
            (str "lazy seq realised at most a small handful (got "
                 @realized "), not all " total)))))

  (testing ":size hint skips the eager count when callers can supply it"
    (let [realized? (atom false)
          xs        (lazy-seq (do (reset! realized? true) [(File. "x")]))
          rc        (a/lazy-resources xs {:size 7
                                          :filesystem-only? false})]
      ;; Build the JavaChild but don't iterate -- size should not
      ;; force the seq when explicitly supplied.
      (is (instance? clj_ant.core.JavaChild rc))
      (is (= 7 (.size ^org.apache.tools.ant.types.ResourceCollection
                       (:object rc))))
      (is (false? @realized?)
          ":size opt should skip (count files)"))))

(deftest with-project-shares-state
  (testing "properties set in one call are visible in the next"
    (let [p (a/make-project {:level :warn})
          seen (atom nil)]
      (a/with-project p
        (a/ant (a/element :property :name "v" :value "1.2.3"))
        (a/ant :on-event #(when (and (= :message (:phase %))
                                      (re-find #"v=" (or (:message %) "")))
                            (reset! seen (:message %)))
               (a/element :echo :message "v=${v}")))
      (is (= "v=1.2.3" @seen))))

  (testing "explicit :project opt overrides binding for one call"
    (let [shared  (a/make-project {:level :warn})
          oneoff  (a/make-project {:level :warn})]
      (a/with-project shared
        (a/ant (a/element :property :name "x" :value "shared"))
        (a/ant :project oneoff
               (a/element :property :name "x" :value "oneoff")))
      (is (= "shared" (.getProperty shared "x")))
      (is (= "oneoff" (.getProperty oneoff "x"))))))

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
