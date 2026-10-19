# Missed regressions

Deltatest can pass where a fresh test run would fail.

## A file other than `.clj` or `.cljc` changes

Examples: `deps.edn`, a resource, a `.cljs` file.
The change selects no tests.
A changed `deps.edn` does not reach the warm JVM.

## A test reaches changed code without requiring it

For example through `requiring-resolve`, a multimethod defined in a namespace
the test does not require, or a data file the test reads.

## State survives a reload

Examples: `defonce` values, which clj-reload keeps, system properties and
running threads.

## Two runs overlap in one repository

Every run in one repository, from any of its git worktrees, uses the same
Deltatest worktree and JVM.
One run can move the worktree while the other is testing it.
Let one finish before starting the next.

## `forget-green` and `stop-jvm` give a fresh test run

For the first three cases, run `forget-green` and `stop-jvm`.
The next run tests everything in a fresh JVM, as a fresh test run would.
