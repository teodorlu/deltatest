# Commands

Deltatest is a library.
Its four functions, `test-head`, `test-tree`, `stop-jvm` and `forget-green`,
are meant to be called from Babashka tasks.

## A test run fails unless every selected test passed

`test-head` and `test-tree` fail the task unless every selected test passed.

## `test-tree` includes untracked files

`test-tree` tests tracked files as they are on disk, and untracked files that
are not ignored.

## `stop-jvm` stops the JVM until the next run

The next test run starts another JVM.
`stop-jvm` reads only `:repo` and `:worktree`.

## `forget-green` makes the next run test everything

Use it when a run went green over a change Deltatest does not follow.
See [missed regressions](missed-regressions.md).

`forget-green` leaves a running JVM running.
A value read when a namespace was loaded stays as it was until `stop-jvm`.
`forget-green` reads only `:repo`.

## Test output is Kaocha's

The selected tests print as [Kaocha] would, reporter included, while they run.

[Kaocha]: https://github.com/lambdaisland/kaocha
