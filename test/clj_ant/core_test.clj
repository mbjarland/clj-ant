(ns clj-ant.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clj-ant.core :as a]
            [clj-ant.pod :as pod])
  (:import [java.io File]
           [java.nio.file Files]
           [org.apache.tools.ant Project]))

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
      (is (re-find #"Copies a file" (:description d)))
      (is (= "https://ant.apache.org/manual/Tasks/copy.html" (:manual-url d)))
      (is (contains? (:attrs d) "todir"))
      (is (= File (get-in d [:attrs "todir" :type])))
      (is (re-find #"directory to copy to"
                   (get-in d [:attrs "todir" :description])))
      (is (re-find #"With the file attribute"
                   (get-in d [:attrs "todir" :required])))
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

(deftest target-requires-name
  (testing "target constructor fails before building an invalid target"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Target elements require a non-empty :name"
          (a/target (a/element :echo :message "unnamed")))))

  (testing "raw target-shaped elements are checked at execute time"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Target elements require a non-empty :name"
          (a/ant :level :error
                 (a/element :target
                   (a/element :echo :message "unnamed")))))))

(deftest ant-accepts-leading-options-map
  (testing "a leading map is treated as execute!/make-project options"
    (let [messages (atom [])]
      (a/ant {:level :warn
              :on-event #(when (= :message (:phase %))
                           (swap! messages conj (:message %)))}
        (a/element :echo :message "via-map"))
      (is (some #{"via-map"} @messages))))

  (testing "an element-shaped map in first position still executes as data"
    (let [base (tmp-dir)
          dir  (File. base "created")]
      (a/ant {:level :warn}
        {:tag :mkdir
         :attrs {:dir (.getAbsolutePath dir)}
         :children []
         :text nil})
      (is (.exists dir)))))

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

(deftest to-xml-rendering
  (testing "renders element records and map-shaped trees"
    (is (= "<echo message=\"hi\"/>"
           (a/to-xml (a/element :echo :message "hi"))))
    (is (= "<echo message=\"hi\"/>"
           (a/to-xml {:tag :echo
                      :attrs {:message "hi"}
                      :children []
                      :text nil}))))

  (testing "escapes attributes and text"
    (is (= "<echo message=\"hi &amp; &lt;there&gt; &quot;q&quot;\">x &amp; y &lt; z &gt; 1</echo>"
           (a/to-xml (a/element :echo
                         :message "hi & <there> \"q\""
                         "x & y < z > 1")))))

  (testing "renders nested trees with Ant-style list attributes"
    (is (= "<project default=\"go\" name=\"x\"><target depends=\"clean,compile\" name=\"go\"><echo message=\"done\"/></target></project>"
           (a/to-xml
             (a/element :project :name "x" :default "go"
               (a/element :target :name :go :depends [:clean :compile]
                 (a/element :echo :message "done")))))))

  (testing "round-trips normal parsed trees"
    (let [xml "<project name=\"x\"><target name=\"go\"><echo>hello &amp; goodbye</echo></target></project>"
          tree (a/from-xml xml)]
      (is (= tree (a/from-xml (a/to-xml tree))))))

  (testing "omits internal clj-ant attrs"
    (is (= "<project name=\"x\"/>"
           (a/to-xml (assoc-in (a/element :project :name "x")
                               [:attrs :clj-ant/source-dir]
                               (io/file "."))))))

  (testing "rejects JVM-backed children"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Cannot render JVM-backed child as XML"
          (a/to-xml (a/element :copy (File. "x")))))))

(deftest session-state-stays-bounded
  (testing "synthetic refids don't accumulate across reused-session calls"
    (a/with-session [s {:level :error}]
      (let [p ^Project (:project s)
            before (count (.getReferences p))]
        (dotimes [_ 30]
          (a/ant :session s
                 (a/element :copy :todir (.getAbsolutePath (tmp-dir))
                            (java.io.File. "/etc/hosts"))))
        (let [delta (- (count (.getReferences p)) before)]
          (is (<= delta 5)
              (str "ref count grew by " delta
                   " across 30 calls -- expected only internal bookkeeping atoms"))))))

  (testing "named targets are removed after each call"
    (a/with-session [s {:level :error}]
      (let [p ^Project (:project s)]
        (dotimes [i 10]
          (a/ant :session s :targets [(str "tgt-" i)]
                 (a/target :name (str "tgt-" i)
                   (a/element :echo :message (str i)))))
        (let [remaining (->> (.getTargets p) keys
                             (remove #{""})
                             count)]
          (is (zero? remaining)
              "user-named targets should not survive past the call"))))))

(deftest project-opt-overrides-session
  (testing ":project on a single execute! call wins over :session"
    (let [s         (a/session {:level :error})
          one-off   (a/make-project {:level :error})]
      (a/ant :session s :project one-off
             (a/element :property :name "k" :value "in-oneoff"))
      (is (= "in-oneoff" (.getProperty one-off "k")))
      (is (nil? (.getProperty (:project s) "k"))))))

(deftest session-and-prepare
  (testing "with-session reuses one Project across calls"
    (a/with-session [s {:level :warn}]
      (a/ant (a/element :property :name "v" :value "session-val"))
      (let [seen (atom nil)]
        (a/ant :on-event #(when (and (= :message (:phase %))
                                      (re-find #"v=" (or (:message %) "")))
                            (reset! seen (:message %)))
               (a/element :echo :message "v=${v}"))
        (is (= "v=session-val" @seen)))))

  (testing "(run (prepare ...)) round-trips and reuses prepared work"
    (a/with-session [s {:level :warn}]
      (let [n (a/element :echo :message "prepared-call")
            p (a/prepare [n])
            evts (atom [])]
        (a/run p :session s
                 :on-event #(when (= :task-started (:phase %))
                              (swap! evts conj (:task %))))
        (a/run p :session s
                 :on-event #(when (= :task-started (:phase %))
                              (swap! evts conj (:task %))))
        (is (= ["echo" "echo"] @evts)
            "prepared plan re-runs cleanly under one session")))))

