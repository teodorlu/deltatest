(ns teodorlu.deltatest
  "Run the tests affected by what changed since the last green commit, in a
  warm JVM, in a worktree that follows a revision."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bencode.core :as bencode]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io PushbackInputStream]
           [java.lang ProcessHandle ProcessHandle$Info]
           [java.net Socket]
           [java.util Optional]
           [java.util.concurrent CompletableFuture]
           [java.util.stream Stream]))

;; (set! *warn-on-reflection* true)

(def green-ref "refs/deltatest/green")

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
  "Paths that differ between the trees of `from` and `to`, or :all when there
  is no `from`. Only the trees matter: a snapshot of the working tree is never
  an ancestor of the commit made from it."
  [repo from to]
  (if from
    ;; -z, or git quotes non-ASCII paths: "bl\303\245_test.clj"
    (let [out (git repo "diff" "-z" "--name-only" "--no-renames" from to)]
      (if (str/blank? out) [] (str/split out #"\u0000")))
    :all))

(def ^:private snapshot-message "deltatest: working tree")

(defn- working-tree-commit
  "A commit of the working tree of `repo`: tracked files as they are on disk,
  and untracked files that are not ignored. Built in a copy of the index, so
  the repository's own index, branches and working tree are left alone."
  [repo]
  (let [index (str (fs/absolutize (fs/path repo (git repo "rev-parse" "--git-path" "deltatest-index"))))
        with-index {:dir (str repo) :out :string :err :string
                    :extra-env {"GIT_INDEX_FILE" index}}]
    (try
      (fs/copy (fs/path repo (git repo "rev-parse" "--git-path" "index")) index
               {:replace-existing true})
      (p/shell with-index "git" "add" "-A")
      (let [tree (str/trim (:out (p/shell with-index "git" "write-tree")))]
        (git repo "commit-tree" "-p" "HEAD" "-m" snapshot-message tree))
      (finally (fs/delete-if-exists index)))))

(defn- short-sha [sha]
  (subs sha 0 8))

(defn- commit-name
  "How a commit is named in output: a snapshot of the working tree by the
  commit it was made on."
  [{:keys [sha parent snapshot?]}]
  (if snapshot? (str "the working tree on " (short-sha parent)) (short-sha sha)))

(defn- green-line
  "`commits` is how many commits lead from `green` to `tested`, or nil when
  `green` is not an ancestor. Snapshots count from the commit they were made on."
  [{:keys [green tested commits]}]
  (cond
    (nil? green) (str "No green ref; testing everything " (if (:snapshot? tested) "in " "at ")
                      (commit-name tested) ".")
    (nil? commits) (str "Green ref at " (commit-name green) ", not an ancestor of "
                        (commit-name tested) ".")
    :else (str "Green ref at " (commit-name green) ", " commits
               (if (= 1 commits) " commit" " commits") " before " (commit-name tested) ".")))

(defn- commit-info [repo sha]
  (let [[parents subject] (str/split-lines (git repo "log" "-1" "--format=%P%n%s" sha))]
    {:sha sha
     :parent (first (str/split parents #" "))
     :snapshot? (= snapshot-message subject)}))

(defn- commits-between
  "Commits from `green` to `tested`, or nil when `green` is not an ancestor."
  [repo green tested]
  (let [on #(if (:snapshot? %) (:parent %) (:sha %))]
    (when (git-ok? repo "merge-base" "--is-ancestor" (on green) (on tested))
      (parse-long (git repo "rev-list" "--count" (str (on green) ".." (on tested)))))))

(defn- default-worktree [repo]
  (let [common (str (fs/canonicalize (fs/path repo (git repo "rev-parse" "--git-common-dir"))))
        state (or (System/getenv "XDG_STATE_HOME")
                  (str (fs/path (fs/home) ".local" "state")))
        key (str (fs/file-name (fs/parent common)) "-" (short-hash common))]
    (str (fs/path state "deltatest" key "worktree"))))

(defn- ensure-worktree [repo worktree sha]
  (if (fs/exists? worktree)
    (when-not (str/blank? (git worktree "status" "--porcelain"))
      (throw (ex-info (str "Worktree has changes, refusing to move it: " worktree) {})))
    (git repo "worktree" "add" "--detach" (str worktree) sha))
  (git worktree "checkout" "-q" "--detach" sha))

;; nREPL

(defn- ->str [x]
  (if (bytes? x) (String/new ^bytes x "UTF-8") x))

(defn- nrepl-eval
  "Evaluate `code` over nREPL at `port`. Returns {:value :out :err :ex}, with
  :value the last value as a string. Output is also handed to `emit` as it
  arrives."
  ([port code] (nrepl-eval port code (fn [_ _])))
  ([port code emit]
   (with-open [socket (Socket/new "localhost" (int port))]
     (let [in (PushbackInputStream/new (Socket/.getInputStream socket))
           out (Socket/.getOutputStream socket)]
       (bencode/write-bencode out {"op" "eval" "code" code "id" "deltatest"})
       (loop [acc {}]
         (let [msg (update-vals (bencode/read-bencode in) ->str)
               acc (cond-> acc
                     (msg "value") (assoc :value (msg "value"))
                     (msg "out") (update :out str (msg "out"))
                     (msg "err") (update :err str (msg "err"))
                     (msg "ex") (assoc :ex (msg "ex")))]
           (some->> (msg "out") (emit :out))
           (some->> (msg "err") (emit :err))
           (if (some #{"done"} (map ->str (msg "status")))
             acc
             (recur acc))))))))

(defn- port [worktree]
  (let [f (fs/file worktree ".nrepl-port")]
    (when (fs/exists? f)
      (parse-long (str/trim (slurp f))))))

(defn- answers? [port]
  (try (= "1" (:value (nrepl-eval port "1")))
       (catch Exception _ false)))

;; JVM

(defn- jvm-file
  "The JVM's pid record (`edn`) or its output (`log`), in the worktree's own
  git dir, where `git status` does not see them."
  [worktree ext]
  (str (fs/path worktree (git worktree "rev-parse" "--git-path" (str "deltatest-jvm." ext)))))

(defn stop-jvm
  "Stop the JVM last started in the worktree, and what it started. Called from
  a babashka task, with `opts` as for `run-changes`. A pid can be reused, so it
  counts only with the start instant recorded beside it."
  [{:keys [repo worktree] :or {repo "."}}]
  (let [worktree (or worktree (default-worktree repo))
        f (when (fs/exists? worktree) (jvm-file worktree "edn"))
        [pid started] (when (and f (fs/exists? f)) (edn/read-string (slurp f)))
        h (some-> pid ProcessHandle/of (Optional/.orElse nil))]
    (when (and h started (= started (some-> h ProcessHandle/.info ProcessHandle$Info/.startInstant (Optional/.orElse nil) str)))
      (let [hs (cons h (iterator-seq (Stream/.iterator (ProcessHandle/.descendants h))))]
        (run! ProcessHandle/.destroyForcibly hs)
        (run! #(CompletableFuture/.join (ProcessHandle/.onExit %)) hs)))))

(defn- start-jvm
  "Starts `jvm-cmd` in `worktree` as a background job of `sh` with job control
  on, which gives it a process group of its own: neither Ctrl-C nor closing the
  terminal reaches it. Returns its ProcessHandle, or nil if it is already gone."
  [worktree jvm-cmd]
  (let [pid (parse-long (str/trim (:out (p/shell {:dir (str worktree) :out :string}
                                                "sh" "-c" "set -m; sh -c \"$1\" </dev/null >\"$2\" 2>&1 & echo $!"
                                                "sh" jvm-cmd (jvm-file worktree "log")))))
        h (Optional/.orElse (ProcessHandle/of pid) nil)]
    (spit (jvm-file worktree "edn")
          (pr-str [pid (some-> h ProcessHandle/.info ProcessHandle$Info/.startInstant (Optional/.orElse nil) str)]))
    h))

(defn- seconds-since [t0]
  (format "%.1f s" (/ (- (System/nanoTime) t0) 1e9)))

(defn- ensure-jvm
  "Returns the port of a JVM that answers, starting one if none does."
  [worktree jvm-cmd emit]
  (if-let [p (some-> (port worktree) (#(when (answers? %) %)))]
    (do (emit :out (str "Warm JVM on port " p ".\n"))
        p)
    (let [t0 (System/nanoTime)]
      (emit :out "Starting JVM… ")
      (stop-jvm {:worktree worktree})
      (fs/delete-if-exists (fs/file worktree ".nrepl-port"))
      (let [jvm (start-jvm worktree jvm-cmd)]
        (loop [tries 3000]
          (let [p (port worktree)]
            (cond (and p (answers? p)) (do (emit :out (str "up in " (seconds-since t0) ".\n"))
                                           p)
                  (not (some-> jvm ProcessHandle/.isAlive)) (throw (ex-info (str "JVM exited before answering on nREPL:\n"
                                                                                 (slurp (jvm-file worktree "log")))
                                                                            {:worktree worktree}))
                  (zero? tries) (throw (ex-info "JVM did not answer on nREPL" {:worktree worktree}))
                  :else (do (Thread/sleep 100) (recur (dec tries))))))))))

(def ^:private agent-source
  (delay (slurp (io/resource "teodorlu/deltatest/agent.clj"))))

(defn- ensure-agent
  "Loads the agent with a file name, so its stack frames look like any other
  (a test that validates stack traces fails on a frame without a file)."
  [port]
  (let [h (hash @agent-source)
        code (format "(if (= %d (some-> (resolve 'teodorlu.deltatest.agent/source-hash) deref)) :cached (do (clojure.lang.Compiler/load (java.io.StringReader. %s) \"teodorlu/deltatest/agent.clj\" \"agent.clj\") (intern 'teodorlu.deltatest.agent 'source-hash %d) :loaded))"
                     h (pr-str @agent-source) h)
        {:keys [ex err value]} (nrepl-eval port code)]
    (when ex (throw (ex-info (str "Could not load agent: " err) {})))
    value))

;; run

(defn- ms-since [t0]
  (quot (- (System/nanoTime) t0) 1000000))

(defn- call-agent
  "Call `f` in the agent with `arg`, handing output to `emit` as it arrives."
  [port emit f arg]
  (let [{:keys [value ex]} (nrepl-eval port (pr-str (list f (list 'quote arg))) emit)]
    (if ex {:error "Eval failed."} (edn/read-string value))))

(defn- print-stream [stream text]
  (binding [*out* (if (= :err stream) *err* *out*)]
    (print text)
    (flush)))

(defn- not-watched [changed]
  (when (sequential? changed)
    (remove #(re-find #"\.cljc?$" %) changed)))

(defn- colored [green? s]
  (str (if green? "\u001b[32m" "\u001b[31m") s "\u001b[m"))

(defn- report!
  "Emit what follows the test output."
  [emit {:keys [error changed sha from selected test-namespaces green? ms]}]
  (when error (emit :out (str error "\n")))
  (when-let [files (seq (not-watched changed))]
    (emit :out (str "Changed, not followed: " (str/join " " files) "\n")))
  (when (and test-namespaces (empty? selected))
    (emit :out (str (colored true "0 tests, 0 assertions, 0 failures.") "\n")))
  (emit :out (str (colored green? (str (short-sha sha) (when from (str " since " (short-sha from))) ", "
                                       (when test-namespaces
                                         (str (count selected) " of " test-namespaces " test namespaces, "))
                                       ms " ms."))
                  "\n")))

(defn run-changes
  "Move the worktree to `rev`, run the affected tests there, and move the
  green ref when they pass. Everything it prints goes through `emit`, called
  with :out or :err and a string."
  [{:keys [repo rev worktree jvm-cmd test-paths emit]
    :or {repo "." rev "HEAD" test-paths ["test"] emit print-stream}}]
  (let [t0 (System/nanoTime)
        repo (str (fs/absolutize repo))
        worktree (str (fs/normalize (fs/absolutize (or worktree (default-worktree repo)))))
        sha (git repo "rev-parse" "--verify" (str rev "^{commit}"))
        from (green repo)
        tested (commit-info repo sha)
        green (some->> from (commit-info repo))
        _ (emit :out (str (green-line {:green green :tested tested
                                       :commits (when green (commits-between repo green tested))})
                          "\n"))
        changed (changed-files repo from sha)
        _ (ensure-worktree repo worktree sha)
        port (ensure-jvm worktree jvm-cmd emit)
        _ (ensure-agent port)
        t1 (System/nanoTime)
        _ (emit :out "Reloading… ")
        reloaded (call-agent port emit 'teodorlu.deltatest.agent/reload changed)
        _ (emit :out (if (:error reloaded) "failed.\n" (str (seconds-since t1) ".\n")))
        result (if (:error reloaded)
                 reloaded
                 (call-agent port emit 'teodorlu.deltatest.agent/run
                             {:touched (:touched reloaded) :test-paths test-paths}))
        green? (and (not (:error result))
                    (zero? (+ (get-in result [:summary :fail] 0)
                              (get-in result [:summary :error] 0))))
        result (merge result {:sha sha :from from :changed changed :green? green?
                              :ms (ms-since t0)})]
    (when green? (git repo "update-ref" green-ref sha))
    (report! emit result)
    result))

(defn- exit-unless-green [{:keys [green?]}]
  (when-not green?
    (throw (ex-info "Not green" {:babashka/exit 1}))))

(defn test-head
  "Test HEAD. Called from a babashka task, with `opts` as for `run-changes`."
  [opts]
  (exit-unless-green (run-changes opts)))

(defn test-tree
  "Test the working tree, uncommitted changes included. Called from a babashka
  task, with `opts` as for `run-changes`."
  [opts]
  (exit-unless-green (run-changes (assoc opts :rev (working-tree-commit (:repo opts "."))))))
