(ns teodorlu.test-latest-changes.agent-test
  (:require [clojure.test :refer [deftest is]]
            [teodorlu.test-latest-changes.agent :as agent]))

(deftest dependents
  (let [requires {'a #{'b}, 'b #{'c}, 'c #{}, 'd #{'c}, 'e #{}}]
    (is (= '#{a b c d} (agent/dependents requires '#{c})))
    (is (= '#{a} (agent/dependents requires '#{a})))))
