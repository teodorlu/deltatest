(ns teodorlu.deltatest-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [teodorlu.deltatest :as deltatest]
            [teodorlu.deltatest.toy :as toy]))

(defn- tmp-dir []
  (str (if-let [base (System/getenv "DELTATEST_TMP")]
         (fs/create-temp-dir {:dir (fs/create-dirs base)})
         (fs/create-temp-dir))))

(deftest green-line
  (let [commit (fn [sha] {:sha sha})
        tree (fn [parent] {:sha "5a5a5a5a" :parent parent :snapshot? true})]
    (is (= {:head-first "No green ref; testing everything at d8d84cd5."
            :tree-first "No green ref; testing everything in the working tree on d8d84cd5."
            :head-again "Green ref at d8d84cd5, 0 commits before d8d84cd5."
            :head-after-commits "Green ref at 71cd466e, 3 commits before d8d84cd5."
            :tree-with-edits "Green ref at d8d84cd5, 0 commits before the working tree on d8d84cd5."
            :tree-after-commits "Green ref at 71cd466e, 3 commits before the working tree on d8d84cd5."
            :head-after-green-tree "Green ref at the working tree on 71cd466e, 1 commit before d8d84cd5."
            :tree-after-green-tree "Green ref at the working tree on d8d84cd5, 0 commits before the working tree on d8d84cd5."
            :head-elsewhere "Green ref at 71cd466e, not an ancestor of d8d84cd5."
            :tree-elsewhere "Green ref at 71cd466e, not an ancestor of the working tree on d8d84cd5."}
           (update-vals
            {:head-first {:tested (commit "d8d84cd5")}
             :tree-first {:tested (tree "d8d84cd5")}
             :head-again {:green (commit "d8d84cd5") :tested (commit "d8d84cd5") :commits 0}
             :head-after-commits {:green (commit "71cd466e") :tested (commit "d8d84cd5") :commits 3}
             :tree-with-edits {:green (commit "d8d84cd5") :tested (tree "d8d84cd5") :commits 0}
             :tree-after-commits {:green (commit "71cd466e") :tested (tree "d8d84cd5") :commits 3}
             :head-after-green-tree {:green (tree "71cd466e") :tested (commit "d8d84cd5") :commits 1}
             :tree-after-green-tree {:green (tree "d8d84cd5") :tested (tree "d8d84cd5") :commits 0}
             :head-elsewhere {:green (commit "71cd466e") :tested (commit "d8d84cd5")}
             :tree-elsewhere {:green (commit "71cd466e") :tested (tree "d8d84cd5")}}
            #'deltatest/green-line)))))

(deftest run-changes
  (let [dir (tmp-dir)
        repo (toy/create! (str (fs/path dir "toy")))
        worktree (str (fs/path dir "toy-latest"))
        out (atom "")
        step (fn [path content]
               (when path
                 (toy/write! repo path content)
                 (toy/commit! repo path))
               (let [{:keys [selected green?]}
                     (deltatest/run-changes {:repo repo :worktree worktree :jvm-cmd toy/jvm-cmd
                                             :emit (fn [_ text] (swap! out str text))})]
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
               [#{toy.blå-test} true]
               [#{toy.a-test toy.b-test toy.c-test toy.d-test toy.blå-test} true]]
             [(step nil nil)
              (step nil nil)
              (step "src/toy/a.clj" (toy/source "toy.a" "toy.b" "(+ 0 (toy.b/value))"))
              (step "src/toy/c.clj" (toy/source "toy.c" nil "(+ 0 1)"))
              (step "test/toy/d_test.clj" (toy/test-source "toy.d-test" "toy.a" 1))
              (step "src/toy/b.clj" (toy/source "toy.b" "toy.c" 2))
              (step "README" "unrelated")
              (step "src/toy/b.clj" (toy/source "toy.b" "toy.c" 1))
              (step "test/toy/blå_test.clj" (toy/test-source "toy.blå-test" "toy.c" 1))
              (do (deltatest/forget-green {:repo repo})
                  (step nil nil))]))
      (is (str/includes? @out "in toy.b-test/value (b_test.clj:2)"))
      (finally
        (deltatest/stop-jvm {:worktree worktree})
        (fs/delete-tree dir)))))

(deftest working-tree
  (let [dir (tmp-dir)
        repo (toy/create! (str (fs/path dir "toy")))
        worktree (str (fs/path dir "toy-latest"))
        selected (fn [rev]
                   (set (:selected (deltatest/run-changes {:repo repo :worktree worktree
                                                           :jvm-cmd toy/jvm-cmd :rev rev
                                                           :emit (fn [_ _])}))))]
    (try
      (selected "HEAD")
      (toy/write! repo "test/toy/d_test.clj" (toy/test-source "toy.d-test" "toy.a" 1))
      (is (= '[#{toy.d-test} "?? test/toy/d_test.clj\n" #{}]
             [(selected (#'deltatest/working-tree-commit repo))
              (:out (p/shell {:dir repo :out :string} "git" "status" "--porcelain"))
              (do (toy/commit! repo "d") (selected "HEAD"))]))
      (finally
        (deltatest/stop-jvm {:worktree worktree})
        (fs/delete-tree dir)))))
