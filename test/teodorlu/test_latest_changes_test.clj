(ns teodorlu.test-latest-changes-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.test :refer [deftest is]]
            [teodorlu.test-latest-changes :as tlc]
            [teodorlu.test-latest-changes.toy :as toy]))

(defn- tmp-dir []
  (str (if-let [base (System/getenv "TLC_TMP")]
         (fs/create-temp-dir {:dir (fs/create-dirs base)})
         (fs/create-temp-dir))))

(deftest run-changes
  (let [dir (tmp-dir)
        repo (toy/create! (str (fs/path dir "toy")))
        worktree (str (fs/path dir "toy-latest"))
        step (fn [path content]
               (when path
                 (toy/write! repo path content)
                 (toy/commit! repo path))
               (let [{:keys [selected green?]}
                     (tlc/run-changes {:repo repo :worktree worktree :jvm-cmd toy/jvm-cmd})]
                 [(set selected) green?]))]
    (try
      (is (= '[[#{toy.a-test toy.b-test toy.c-test} true]
               [#{} true]
               [#{toy.a-test} true]
               [#{toy.a-test toy.b-test toy.c-test} true]
               [#{toy.d-test} true]
               [#{toy.a-test toy.b-test toy.d-test} false]
               [#{toy.a-test toy.b-test toy.d-test} false]
               [#{toy.a-test toy.b-test toy.d-test} true]
               [#{toy.blå-test} true]]
             [(step nil nil)
              (step nil nil)
              (step "src/toy/a.clj" (toy/source "toy.a" "toy.b" "(+ 0 (toy.b/value))"))
              (step "src/toy/c.clj" (toy/source "toy.c" nil "(+ 0 1)"))
              (step "test/toy/d_test.clj" (toy/test-source "toy.d-test" "toy.a" 1))
              (step "src/toy/b.clj" (toy/source "toy.b" "toy.c" 2))
              (step "README" "unrelated")
              (step "src/toy/b.clj" (toy/source "toy.b" "toy.c" 1))
              (step "test/toy/blå_test.clj" (toy/test-source "toy.blå-test" "toy.c" 1))]))
      (finally
        (p/shell {:continue true :out :string :err :string}
                 "bgproc" "stop" "-n" (#'tlc/process-name worktree))
        (fs/delete-tree dir)))))

(deftest working-tree
  (let [dir (tmp-dir)
        repo (toy/create! (str (fs/path dir "toy")))
        worktree (str (fs/path dir "toy-latest"))
        selected (fn [rev]
                   (set (:selected (tlc/run-changes {:repo repo :worktree worktree
                                                     :jvm-cmd toy/jvm-cmd :rev rev}))))]
    (try
      (selected "HEAD")
      (toy/write! repo "test/toy/d_test.clj" (toy/test-source "toy.d-test" "toy.a" 1))
      (is (= '[#{toy.d-test} "?? test/toy/d_test.clj\n" #{}]
             [(selected (#'tlc/working-tree-commit repo))
              (:out (p/shell {:dir repo :out :string} "git" "status" "--porcelain"))
              (do (toy/commit! repo "d") (selected "HEAD"))]))
      (finally
        (p/shell {:continue true :out :string :err :string}
                 "bgproc" "stop" "-n" (#'tlc/process-name worktree))
        (fs/delete-tree dir)))))
