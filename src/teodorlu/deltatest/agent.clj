(ns teodorlu.deltatest.agent
  "Runs inside the JVM under test. Sent there as source over nREPL, so the
  project under test does not depend on deltatest. Needs clj-reload and
  kaocha on that JVM's classpath, and nothing else. Written for Clojure 1.11,
  so no 1.12 interop."
  (:require [clj-reload.core :as clj-reload]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [kaocha.repl :as kaocha]))

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

(defn reload
  "Reload what changed on disk. Returns the namespaces the files in `changed`
  held, before or after the reload, as :touched. `changed` is a seq of file
  paths relative to the working directory, or :all."
  [changed]
  (let [before (reload-state)
        reloaded (try (clj-reload/reload {:throw false :log-fn nil})
                      (catch Throwable e {:exception e}))]
    (if-let [e (:exception reloaded)]
      {:error (str "Reload failed" (some->> (:failed reloaded) (str " at ")) ": "
                   (ex-message e) (some->> (ex-cause e) ex-message (str "\n")))}
      {:touched (if (= :all changed)
                  :all
                  (into (namespaces-in before changed)
                        (namespaces-in (reload-state) changed)))})))

(defn run
  "Run, with kaocha, the test namespaces that depend on `touched`, or all of
  them when `touched` is :all."
  [{:keys [touched test-paths]}]
  (let [state (reload-state)
        tests (test-namespaces state test-paths)
        _ (run! #(when-not (find-ns %) (require %)) tests)
        selected (if (= :all touched)
                   tests
                   (set/intersection
                    tests
                    (dependents (update-vals (:namespaces state) :requires) touched)))
        result (when (seq selected)
                 (apply kaocha/run (sort selected)))]
    (merge {:selected (vec (sort selected))
            :test-namespaces (count tests)}
           (cond
             (empty? selected) {:summary {:test 0 :pass 0 :fail 0 :error 0}}
             ;; kaocha returns 0, not a result, when it ran nothing
             (map? result) {:summary {:test (:kaocha.result/count result)
                                      :pass (:kaocha.result/pass result)
                                      :fail (:kaocha.result/fail result)
                                      :error (:kaocha.result/error result)}}
             :else {:error "Kaocha ran none of the selected test namespaces."}))))
