# deltatest

Run the tests your latest changes could have broken, and only those.

## Rationale

Running the whole suite in a fresh worktree and a fresh JVM catches what you
forgot to commit, and costs the whole suite every time.

`deltatest` keeps one worktree and one JVM around. Each run moves the
worktree to HEAD, or to a commit of your working tree, reloads what changed,
and runs the test namespaces that depend on a changed namespace, transitively. The rest cannot have changed
behaviour, as far as `ns` forms can tell.

## Usage

There is no command line. Call one of three functions from a babashka task:

```clojure
;; bb.edn
{:deps {io.github.teodorlu/deltatest {:local/root "../deltatest"}}
 :tasks
 {test-head
  {:requires ([teodorlu.deltatest :as deltatest])
   :task (deltatest/test-head {:jvm-cmd "clojure -Sdeps '{:deps {nrepl/nrepl {:mvn/version \"1.4.0\"}}}' -M:test -m nrepl.cmdline"})}
  test-tree
  {:requires ([teodorlu.deltatest :as deltatest])
   :task (deltatest/test-tree {:jvm-cmd "clojure -Sdeps '{:deps {nrepl/nrepl {:mvn/version \"1.4.0\"}}}' -M:test -m nrepl.cmdline"})}
  stop-jvm
  {:requires ([teodorlu.deltatest :as deltatest])
   :task (deltatest/stop-jvm {})}}}
```

`test-head` tests HEAD. `test-tree` tests the working tree: tracked
files as they are on disk, and untracked files that are not ignored.
`stop-jvm` stops the JVM they keep running; the next test run starts
another. It reads only `:repo` and `:worktree`.

Keys, all but `:jvm-cmd` optional:

- `:jvm-cmd` starts an nREPL server in the worktree. It must put
  clj-reload, kaocha and the test paths on the classpath, and write
  `.nrepl-port`, which `nrepl.cmdline` does when no port is given.
- `:worktree` is where the worktree lives. Default
  `$XDG_STATE_HOME/deltatest/<repo>-<hash>/worktree`, with
  `~/.local/state` when `XDG_STATE_HOME` is unset.
- `:repo` is the repository, default `.`.
- `:test-paths` is a vector, default `["test"]`.

The task fails unless everything selected passed.

The selected tests run with kaocha, configured by the project's `tests.edn`,
so they print as kaocha would, reporter included, while they run.

## How it works

- **Green is a git ref**, `refs/deltatest/green`. What runs is
  decided by `git diff <green> <tested>`, so a red run keeps its tests
  selected until they pass, and a restarted JVM selects the same tests as a
  warm one. No ref runs everything. The diff compares trees, not history, so
  the two functions share one green ref: commit a green working tree, and
  testing HEAD has nothing left to run.
- **The working tree is tested as a commit.** It is built in a copy of the
  index with `git add -A`, `git write-tree` and `git commit-tree`. No branch
  points at it; the green ref does once it passes. Your index, branches and
  files are left alone.
- **The dependency graph is clj-reload's**, read from the JVM under test after
  reloading. No clj-kondo, no second analysis.
- **The JVM is started by `sh` with job control on**, which gives it a
  process group of its own, so Ctrl-C and closing the terminal leave it
  running. It is up when it answers an nREPL eval. Its pid and output are in
  the worktree's git dir, `deltatest-jvm.edn` and `deltatest-jvm.log`.
- **The half that runs in the JVM is sent as source**
  (`src/teodorlu/deltatest/agent.clj`), so the project under test
  does not depend on this library. It runs tests with `kaocha.repl/run`.

## Not handled

- Changes to anything but `.clj`/`.cljc` are listed as "Changed, not
  followed", and select nothing.
- Dependencies that are not in `ns` forms: `requiring-resolve`, multimethods
  defined where the test does not require, data files.
- Drift in the JVM beyond what clj-reload unloads.
- Two runs at once against the same worktree.
- A file that does not parse is red, but clj-reload 1.0.0 reports it as
  `Cannot throw exception because "exception" is null`, not as the syntax error.

## Development

```
bb test          # kaocha on the JVM; starts toy JVMs
DELTATEST_TMP=../tmp bb test   # keep toy repositories somewhere of your choosing
```
