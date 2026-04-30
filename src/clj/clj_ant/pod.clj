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

(defn- node-clean
  "Strip Java objects out of a result map so it can survive the wire."
  [m]
  (-> m
      (dissoc :project :target :tasks)
      (cond->
        (:error m) (update :error #(some-> % .getMessage)))))

(defn ^:no-doc op-execute [{:keys [nodes opts]}]
  (-> (apply core/execute! nodes (mapcat identity (or opts {})))
      node-clean))

(defn ^:no-doc op-files [{:keys [node opts]}]
  (mapv #(.getAbsolutePath ^java.io.File %)
        (apply core/files node (mapcat identity (or opts {})))))

(defn ^:no-doc op-plan [{:keys [node]}]
  (with-out-str (core/plan node)))

(def ops
  {"clj-ant.pod/execute" #'op-execute
   "clj-ant.pod/files"   #'op-files
   "clj-ant.pod/plan"    #'op-plan})

;; ---------------------------------------------------------------------------
;; Pod loop

(def ^:private describe-payload
  {"format"    "edn"
   "namespaces"
   [{"name" "clj-ant.pod"
     "vars" [{"name" "execute" "code"
              ;; Client-side stub. Re-shapes args, calls the JVM op.
              (str
                "(defn execute [nodes & {:as opts}] "
                "  (babashka.pods/invoke "
                "    \"clj-ant.pod\" "
                "    'clj-ant.pod/execute "
                "    [{:nodes nodes :opts opts}]))")}
             {"name" "files" "code"
              (str
                "(defn files [node & {:as opts}] "
                "  (babashka.pods/invoke "
                "    \"clj-ant.pod\" "
                "    'clj-ant.pod/files "
                "    [{:node node :opts opts}]))")}
             {"name" "plan" "code"
              (str
                "(defn plan [node] "
                "  (babashka.pods/invoke "
                "    \"clj-ant.pod\" "
                "    'clj-ant.pod/plan "
                "    [{:node node}]))")}]}]
   "ops"
   {"shutdown" {}}})

(defn ^:no-doc handle-invoke [in out msg]
  (let [op-name (get msg "var")
        id      (get msg "id")
        args    (when-some [a (get msg "args")] (edn/read-string a))
        op      (ops op-name)
        reply   (try
                  (let [v (apply op args)]
                    {"id"     id
                     "value"  (pr-str v)
                     "status" ["done"]})
                  (catch Throwable t
                    {"id"      id
                     "ex-message" (.getMessage t)
                     "ex-data" (pr-str (or (ex-data t) {}))
                     "status"  ["done" "error"]}))]
    (write-bencode out reply)
    (.flush out)))

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
