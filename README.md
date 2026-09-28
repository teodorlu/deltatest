# test-latest-changes

Run the tests your latest commits could have broken, and only those.

## Rationale

Running the whole suite in a fresh worktree and a fresh JVM catches what you
forgot to commit, and costs the whole suite every time.

`test-latest-changes` keeps one worktree and one JVM around. Each run moves the
worktree to `main`, reloads what changed, and runs the test namespaces that
depend on a changed namespace, transitively. The rest cannot have changed
behaviour, as far as `ns` forms can tell.

## Usage

```clojure
;; bb.edn
{:deps {io.github.teodorlu/test-latest-changes {:local/root "../test-latest-changes"}}
 :tasks
 {test-latest-changes
  {:requires ([teodorlu.test-latest-changes])
   :task (apply teodorlu.test-latest-changes/main *command-line-args*)}}}
```

```
bb test-latest-changes --jvm-cmd "clojure -Sdeps '{:deps {nrepl/nrepl {:mvn/version \"1.4.0\"}}}' -M:test -m nrepl.cmdline"
```

Options, all but `--jvm-cmd` optional:

- `--jvm-cmd` starts an nREPL server in the worktree. It must put
  clj-reload and the test paths on the classpath, and write `.nrepl-port`,
  which `nrepl.cmdline` does when no port is given.
- `--rev` is what to test, default `main`.
- `--worktree` is where the worktree lives. Default
  `$XDG_STATE_HOME/test-latest-changes/<repo>-<hash>/worktree`, with
  `~/.local/state` when `XDG_STATE_HOME` is unset.
- `--repo` is the repository, default `.`.
- `--test-paths` is a comma-separated list, default `test`.

Exit status is 0 when everything selected passed.

## How it works

- **Green is a git ref**, `refs/test-latest-changes/green`. What runs is
  decided by `git diff <green> <rev>`, so a red run keeps its tests selected
  until they pass, and a restarted JVM selects the same tests as a warm one.
  No ref, or a ref that is not an ancestor of `<rev>`, runs everything.
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

## Development

```
bb test          # kaocha on the JVM; starts toy JVMs under bgproc
TLC_TMP=../tmp bb test   # keep toy repositories somewhere of your choosing
```
