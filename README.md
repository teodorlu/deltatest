# test-latest-changes

Run the tests your latest changes could have broken, and only those.

## Rationale

Running the whole suite in a fresh worktree and a fresh JVM catches what you
forgot to commit, and costs the whole suite every time.

`test-latest-changes` keeps one worktree and one JVM around. Each run moves the
worktree to HEAD, or to a commit of your working tree, reloads what changed,
and runs the test namespaces that depend on a changed namespace, transitively. The rest cannot have changed
behaviour, as far as `ns` forms can tell.

## Usage

Needs [bgproc](https://github.com/ascorbic/bgproc) on the `PATH`:

```
npm install -g bgproc
```

There is no command line. Call one of two functions from a babashka task:

```clojure
;; bb.edn
{:deps {io.github.teodorlu/test-latest-changes {:local/root "../test-latest-changes"}}
 :tasks
 {test-head
  {:requires ([teodorlu.test-latest-changes :as tlc])
   :task (tlc/test-head {:jvm-cmd "clojure -Sdeps '{:deps {nrepl/nrepl {:mvn/version \"1.4.0\"}}}' -M:test -m nrepl.cmdline"})}
  test-working-tree
  {:requires ([teodorlu.test-latest-changes :as tlc])
   :task (tlc/test-working-tree {:jvm-cmd "clojure -Sdeps '{:deps {nrepl/nrepl {:mvn/version \"1.4.0\"}}}' -M:test -m nrepl.cmdline"})}}}
```

`test-head` tests HEAD. `test-working-tree` tests the working tree: tracked
files as they are on disk, and untracked files that are not ignored.

Keys, all but `:jvm-cmd` optional:

- `:jvm-cmd` starts an nREPL server in the worktree. It must put
  clj-reload and the test paths on the classpath, and write `.nrepl-port`,
  which `nrepl.cmdline` does when no port is given.
- `:worktree` is where the worktree lives. Default
  `$XDG_STATE_HOME/test-latest-changes/<repo>-<hash>/worktree`, with
  `~/.local/state` when `XDG_STATE_HOME` is unset.
- `:repo` is the repository, default `.`.
- `:test-paths` is a vector, default `["test"]`.

The task fails unless everything selected passed.

## How it works

- **Green is a git ref**, `refs/test-latest-changes/green`. What runs is
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
- **The JVM is kept up by bgproc**, and is up when it answers an nREPL eval.
- **The half that runs in the JVM is sent as source**
  (`src/teodorlu/test_latest_changes/agent.clj`), so the project under test
  does not depend on this library. It uses `clojure.test`.

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
bb test          # kaocha on the JVM; starts toy JVMs under bgproc
TLC_TMP=../tmp bb test   # keep toy repositories somewhere of your choosing
```
