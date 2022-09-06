(ns noria.test
  (:require
   [noria.thunks-test]
   [clojure.test :as t]))

(defn -main [& args]
  (clojure.test/run-all-tests #"noria\..*-test"))

