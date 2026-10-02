# deltatest

Run the tests your latest changes could have broken, and only those.

## Rationale

Running the whole suite in a fresh worktree and a fresh JVM catches what you
forgot to commit, and costs the whole suite every time.

`deltatest` keeps one worktree and one JVM around. Each run moves the
worktree to HEAD, or to a commit of your working tree, reloads what changed,
and runs the test namespaces that depend on a changed namespace, transitively. The rest cannot have changed
behaviour, as far as `ns` forms can tell.

## Requirements

The project under test needs:

- Clojure 1.11 or later. The half that runs in its JVM does not assume 1.12.
- Code that clj-reload can reload cleanly. Every run reloads what changed
  with clj-reload, in a JVM that stays up between runs.

deltatest itself runs on Clojure 1.12 or later, or on Babashka 1.12.194 or
later.

## Usage

deltatest is a library, meant to be called from babashka tasks. Add it to
`bb.edn` and give each of its four functions a task:

```clojure
;; bb.edn
{:deps {io.github.teodorlu/deltatest {:git/sha "f99bffc43b2914f63994f6b28ec75ab5eccdc22d"}}
 :tasks
 {:init (def deltatest-opts {:jvm-cmd "clojure -Sdeps '{:deps {nrepl/nrepl {:mvn/version \"1.4.0\"}}}' -M:test -m nrepl.cmdline"})
  :requires ([teodorlu.deltatest :as deltatest])
  deltatest-head (deltatest/test-head deltatest-opts)
  deltatest-tree (deltatest/test-tree deltatest-opts)
  deltatest-stop (deltatest/stop-jvm deltatest-opts)
  deltatest-forget (deltatest/forget-green deltatest-opts)}}
```

`test-head` tests HEAD. `test-tree` tests the working tree: tracked
files as they are on disk, and untracked files that are not ignored.
`stop-jvm` stops the JVM they keep running; the next test run starts
another. It reads only `:repo` and `:worktree`.
`forget-green` deletes the green ref, so the next run tests everything,
for when a run went green over a change it does not follow. It leaves any
running JVM online, so a value read when a namespace was loaded stays as it
was until `stop-jvm`. It reads only `:repo`.

Keys, all but `:jvm-cmd` optional:

- `:jvm-cmd` starts an nREPL server in the worktree. It must put
  clj-reload, kaocha and the test paths on the classpath, and write
  `.nrepl-port`, which `nrepl.cmdline` does when no port is given.
- `:worktree` overrides the path of deltatest's git worktree. Default: one
  per repository, under `$XDG_STATE_HOME/deltatest/`
  (`~/.local/state/deltatest/` when `XDG_STATE_HOME` is unset). Every commit
  and every git worktree of that repository shares it, and so shares its JVM.
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

## When deltatest misses a regression

deltatest assumes your code can be reloaded by clj-reload, and that static
analysis of namespace `:require`s finds the tests a change affects. Where
that does not hold, deltatest can succeed when a fresh test run would fail.

This can happen when:

- a file other than `.clj` or `.cljc` changes, such as `deps.edn`, a
  resource or a `.cljs` file. It selects no tests, and a changed `deps.edn`
  does not reach the warm JVM.
- a test reaches changed code without requiring it, through
  `requiring-resolve`, a multimethod defined in a namespace it does not
  require, or a data file it reads.
- state from earlier runs survives a reload and changes how code or tests
  behave: `defonce` values, which clj-reload keeps, system properties,
  running threads.
- deltatest runs twice at once in one repository. Every call in one
  repository, from any of its git worktrees, uses the same deltatest worktree
  and JVM, so one call can move the worktree while the other is testing it.
  Let one finish before starting the next.

When you suspect one of the first three, run `forget-green` and `stop-jvm`.
The next run tests everything in a fresh JVM, as a fresh test run would.

## Development

Run the tests with

```
bb test
```

They create scratch git repositories and start a JVM in some of them, which
they stop when done. The repositories go in the system temp directory, or
under `$DELTATEST_TMP` when it is set.
