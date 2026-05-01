(ns clj-ant.pod
  "Babashka pod for clj-ant.

  Babashka itself can't load `org.apache.tools.ant` -- Ant relies on
  custom classloaders and a great deal of reflection, neither of which
  the bb sci runtime supports. The standard answer to that mismatch is
  a *pod*: a separate JVM process that exposes a small RPC surface,
  which bb scripts can call as if it were a local namespace.

  Run this pod with one of:

      clojure -M:pod
      java -cp $(clojure -Spath) clj_ant.pod

  Then in a bb script:

      (require '[babashka.pods :as pods])
      (pods/load-pod ['clj-ant.pod])           ; auto-discovered, see manifest
      (require '[clj-ant.tasks :as t]
               '[clj-ant.core  :as a])
      (a/ant (t/echo :message \"hello from bb\"))

  The pod protocol is bencode-over-stdio. We implement just enough of
  it inline -- pods are small enough that pulling in a dependency for
  bencode would be overkill."
  (:require [clojure.edn :as edn]
            [clj-ant.core :as core])
  (:import [java.io OutputStream PushbackInputStream]))

;; ---------------------------------------------------------------------------
;; Bencode (https://en.wikipedia.org/wiki/Bencode)
;;
;; The bb pod protocol uses bencode for the outer envelope but each value
;; that needs structure is itself an edn or json string inside a bencode
;; byte-string. So we only ever emit/parse top-level dicts of byte-strings.

(defn- read-bencode
  "Read one bencode value from `in`. Returns Clojure data:
   * bencode int       -> Long
   * bencode string    -> String (assumed UTF-8)
   * bencode list      -> vector
   * bencode dict      -> sorted-map keyed by String
   Returns ::eof at EOF."
  [^PushbackInputStream in]
  (let [b (.read in)]
    (cond
      (neg? b) ::eof

      (= b (int \i))
      (let [sb (StringBuilder.)]
        (loop []
          (let [c (.read in)]
            (when (neg? c) (throw (ex-info "EOF inside int" {})))
            (if (= c (int \e))
              (Long/parseLong (str sb))
              (do (.append sb (char c)) (recur))))))

      (= b (int \l))
      (loop [acc []]
        (let [v (read-bencode in)]
          (if (= ::end v) acc (recur (conj acc v)))))

      (= b (int \d))
      (loop [acc (sorted-map)]
        (let [k (read-bencode in)]
          (if (= ::end k)
            acc
            (let [v (read-bencode in)]
              (recur (assoc acc k v))))))

      (= b (int \e)) ::end

      (Character/isDigit ^char (char b))
      (let [sb (StringBuilder.)]
        (.append sb (char b))
        (loop []
          (let [c (.read in)]
            (when (neg? c) (throw (ex-info "EOF reading length" {})))
            (if (= c (int \:))
              (let [n (Long/parseLong (str sb))
                    bs (byte-array n)]
                (loop [off 0]
                  (when (< off n)
                    (let [r (.read in bs off (- n off))]
                      (when (neg? r)
                        (throw (ex-info "EOF inside string" {})))
                      (recur (+ off r)))))
                (String. bs "UTF-8"))
              (do (.append sb (char c)) (recur)))))))))

(defn- write-bencode [^OutputStream out v]
  (cond
    (integer? v) (do (.write out (int \i))
                     (.write out (.getBytes (str v) "UTF-8"))
                     (.write out (int \e)))
    (string? v)  (let [bs (.getBytes ^String v "UTF-8")]
                   (.write out (.getBytes (str (alength bs) ":") "UTF-8"))
                   (.write out bs))
    (bytes? v)   (let [^bytes bs v]
                   (.write out (.getBytes (str (alength bs) ":") "UTF-8"))
                   (.write out bs))
    (sequential? v) (do (.write out (int \l))
                        (doseq [x v] (write-bencode out x))
                        (.write out (int \e)))
    (map? v)     (do (.write out (int \d))
                     (doseq [[k val] (sort-by first v)]
                       (write-bencode out (str k))
                       (write-bencode out val))
                     (.write out (int \e)))
    :else (throw (ex-info (str "Cannot bencode: " (pr-str v))
                          {:value v}))))

;; ---------------------------------------------------------------------------
;; Pod operation table
;;
;; All ops take and return edn. Keep return values to plain data: the bb
;; side cannot dereference JVM objects.

(defn- element-clean
  "Strip Java objects out of a result map so it can survive the wire.

  The keys :project / :target / :tasks / :targets all hold live JVM
  references (Project, Target, UnknownElement) that have no
  meaningful edn representation. We replace them with plain data:
  :tasks / :targets become a count, :error becomes its message
  string."
  [m]
  (-> m
      (dissoc :project :target)
      (cond-> (:tasks m)   (update :tasks   count))
      (cond-> (:targets m) (update :targets (fn [ts]
                                              (mapv #(.getName ^org.apache.tools.ant.Target %)
                                                    ts))))
      (cond-> (:error m)   (update :error   #(some-> % .getMessage)))))

(defn ^:no-doc op-execute [{:keys [elements opts]}]
  (-> (apply core/execute! elements (mapcat identity (or opts {})))
      element-clean))

;; ---------------------------------------------------------------------------
;; Sessions. A session is a long-lived clj-ant Session (i.e. a single
;; Ant Project) that the bb side keeps a handle to via a string id.
;; Without sessions, every pod call rebuilds a Project; with them, the
;; init cost is paid once.

(defonce ^:private sessions (atom {}))    ; id -> Session

(defn ^:no-doc op-open-session [{:keys [opts]}]
  (let [id (str "sess-" (System/nanoTime))
        s  (core/session (or opts {}))]
    (swap! sessions assoc id s)
    id))

(defn ^:no-doc op-close-session [{:keys [id]}]
  (if-some [s (get @sessions id)]
    (do (core/close-session s)
        (swap! sessions dissoc id)
        nil)
    (throw (ex-info (str "Unknown session id: " (pr-str id))
                    {:id id :open-sessions (vec (keys @sessions))}))))

(defn ^:no-doc op-execute-in [{:keys [id elements opts]}]
  (if-some [s (get @sessions id)]
    (-> (apply core/execute! elements
               (mapcat identity (assoc (or opts {}) :session s)))
        element-clean)
    (throw (ex-info
             (str "Unknown session id: " (pr-str id)
                  ". Open one first via (a/open-session ...).")
             {:id id :open-sessions (vec (keys @sessions))}))))

(defn ^:no-doc op-execute-stream
  "Streaming variant of execute. Each event is sent as its own pod
  reply (status []); `partial!` is called by the pod loop for every
  intermediate value.

  Returns the cleaned execute! result map wrapped in a sentinel
  {:clj-ant/result …} so the bb-side stub can tell events apart
  from the final result -- otherwise, since the success handler
  fires async, callers can't synchronise.

  Accepts `:session <id>` in opts to run inside a previously-opened
  session (same id you got back from open-session). Without it, a
  fresh project is built per call."
  [{:keys [elements opts]} partial!]
  (let [opts*    (or opts {})
        session  (when-some [id (:session opts*)]
                   (or (get @sessions id)
                       (throw (ex-info
                                (str "Unknown session id: " (pr-str id)
                                     ". Open one first via "
                                     "(a/open-session ...).")
                                {:id id :open-sessions
                                 (vec (keys @sessions))}))))
        opts**   (-> opts*
                     (assoc :on-event (fn [e] (partial! e)))
                     (cond-> session (assoc :session session)))]
    {:clj-ant/result
     (-> (apply core/execute! elements (mapcat identity opts**))
         element-clean)}))

(defn ^:no-doc op-files-stream
  "Streaming variant of files: each path is delivered as it's
  discovered. Useful for large filesets where you want to start work
  before the whole scan finishes.

  Returns the same {:clj-ant/result …} sentinel execute-stream uses,
  so the bb-side stub can detect end-of-stream and unblock without
  the caller's handler ever seeing a non-path payload."
  [{:keys [element opts]} partial!]
  (doseq [^java.io.File f (apply core/files element
                                 (mapcat identity (or opts {})))]
    (partial! (.getAbsolutePath f)))
  {:clj-ant/result :done})

(defn ^:no-doc op-files [{:keys [element opts]}]
  (mapv #(.getAbsolutePath ^java.io.File %)
        (apply core/files element (mapcat identity (or opts {})))))

(defn ^:no-doc op-plan [{:keys [element]}]
  (with-out-str (core/plan element)))

(defn- class-name [x]
  (if (instance? Class x) (.getName ^Class x) x))

(defn- describe-clean [m]
  (some-> m
          (update :attrs
                  (fn [attrs]
                    (into (sorted-map)
                          (map (fn [[k v]] [k (update v :type class-name)]))
                          attrs)))
          (update :nested
                  (fn [nested]
                    (into (sorted-map)
                          (map (fn [[k v]] [k (class-name v)]))
                          nested)))))

(defn ^:no-doc op-describe [{:keys [tag]}]
  (describe-clean (core/describe tag)))

(defn ^:no-doc op-lint [{:keys [elements opts]}]
  (apply core/lint elements (mapcat identity (or opts {}))))

(def ops
  {"clj-ant.pod/execute"        {:fn #'op-execute}
   "clj-ant.pod/files"          {:fn #'op-files}
   "clj-ant.pod/plan"           {:fn #'op-plan}
   "clj-ant.pod/describe"       {:fn #'op-describe}
   "clj-ant.pod/lint"           {:fn #'op-lint}
   "clj-ant.pod/open-session"   {:fn #'op-open-session}
   "clj-ant.pod/close-session"  {:fn #'op-close-session}
   "clj-ant.pod/execute-in"     {:fn #'op-execute-in}
   "clj-ant.pod/execute-stream"
   {:fn #'op-execute-stream :stream? true}
   "clj-ant.pod/files-stream"
   {:fn #'op-files-stream :stream? true}})

;; ---------------------------------------------------------------------------
;; Pod loop

(defn- task-namespace-payload
  "Build a `clj-ant.tasks` namespace entry for the pod describe payload.

  Iterates every public var in the JVM-side `clj-ant.tasks` and emits
  a single bb-side function for each one whose only job is to assemble
  a clj-ant element map. No docstrings (size), no schema (the JVM
  side has both). Each wrapper is a one-liner -- bb users get the
  same `(t/copy :todir ...)` ergonomics they have on the JVM."
  []
  (require 'clj-ant.tasks)
  (let [tasks-ns (the-ns 'clj-ant.tasks)
        ;; First var: the element builder, used by every wrapper.
        ;; Mirrors core/split-args: a sequential whose first item LOOKS
        ;; like an element (a map with :tag) is spliced; anything else is
        ;; treated as a single child (the JVM-side as-child handles
        ;; coercion of paths/Files at execute time).
        ;;
        ;; Named `make-element` so it doesn't collide with the generated
        ;; `t/element` wrapper -- recursive nested-element discovery now
        ;; finds <element> as a child of macrodef and emits a wrapper for
        ;; it. The wrapper would shadow our builder (and call itself
        ;; through clojure.core/apply) without this rename.
        builder
        {"name" "make-element"
         "code" (str
                  "(defn make-element "
                  "  ([tag-kw] {:tag tag-kw :attrs {} :children [] :text nil}) "
                  "  ([tag-kw & args] "
                  "    (let [step "
                  "          (fn step [attrs text children xs] "
                  "            (if (empty? xs) "
                  "              {:tag tag-kw :attrs attrs "
                  "               :children children :text text} "
                  "              (let [x (first xs)] "
                  "                (cond "
                  "                  (and (keyword? x) (next xs)) "
                  "                   (step (assoc attrs x (second xs)) text "
                  "                         children (nthrest xs 2)) "
                  "                  (string? x) "
                  "                   (step attrs (str (or text \"\") x) "
                  "                         children (rest xs)) "
                  "                  (nil? x) "
                  "                   (step attrs text children (rest xs)) "
                  "                  (sequential? x) "
                  "                   (let [fst (first x)] "
                  "                     (if (and (map? fst) (:tag fst)) "
                  "                       (step attrs text (into children x) (rest xs)) "
                  "                       (step attrs text (conj children x) (rest xs)))) "
                  "                  :else "
                  "                   (step attrs text (conj children x) (rest xs))))))] "
                  "      (step {} nil [] args))))")}
        ;; Then a thin wrapper per public task/type var.
        wrappers
        (for [[sym _] (sort (ns-publics tasks-ns))
              :let [m   (meta (resolve (symbol "clj-ant.tasks" (name sym))))
                    tag (get m :clj-ant/tag)]
              :when tag]
          {"name" (name sym)
           "code" (str "(defn " sym " [& args] "
                       "(clojure.core/apply make-element "
                       (pr-str (keyword tag)) " args))")})]
    {"name" "clj-ant.tasks"
     "vars" (vec (cons builder wrappers))}))

(def ^:private describe-payload
  {"format"    "edn"
   "namespaces"
   [{"name" "clj-ant.pod"
     "vars" [{"name" "execute" "code"
              ;; Client-side stub. Re-shapes args, calls the JVM op.
              (str
                "(defn execute [elements & {:as opts}] "
                "  (babashka.pods/invoke "
                "    \"clj-ant.pod\" "
                "    'clj-ant.pod/execute "
                "    [{:elements elements :opts opts}]))")}
             {"name" "files" "code"
              (str
                "(defn files [element & {:as opts}] "
                "  (babashka.pods/invoke "
                "    \"clj-ant.pod\" "
                "    'clj-ant.pod/files "
                "    [{:element element :opts opts}]))")}
             {"name" "plan" "code"
              (str
                "(defn plan [element] "
                "  (babashka.pods/invoke "
                "    \"clj-ant.pod\" "
                "    'clj-ant.pod/plan "
                "    [{:element element}]))")}
             {"name" "describe" "code"
              (str
                "(defn describe [tag] "
                "  (babashka.pods/invoke "
                "    \"clj-ant.pod\" "
                "    'clj-ant.pod/describe "
                "    [{:tag tag}]))")}
             {"name" "lint" "code"
              (str
                "(defn lint [elements & {:as opts}] "
                "  (babashka.pods/invoke "
                "    \"clj-ant.pod\" "
                "    'clj-ant.pod/lint "
                "    [{:elements elements :opts opts}]))")}
             {"name" "explain" "code"
              "(defn explain [elements & opts] (apply lint elements opts))"}
             ;; Long-lived sessions. The pod side holds an Ant Project
             ;; per id; bb gets a string handle. Reuse across many
             ;; calls amortises the per-call init cost (logger setup,
             ;; taskdef registration, project base initialisation).
             {"name" "open-session" "code"
              (str
                "(defn open-session [& {:as opts}] "
                "  (babashka.pods/invoke "
                "    \"clj-ant.pod\" "
                "    'clj-ant.pod/open-session "
                "    [{:opts opts}]))")}
             {"name" "close-session" "code"
              (str
                "(defn close-session [id] "
                "  (babashka.pods/invoke "
                "    \"clj-ant.pod\" "
                "    'clj-ant.pod/close-session "
                "    [{:id id}]))")}
             {"name" "execute-in" "code"
              (str
                "(defn execute-in [id elements & {:as opts}] "
                "  (babashka.pods/invoke "
                "    \"clj-ant.pod\" "
                "    'clj-ant.pod/execute-in "
                "    [{:id id :elements elements :opts opts}]))")}
             {"name" "with-session" "code"
              (str
                "(defmacro with-session [[sym opts] & body] "
                "  `(let [~sym (open-session ~@(mapcat identity opts))] "
                "     (try ~@body (finally (close-session ~sym)))))")}
             ;; Streaming variant: the supplied handler fn is called
             ;; with each event as it happens, and the final return
             ;; is the result map.
             {"name" "execute-stream" "code"
              (str
                "(defn execute-stream [elements handler & {:as opts}] "
                "  (let [done (promise)] "
                "    (babashka.pods/invoke "
                "      \"clj-ant.pod\" "
                "      'clj-ant.pod/execute-stream "
                "      [{:elements elements :opts opts}] "
                "      {:handlers {:success "
                "                  (fn [v] "
                "                    (if (and (map? v) (contains? v :clj-ant/result)) "
                "                      (deliver done (:clj-ant/result v)) "
                "                      (handler v))) "
                "                  :error "
                "                  (fn [{:keys [ex-message]}] "
                "                    (deliver done (ex-info ex-message {})) "
                "                    (throw (ex-info ex-message {})))}}) "
                "    (let [r (deref done 60000 :clj-ant/timeout)] "
                "      (if (instance? Throwable r) (throw r) r))))")}
             ;; Stream resolved paths from a fileset/path/etc. Useful
             ;; for very large scans where you want to start work
             ;; before the iterator is exhausted.
             {"name" "files-stream" "code"
              (str
                "(defn files-stream [element handler & {:as opts}] "
                "  (let [done (promise)] "
                "    (babashka.pods/invoke "
                "      \"clj-ant.pod\" "
                "      'clj-ant.pod/files-stream "
                "      [{:element element :opts opts}] "
                "      {:handlers {:success "
                "                  (fn [v] "
                "                    (if (and (map? v) "
                "                             (contains? v :clj-ant/result)) "
                "                      (deliver done :ok) "
                "                      (handler v))) "
                "                  :error "
                "                  (fn [{:keys [ex-message]}] "
                "                    (deliver done (ex-info ex-message {})) "
                "                    (throw (ex-info ex-message {})))}}) "
                "    (let [r (deref done 60000 :clj-ant/timeout)] "
                "      (if (instance? Throwable r) (throw r) nil))))")}]}
    (task-namespace-payload)]
   "ops"
   {"shutdown" {}}})

(defn ^:no-doc send! [out reply]
  (locking out
    (write-bencode out reply)
    (.flush out)))

(defn ^:no-doc handle-invoke [_in out msg]
  (let [op-name (get msg "var")
        id      (get msg "id")
        args    (when-some [a (get msg "args")] (edn/read-string a))
        {f :fn stream? :stream?} (ops op-name)]
    (try
      (if stream?
        (let [partial! (fn [v]
                         (send! out {"id" id
                                     "value" (pr-str v)
                                     "status" []}))
              final    (apply f (conj args partial!))]
          ;; Bb's :done reply does NOT pass through the success
          ;; handler -- only intermediate values do. So we deliver
          ;; the final result as one more partial!, then signal
          ;; completion with an empty done reply.
          (partial! final)
          (send! out {"id" id "status" ["done"]}))
        (let [v (apply f args)]
          (send! out {"id" id "value" (pr-str v) "status" ["done"]})))
      (catch Throwable t
        (send! out {"id" id
                    "ex-message" (or (.getMessage t) "(no message)")
                    "ex-data" (pr-str (or (ex-data t) {}))
                    "status" ["done" "error"]})))))

(defn -main [& _]
  ;; Capture the real stdout for bencode replies before anything else
  ;; can grab it. Ant's DefaultLogger writes to System/out for build
  ;; messages -- that traffic must NOT mix into the pod's wire protocol,
  ;; or bb's bencode reader will see garbage. We swap System/out to
  ;; System/err so build chatter shows up on the parent process's
  ;; stderr stream, while replies go down the original stdout.
  (let [real-out System/out
        in       (PushbackInputStream. System/in)
        out      real-out]
    (System/setOut System/err)
    (loop []
      (let [msg (read-bencode in)]
        (cond
          (= ::eof msg) (System/exit 0)

          (= "describe" (get msg "op"))
          (do (write-bencode out describe-payload)
              (.flush out)
              (recur))

          (= "invoke" (get msg "op"))
          (do (handle-invoke in out msg)
              (recur))

          (= "shutdown" (get msg "op"))
          (System/exit 0)

          :else
          (do (binding [*out* *err*]
                (println "clj-ant pod: unknown op" (pr-str msg)))
              (recur)))))))
