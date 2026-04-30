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
            [clojure.java.io :as io]
            [clj-ant.core :as core])
  (:import [java.io InputStream OutputStream PushbackInputStream
                    ByteArrayOutputStream]))

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
  "Strip Java objects out of a result map so it can survive the wire."
  [m]
  (-> m
      (dissoc :project :target :tasks)
      (cond->
        (:error m) (update :error #(some-> % .getMessage)))))

(defn ^:no-doc op-execute [{:keys [elements opts]}]
  (-> (apply core/execute! elements (mapcat identity (or opts {})))
      element-clean))

(defn ^:no-doc op-execute-stream
  "Streaming variant of execute. Each event is sent as its own pod
  reply (status []). The final result map is the last reply with
  status [done]. `partial!` is called by the pod loop for every
  intermediate value."
  [{:keys [elements opts]} partial!]
  (apply core/execute! elements
         (mapcat identity
                 (-> (or opts {})
                     (assoc :on-event (fn [e] (partial! e))))))
  {:phase :result})

(defn ^:no-doc op-files-stream
  "Streaming variant of files: each path is delivered as it's
  discovered. Useful for large filesets where you want to start work
  before the whole scan finishes."
  [{:keys [element opts]} partial!]
  (doseq [^java.io.File f (apply core/files element
                                 (mapcat identity (or opts {})))]
    (partial! (.getAbsolutePath f)))
  {:phase :done})

(defn ^:no-doc op-files [{:keys [element opts]}]
  (mapv #(.getAbsolutePath ^java.io.File %)
        (apply core/files element (mapcat identity (or opts {})))))

(defn ^:no-doc op-plan [{:keys [element]}]
  (with-out-str (core/plan element)))

(def ops
  {"clj-ant.pod/execute" {:fn #'op-execute}
   "clj-ant.pod/files"   {:fn #'op-files}
   "clj-ant.pod/plan"    {:fn #'op-plan}
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
        builder
        {"name" "element"
         "code" (str
                  "(defn element [tag-kw & args] "
                  "  (loop [attrs {} text nil children [] xs args] "
                  "    (let [x (first xs)] "
                  "      (cond "
                  "        (empty? xs) "
                  "         {:tag tag-kw :attrs attrs :children children :text text} "
                  "        (and (keyword? x) (next xs)) "
                  "         (recur (assoc attrs x (second xs)) text children (drop 2 xs)) "
                  "        (string? x) "
                  "         (recur attrs (str (or text \"\") x) children (rest xs)) "
                  "        (sequential? x) "
                  "         (recur attrs text (into children x) (rest xs)) "
                  "        :else "
                  "         (recur attrs text (conj children x) (rest xs))))))")}
        ;; Then a thin wrapper per public task/type var.
        wrappers
        (for [[sym _] (sort (ns-publics tasks-ns))
              :let [m   (meta (resolve (symbol "clj-ant.tasks" (name sym))))
                    tag (get m :clj-ant/tag)]
              :when tag]
          {"name" (name sym)
           "code" (str "(defn " sym " [& args] "
                       "(clojure.core/apply element "
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
             ;; Streaming variant: the supplied handler fn is called
             ;; with each event as it happens, and the final return
             ;; is the result map.
             {"name" "execute-stream" "code"
              (str
                "(defn execute-stream [elements handler & {:as opts}] "
                "  (babashka.pods/invoke "
                "    \"clj-ant.pod\" "
                "    'clj-ant.pod/execute-stream "
                "    [{:elements elements :opts opts}] "
                "    {:handlers {:success handler "
                "                :error   (fn [{:keys [ex-message]}] "
                "                           (throw (ex-info ex-message {})))}}))")}
             ;; Stream resolved paths from a fileset/path/etc. Useful
             ;; for very large scans where you want to start work
             ;; before the iterator is exhausted.
             {"name" "files-stream" "code"
              (str
                "(defn files-stream [element handler & {:as opts}] "
                "  (babashka.pods/invoke "
                "    \"clj-ant.pod\" "
                "    'clj-ant.pod/files-stream "
                "    [{:element element :opts opts}] "
                "    {:handlers {:success handler "
                "                :error   (fn [{:keys [ex-message]}] "
                "                           (throw (ex-info ex-message {})))}}))")}]}
    (task-namespace-payload)]
   "ops"
   {"shutdown" {}}})

(defn ^:no-doc send! [out reply]
  (locking out
    (write-bencode out reply)
    (.flush out)))

(defn ^:no-doc handle-invoke [in out msg]
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
          (send! out {"id" id "value" (pr-str final) "status" ["done"]}))
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
