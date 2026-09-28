(ns teodorlu.test-latest-changes
  "Run the tests affected by what changed since the last green commit, in a
  warm JVM, in a worktree that follows a revision."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bencode.core :as bencode]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.net Socket]))

(def green-ref "refs/test-latest-changes/green")

(defn- short-hash [s]
  (subs (format "%08x" (hash s)) 0 6))

;; git

(defn- git [dir & args]
  (-> (apply p/shell {:dir (str dir) :out :string :err :string} "git" args)
      :out str/trim))

(defn- git-ok? [dir & args]
  (zero? (:exit (apply p/shell {:dir (str dir) :out :string :err :string :continue true}
                       "git" args))))

(defn- green [repo]
  (when (git-ok? repo "rev-parse" "--verify" "-q" green-ref)
    (git repo "rev-parse" green-ref)))

(defn- changed-files
  "Paths changed between `from` and `to`, or :all when there is no usable
  `from`."
  [repo from to]
  (if (and from (git-ok? repo "merge-base" "--is-ancestor" from to))
    (let [out (git repo "diff" "--name-only" "--no-renames" from to)]
      (if (str/blank? out) [] (str/split-lines out)))
    :all))

(defn- default-worktree [repo]
  (let [common (str (fs/canonicalize (fs/path repo (git repo "rev-parse" "--git-common-dir"))))
        state (or (System/getenv "XDG_STATE_HOME")
                  (str (fs/path (fs/home) ".local" "state")))
        key (str (fs/file-name (fs/parent common)) "-" (short-hash common))]
    (str (fs/path state "test-latest-changes" key "worktree"))))

(defn- ensure-worktree [repo worktree sha]
  (if (fs/exists? worktree)
    (when-not (str/blank? (git worktree "status" "--porcelain"))
      (throw (ex-info (str "Worktree has changes, refusing to move it: " worktree) {})))
    (git repo "worktree" "add" "--detach" (str worktree) sha))
  (git worktree "checkout" "-q" "--detach" sha))

;; nREPL

(defn- ->str [x]
  (if (bytes? x) (String. ^bytes x "UTF-8") x))

(defn- nrepl-eval
  "Evaluate `code` over nREPL at `port`. Returns {:value :out :err :ex}, with
  :value the last value as a string."
  [port code]
  (with-open [socket (Socket. "localhost" (int port))]
    (let [in (java.io.PushbackInputStream. (.getInputStream socket))
          out (.getOutputStream socket)]
      (bencode/write-bencode out {"op" "eval" "code" code "id" "tlc"})
      (loop [acc {}]
        (let [msg (update-vals (bencode/read-bencode in) ->str)
              acc (cond-> acc
                    (msg "value") (assoc :value (msg "value"))
                    (msg "out") (update :out str (msg "out"))
                    (msg "err") (update :err str (msg "err"))
                    (msg "ex") (assoc :ex (msg "ex")))]
          (if (some #{"done"} (map ->str (msg "status")))
            acc
            (recur acc)))))))

(defn- port [worktree]
  (let [f (fs/file worktree ".nrepl-port")]
    (when (fs/exists? f)
      (parse-long (str/trim (slurp f))))))

(defn- answers? [port]
  (try (= "1" (:value (nrepl-eval port "1")))
       (catch Exception _ false)))

;; JVM

(defn- process-name
  "bgproc names are global and at most 64 characters."
  [worktree]
  (let [folder (str (fs/file-name worktree))]
    (str "tlc-" (subs folder 0 (min 40 (count folder))) "-" (short-hash (str (fs/absolutize worktree))))))

(defn- ensure-jvm
  "Returns the port of a JVM that answers, starting one under bgproc if none
  does."
  [worktree jvm-cmd]
  (or (some-> (port worktree) (#(when (answers? %) %)))
      (do (fs/delete-if-exists (fs/file worktree ".nrepl-port"))
          (p/shell {:dir (str worktree) :out :string :err :string}
                   "bgproc" "start" "-f" "-n" (process-name worktree) "-w" "300"
                   "--" "sh" "-c" jvm-cmd)
          (loop [tries 600]
            (let [p (port worktree)]
              (cond (and p (answers? p)) p
                    (zero? tries) (throw (ex-info "JVM did not answer on nREPL" {:worktree worktree}))
                    :else (do (Thread/sleep 100) (recur (dec tries)))))))))

(def ^:private agent-source
  (delay (slurp (io/resource "teodorlu/test_latest_changes/agent.clj"))))

(defn- ensure-agent [port]
  (let [h (hash @agent-source)
        code (format "(if (= %d (some-> (resolve 'teodorlu.test-latest-changes.agent/source-hash) deref)) :cached (do (load-string %s) (intern 'teodorlu.test-latest-changes.agent 'source-hash %d) :loaded))"
                     h (pr-str @agent-source) h)
        {:keys [ex err value]} (nrepl-eval port code)]
    (when ex (throw (ex-info (str "Could not load agent: " err) {})))
    value))

;; run

(defn- ms-since [t0]
  (quot (- (System/nanoTime) t0) 1000000))

(defn run-changes
  "Move the worktree to `rev`, run the affected tests there, and move the
  green ref when they pass."
  [{:keys [repo rev worktree jvm-cmd test-paths]
    :or {repo "." rev "main" test-paths ["test"]}}]
  (let [t0 (System/nanoTime)
        repo (str (fs/absolutize repo))
        worktree (str (fs/absolutize (or worktree (default-worktree repo))))
        sha (git repo "rev-parse" "--verify" (str rev "^{commit}"))
        from (green repo)
        changed (changed-files repo from sha)
        _ (ensure-worktree repo worktree sha)
        t-git (ms-since t0)
        t1 (System/nanoTime)
        port (ensure-jvm worktree jvm-cmd)
        agent (ensure-agent port)
        t-jvm (ms-since t1)
        t2 (System/nanoTime)
        {:keys [value out err ex]}
        (nrepl-eval port (pr-str (list 'teodorlu.test-latest-changes.agent/run
                                       {:changed changed :test-paths test-paths})))
        t-eval (ms-since t2)
        result (if ex {:error (str "Eval failed: " err)} (edn/read-string value))
        green? (and (not (:error result))
                    (zero? (+ (get-in result [:summary :fail] 0)
                              (get-in result [:summary :error] 0)))) ]
    (when green? (git repo "update-ref" green-ref sha))
    (merge result
           {:sha sha :from from :changed changed :green? green? :agent agent
            :out out
            :ms (merge (:ms result)
                       {:git t-git :jvm t-jvm :eval t-eval :total (ms-since t0)})})))

(defn- not-watched [changed]
  (when (sequential? changed)
    (remove #(re-find #"\.cljc?$" %) changed)))

(defn ^:export main
  "Entrypoint from babashka tasks.

    bb test-latest-changes --jvm-cmd 'clojure -M:test -m nrepl.cmdline'"
  [& args]
  (let [opts (loop [[k v & more] args, acc {}]
               (if k
                 (recur more (assoc acc (keyword (subs k 2)) v))
                 acc))
        _ (when-not (:jvm-cmd opts)
            (throw (ex-info "--jvm-cmd is required" {:babashka/exit 2})))
        result (run-changes (cond-> opts
                              (:test-paths opts) (update :test-paths #(str/split % #","))))]
    (some-> (:out result) print)
    (when-let [e (:error result)] (println e))
    (when-let [files (seq (not-watched (:changed result)))]
      (println "Changed, not followed:" (str/join " " files)))
    (println (str (subs (:sha result) 0 8)
                  " since " (some-> (:from result) (subs 0 8))
                  ": " (count (:selected result)) " namespaces "
                  (pr-str (:summary result)) " "
                  (pr-str (:ms result))))
    (when-not (:green? result)
      (throw (ex-info "Not green" {:babashka/exit 1})))))
