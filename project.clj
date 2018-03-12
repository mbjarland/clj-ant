(defproject clj-ant "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.apache.ant/ant "1.10.2"]
                 [org.clojure/data.xml "0.0.8"]]
  :source-paths ["src/clj" "target/generated"]
  :java-source-paths ["src/java"]
  :javac-options ["-target" "1.8" "-source" "1.8"
                  "-Xlint:deprecation" "-Xlint:options"]
  :jvm-opts ["-Xms512m" "-Xmx2g" "-server"]

  :profiles {:dev {:dependencies [[clj-tagsoup/clj-tagsoup "0.3.0"]
                                  [com.rpl/specter "1.1.0"]]
                   :source-paths ["src/clj"
                                  "src/build-src"
                                  "target/generated"]}}
  )
