# How it works

## The green commit is a git ref

The last green commit is stored as `refs/deltatest/green`.
The tests to run are selected from `git diff <green> <tested>`.
So:

- A red run keeps its tests selected until they pass.
- A restarted JVM selects the same tests as a warm one.
- With no green ref, everything runs.
- The diff compares trees, not history, so `test-head` and `test-tree` share
  one green ref.
  Commit a green working tree, and testing `HEAD` has nothing left to run.

## The working tree is tested as a commit

`test-tree` builds a commit in a copy of the index, with `git add -A`,
`git write-tree` and `git commit-tree`.
No branch points at it; the green ref does once it passes.
Your index, branches and files are left alone.

## The dependency graph is clj-reload's

The graph is read from the JVM under test, after reloading.
No clj-kondo, no second analysis.

## The JVM survives Ctrl-C and closing the terminal

The JVM is started by `sh` with job control on, which gives it a process
group of its own.
It counts as up when it answers an nREPL eval.
Its pid and output are in the worktree's git dir, in `deltatest-jvm.edn` and
`deltatest-jvm.log`.

## The project under test does not depend on Deltatest

The part that runs in the JVM, `src/teodorlu/deltatest/agent.clj`, is sent
over nREPL as source.
It runs tests with `kaocha.repl/run`.
