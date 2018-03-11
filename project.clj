(defproject clj-ant "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.apache.ant/ant "1.10.2"]
                 [org.clojure/data.xml "0.0.8"]]
  :java-source-paths ["java"]
  :javac-options ["-target" "1.8" "-source" "1.8"
                  "-Xlint:deprecation" "-Xlint:options"]
  :jvm-opts ["-Xms512m" "-Xmx2g" "-server"])
