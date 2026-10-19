# deltatest

Test.
Alter code.
Run the tests impacted by the code you changed.
Don't wait for JVM restarts.

## How

Deltatest remembers the latest green commit.
After loading new code, the static dependency graph determines which tests to run.
No JVM restarts and no re-running tests that don't affect our work speeds up the test run.

## Limitations

**Deltatest needs static dependencies between namespaces.**
Deltatest understands static requires like `(ns a (:require b))`, but not dynamic `require` or `requiring-resolve` calls.

**Deltatest needs your code to be reloadable by clj-reload**.
If a [clj-reload] reload of your code fails, Deltatest won't work.

## Quickstart

```clojure
;; In your bb.edn, add
{:deps {io.github.teodorlu/deltatest {:git/sha "e8e37323181ab67d085534ad09b5f5c65ba11ee9"}}
 :tasks
 {:init (def deltatest-opts {:jvm-cmd "clojure -Sdeps '{:deps {nrepl/nrepl {:mvn/version \"1.4.0\"}}}' -M:test -m nrepl.cmdline"})
  :requires ([teodorlu.deltatest :as deltatest])

  ;; ── Key commands ───────────
  ;; Test your latest commit; where HEAD points
  deltatest-head (deltatest/test-head deltatest-opts)
  ;; Test the files as you see them; what Git calls your worktree
  deltatest-tree (deltatest/test-tree deltatest-opts)

  ;; ── Peripheral commands ────
  ;; Stop Deltatest's JVM process, eg to load new deps.edn on next test
  deltatest-stop (deltatest/stop-jvm deltatest-opts)
  ;; Forget Deltatest's last known green commit, all tests will run next time
  deltatest-forget (deltatest/forget-green deltatest-opts)
  ;; Do a completely clean run of the latest commit. Similar to CI servers, no shortcuts.
  test-head (doto deltatest-opts
	           deltatest/stop-jvm
			   deltatest/forget-green
			   deltatest/test-head)}}
```

All commands take an option map.
`:jvm-cmd` is required.
It should start your Clojure process, with an [nREPL] server.
[clj-reload] and [Kaocha] must be on its classpath.
When the Clojure process is ready to take nREPL commands, an `.nrepl-port` file must exist.

Optional keys:

- `:worktree` overrides the path of deltatest's git worktree. Default: one
  per repository, under `$XDG_STATE_HOME/deltatest/`
  (`~/.local/state/deltatest/` when `XDG_STATE_HOME` is unset). Every commit
  and every git worktree of that repository shares it, and so shares its JVM.
- `:repo` is the repository path, default `.`.
- `:test-paths` is where tests are stored, default `["test"]`.

Tests are run with Kaocha, and Deltatest respects the project's `tests.edn`.

## Development

Run the tests with

```
bb test
```

[clj-reload]: https://github.com/tonsky/clj-reload
[Kaocha]: https://github.com/lambdaisland/kaocha
[nREPL]: https://nrepl.org
