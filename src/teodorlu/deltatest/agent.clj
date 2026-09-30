(ns teodorlu.deltatest.agent
  "Runs inside the JVM under test. Sent there as source over nREPL, so the
  project under test does not depend on deltatest. Needs clj-reload
  on that JVM's classpath, and nothing else."
  (:require [clj-reload.core :as clj-reload]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :as t]))

(defn dependents
  "`nses`, and every namespace that requires one of them, transitively.
  `requires` maps a namespace to the set of namespaces it requires."
  [requires nses]
  (let [required-by (apply merge-with into {}
                           (for [[ns reqs] requires, req reqs]
                             {req #{ns}}))]
    (loop [found (set nses), frontier (set nses)]
      (let [new (set/difference (set (mapcat required-by frontier)) found)]
        (if (empty? new)
          found
          (recur (into found new) new))))))

(defn- reload-state []
  @@#'clj-reload/*state)

(defn- canonical [file]
  (.getCanonicalPath (io/file file)))

(defn- namespaces-in [state paths]
  (let [paths (set (map canonical paths))]
    (set (for [[file {:keys [namespaces]}] (:files state)
               :when (contains? paths (canonical file))
               ns namespaces]
           ns))))

(defn- test-namespaces [state test-paths]
  (let [dirs (map #(str (canonical %) "/") test-paths)]
    (set (for [[file {:keys [namespaces]}] (:files state)
               :when (some #(.startsWith ^String (canonical file) %) dirs)
               ns namespaces]
           ns))))

(defn- ms-since [t0]
  (quot (- (System/nanoTime) t0) 1000000))

(defn run
  "Reload what changed on disk, then run the test namespaces that depend on
  `changed`. `changed` is a seq of file paths relative to the working
  directory, or :all."
  [{:keys [changed test-paths]}]
  (let [t0 (System/nanoTime)
        before (reload-state)
        reloaded (try (clj-reload/reload {:throw false})
                      (catch Throwable e {:exception e}))]
    (if-let [e (:exception reloaded)]
      {:error (str "Reload failed" (some->> (:failed reloaded) (str " at ")) ": "
                   (ex-message e) (some->> (ex-cause e) ex-message (str "\n")))}
      (let [t-reload (ms-since t0)
            t1 (System/nanoTime)
            after (reload-state)
            tests (test-namespaces after test-paths)
            _ (run! #(when-not (find-ns %) (require %)) tests)
            selected (if (= :all changed)
                       tests
                       (set/intersection
                        tests
                        (dependents (update-vals (:namespaces after) :requires)
                                    (into (namespaces-in before changed)
                                          (namespaces-in after changed)))))
            t-select (ms-since t1)
            t2 (System/nanoTime)
            summary (if (seq selected)
                      (apply t/run-tests (sort selected))
                      {:test 0 :pass 0 :fail 0 :error 0})]
        {:selected (vec (sort selected))
         :test-namespaces (count tests)
         :summary (select-keys summary [:test :pass :fail :error])
         :ms {:reload t-reload :select t-select :run (ms-since t2)}}))))
