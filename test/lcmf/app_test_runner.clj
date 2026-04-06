(ns lcmf.app-test-runner
  (:require [clojure.test :as t]
            [lcmf.app-test]))

(defn -main []
  (let [{:keys [fail error]} (t/run-tests 'lcmf.app-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
