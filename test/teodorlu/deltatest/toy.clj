(ns teodorlu.deltatest.toy
  "A small git repository to run deltatest against: toy.a requires
  toy.b requires toy.c, and each has a test namespace."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]))

(def jvm-cmd
  "clojure -Sdeps '{:deps {nrepl/nrepl {:mvn/version \"1.4.0\"}}}' -M -m nrepl.cmdline")

(defn- git [dir & args]
  (apply p/shell {:dir (str dir) :out :string :err :string} "git" args))

(defn write! [dir path content]
  (let [f (fs/file dir path)]
    (fs/create-dirs (fs/parent f))
    (spit f content)))

(defn commit! [dir message]
  (git dir "add" "-A")
  (git dir "commit" "-q" "-m" message))

(defn source [ns requires value]
  (str "(ns " ns (when requires (str " (:require [" requires "])")) ")\n"
       "(defn value [] " value ")\n"))

(defn test-source [ns tested expected]
  (str "(ns " ns " (:require [clojure.test :refer [deftest is]] [" tested "]))\n"
       "(deftest value (is (= " expected " (" tested "/value))))\n"))

(defn create!
  "A fresh toy repository in `dir`, with one commit on main."
  [dir]
  (fs/delete-tree dir)
  (fs/create-dirs dir)
  (git dir "init" "-q" "-b" "main")
  (git dir "config" "user.email" "toy@example.com")
  (git dir "config" "user.name" "toy")
  (write! dir ".gitignore" "/.cpcache\n/.nrepl-port\n")
  (write! dir "deps.edn" "{:paths [\"src\" \"test\"] :deps {io.github.tonsky/clj-reload {:mvn/version \"1.0.0\"}}}\n")
  (write! dir "src/toy/c.clj" (source "toy.c" nil 1))
  (write! dir "src/toy/b.clj" (source "toy.b" "toy.c" "(toy.c/value)"))
  (write! dir "src/toy/a.clj" (source "toy.a" "toy.b" "(toy.b/value)"))
  (write! dir "test/toy/c_test.clj" (test-source "toy.c-test" "toy.c" 1))
  (write! dir "test/toy/b_test.clj" (test-source "toy.b-test" "toy.b" 1))
  (write! dir "test/toy/a_test.clj" (test-source "toy.a-test" "toy.a" 1))
  (commit! dir "toy")
  dir)