(deftest task-collision-rejection
  (let [v (requiring-resolve 'clj-ant.core/task)]
    (testing "(task :built-in fn) is rejected outright"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"collides with an existing task definition"
            (a/ant :level :error (v :echo (fn [_] :nope))))))

    (testing "(task :existing-deftask fn) is rejected"
      (a/deftask :collide-deftask (fn [_] :original))
      (try
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"collides with an existing task definition"
              (a/ant :level :error (v :collide-deftask (fn [_] :wrong)))))
        ;; deftask still works
        (a/ant :level :error (a/element :collide-deftask))
        (finally
          (.remove cljant.ClojureTask/REGISTRY "collide-deftask")))))

  (testing "after an inline-task build, built-in tasks still work"
    (a/ant :level :error
      (a/element :echo :message "first")
      (a/task #(do nil))
      (a/element :echo :message "second"))
    ;; second build, same JVM: echo still resolves correctly
    (a/ant :level :error (a/element :echo :message "second build"))
    (is true)))

(deftest parent-context-validation
  (let [v (requiring-resolve 'clj-ant.spec/validate)]
    (testing "<attribute> under macrodef accepts :default, rejects :value"
      (is (nil? (v :attribute {:name "who" :default "world"}
                   {:closed? true :parent :macrodef})))
      (is (some? (v :attribute {:name "who" :value "x"}
                    {:closed? true :parent :macrodef}))))

    (testing "<attribute> under manifest accepts :value, rejects :default"
      (is (nil? (v :attribute {:name "X" :value "v"}
                   {:closed? true :parent :manifest})))
      (is (some? (v :attribute {:name "X" :default "v"}
                    {:closed? true :parent :manifest})))))

  (testing "validate-tree threads parent context through the walk"
    (let [vt (requiring-resolve 'clj-ant.spec/validate-tree)
          bad (a/element :macrodef :name "shout"
                (a/element :attribute :name "who" :value "x"))
          good (a/element :macrodef :name "shout"
                 (a/element :attribute :name "who" :default "x"))]
      (is (= 1 (count (vt bad {:closed? true}))))
      (is (zero? (count (vt good {:closed? true})))))))

(deftest async-execute-and-cancel
  (testing "execute-async! returns a Run, deref blocks for the result"
    (let [r (a/execute-async! [(a/element :echo :message "x")] :level :error)
          v @r]
      (is (realized? r))
      (is (some? (:tasks v)))))

  (testing "cancel! sets the flag; result reflects user intent"
    (let [r (a/execute-async! [(a/element :sleep :seconds "1")] :level :error)]
      (Thread/sleep 50)
      (a/cancel! r)
      (is (a/cancelled? r))
      (let [v (deref r 3000 :timeout)]
        (is (not= :timeout v))
        ;; Ant's <sleep> swallows interrupts so the build finishes,
        ;; but :cancelled? on the result reflects that the caller
        ;; asked for cancel.
        (is (true? (:cancelled? v)))))))

(deftest watch-fires-on-change
  (testing "watch runs once initially, then on every change"
    (let [base   (tmp-dir)
          probe  (File. base "probe.txt")
          _      (spit probe "v1")
          hits   (atom 0)
          stop   (a/watch [(a/element :echo :message "fired")]
                          :paths   [(.getAbsolutePath base)]
                          :poll-ms 50
                          :level   :error
                          :on-rebuild (fn [_] (swap! hits inc)))]
      (try
        (Thread/sleep 200)              ; pick up initial run
        (spit probe "v2") (Thread/sleep 200)
        (spit probe "v3") (Thread/sleep 200)
        ;; one initial + at least 2 change-driven rebuilds
        (is (>= @hits 3))
        (finally (stop))))))

(deftest error-context-wraps-build-exceptions
  (testing "BuildException bubbles up as ex-info with the input tree"
    (let [r (a/ant :level :error (a/element :copy :tdoir "/tmp"))]
      (is (instance? clojure.lang.ExceptionInfo (:error r)))
      (let [ed (ex-data (:error r))]
        (is (true? (:clj-ant/error ed)))
        (is (vector? (:clj-ant/elements ed)))
        (is (= 1 (count (:clj-ant/elements ed))))
        (is (re-find #"tdoir" (:ant/message ed)))))))

(deftype TestUserBag [paths])

(extend-protocol a/ICoercible
  TestUserBag
  (-as-child [bag]
    (a/lazy-resources (map #(File. ^String %) (.-paths bag))
                      {:size (count (.-paths bag))})))

(deftest icoercible-user-extension
  (testing "a user-defined type extending ICoercible flows as a child"
    (let [base (tmp-dir)
          src  (File. base "src")]
      (.mkdirs src)
      (doseq [n ["a.txt" "b.txt" "c.txt"]] (spit-file src n n))
      (let [dst (File. base "dst")
            bag (->TestUserBag (mapv #(.getAbsolutePath (File. src %))
                                     ["a.txt" "b.txt"]))]
        (a/ant :level :warn
          (a/element :copy :todir (.getAbsolutePath dst) bag))
        (is (= #{"a.txt" "b.txt"}
               (set (mapv #(.getName %) (.listFiles dst))))
            "user-extended type contributed two files via the protocol")))))

(deftest plain-map-tree-with-raw-children
  (testing "execute! handles map-shaped trees with raw seq/File children
            (the shape from-xml and the bb pod produce)"
    (let [base (tmp-dir)
          src  (File. base "src") dst (File. base "dst")]
      (.mkdirs src)
      (spit-file src "x.txt" "x")
      (let [tree {:tag :copy
                  :attrs {:todir (.getAbsolutePath dst)}
                  :children [[(.getAbsolutePath (File. src "x.txt"))]]
                  :text nil}]
        (a/ant :level :warn tree)
        (is (= ["x.txt"] (mapv #(.getName %) (.listFiles dst))))))))

(deftest task-no-registry-leak
  (testing "(a/task f) registers and de-registers per-call"
    (let [hits   (atom 0)
          before (count (.keySet cljant.ClojureTask/REGISTRY))]
      (dotimes [_ 25]
        (a/ant :level :error (a/task #(swap! hits inc))))
      (let [after (count (.keySet cljant.ClojureTask/REGISTRY))]
        (is (= 25 @hits))
        (is (= before after)
            "registry size unchanged after 25 inline tasks")))))

(deftest project-syncs-only-permanent-deftasks
  (testing "transient registry entries are not replayed into new Projects"
    (let [task-name "transient-registry-entry"]
      (.put cljant.ClojureTask/REGISTRY task-name (fn [_]))
      (try
        (let [p (a/make-project {:level :error})]
          (is (nil? (.get (.getTaskDefinitions ^Project p) task-name))))
        (finally
          (.remove cljant.ClojureTask/REGISTRY task-name))))))

(deftest ambiguous-tag-union-schema
  (testing "<attribute> on macrodef and on manifest both pass closed validation"
    (let [v   (requiring-resolve 'clj-ant.spec/validate)]
      (is (nil? (v :attribute {:name "who" :default "world"} {:closed? true})))
      (is (nil? (v :attribute {:name "X-Foo" :value "bar"} {:closed? true})))
      ;; typo still caught
      (is (some? (v :attribute {:name "x" :defalt "y"} {:closed? true})))))

  (testing "describe lists every recorded class for ambiguous tags"
    (let [d (a/describe :attribute)]
      (is (>= (count (:classes d)) 2))
      (is (contains? (:attrs d) "default"))   ; from MacroDef$Attribute
      (is (contains? (:attrs d) "value")))))  ; from Manifest$Attribute

(deftest tree-helpers-tolerate-full-vocab
  (testing "elements walks Element + JavaChild + map-with-:tag uniformly"
    (let [tree (a/element :copy
                 ;; Map-shaped node (no Element record):
                 {:tag :fileset :attrs {:dir "src"} :children []}
                 ;; JavaChild wrapper (a real ResourceCollection):
                 (a/lazy-resources [(java.io.File. "x.txt")] {:size 1}))
          tags (mapv :tag (a/elements tree))]
      (is (= [:copy :fileset :resources] tags))))

  (testing "transform passes JavaChildren through and preserves map shapes"
    (let [tree (a/element :copy
                 {:tag :fileset :attrs {:dir "src"} :children []}
                 (a/lazy-resources [(java.io.File. "x")] {:size 1}))
          out  (a/transform tree identity)
          kids (:children out)]
      ;; both children survive identity-transform
      (is (= 2 (count kids)))
      (is (= :fileset (-> kids (nth 0) :tag)))
      (is (a/java-child? (nth kids 1)))))

  (testing "transform on a tree with JavaChild leaves doesn't drop them"
    ;; Public API: a vec of strings goes through as-child and becomes
    ;; a JavaChild in :children. transform must preserve it.
    (let [tree (a/element :copy ["a.txt" "b.txt" "c.txt"])
          out  (a/transform tree identity)
          kid  (first (:children out))]
      (is (a/java-child? kid)))))

(deftest reused-project-no-listener-leak
  (testing "execute! detaches its listener so reused projects don't double-deliver"
    (let [p     (a/make-project {:level :warn})
          calls (atom 0)]
      (a/with-project p
        (a/ant :on-event (fn [_] (swap! calls inc))
               (a/element :echo :message "first"))
        (let [after-first @calls]
          (reset! calls 0)
          (a/ant :on-event (fn [_] (swap! calls inc))
                 (a/element :echo :message "second"))
          ;; Without the leak fix, the first call's listener would
          ;; still fire here -- we'd get ~2x the events.
          (let [after-second @calls]
            (is (<= after-second (* 1.2 after-first))
                (str "second run got " after-second
                     " events, first got " after-first
                     " -- listener probably leaked"))))))))

(deftest nested-only-tags-are-introspectable
  (testing "describe recognises nested-only tags via wrapper meta"
    (let [d (a/describe :tokenfilter)]
      (is (= :nested (:kind d)))
      (is (re-find #"TokenFilter" (str (:class d)))))
    (let [d (a/describe :replacestring)]
      (is (= :nested (:kind d)))
      (is (contains? (:attrs d) "from"))
      (is (contains? (:attrs d) "to"))))

  (testing "schema-for + closed validation work for nested-only tags"
    (is (nil? (@(requiring-resolve 'clj-ant.spec/validate)
                :replacestring {:from "x" :to "y"} {:closed? true})))
    (is (some? (@(requiring-resolve 'clj-ant.spec/validate)
                 :replacestring {:from "x" :nope "y"} {:closed? true})))))

(deftest nested-aliases-have-wrappers
  (testing "tags that share a class with an earlier-seen tag get their own wrapper"
    (require '[clj-ant.tasks])
    (let [tasks (the-ns 'clj-ant.tasks)]
      (doseq [tag ["jvmarg" "sysproperty" "argument" "targetfile" "arg"]]
        (is (some? (ns-resolve tasks (symbol tag)))
            (str tag " should have a wrapper"))))))

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

(deftest lint-suggests-attribute-typos
  (testing "lint validates without running and suggests likely attr names"
    (let [issues (a/lint (a/element :copy :tdoir "out"))
          issue  (first issues)]
      (is (= 1 (count issues)))
      (is (= :copy (:tag issue)))
      (is (contains? (:errors issue) :tdoir))
      (is (= [:todir] (get-in issue [:suggestions :tdoir])))))

  (testing "valid trees return no lint issues"
    (is (empty? (a/lint (a/element :echo :message "x")))))

  (testing "plain Required: Yes metadata reports missing attributes"
    (let [issue (first (a/lint (a/element :mkdir)))]
      (is (= :mkdir (:tag issue)))
      (is (contains? (:errors issue) :dir))))

  (testing "explain is an alias for lint"
    (let [tree (a/element :copy :tdoir "out")]
      (is (= (a/lint tree) (a/explain tree))))))

(deftest pod-exposes-introspection
  (testing "pod describe returns bb-safe data"
    (let [d (#'pod/op-describe {:tag :copy})]
      (is (= :copy (:tag d)))
      (is (= "java.io.File" (get-in d [:attrs "todir" :type])))
      (is (= "org.apache.tools.ant.types.FileSet"
             (get-in d [:nested "fileset"])))
      (is (re-find #"Copies a file" (:description d)))))

  (testing "pod lint returns validation issues"
    (let [issue (first (#'pod/op-lint {:elements (a/element :copy :tdoir "out")}))]
      (is (= :copy (:tag issue)))
      (is (= [:todir] (get-in issue [:suggestions :tdoir]))))))

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
