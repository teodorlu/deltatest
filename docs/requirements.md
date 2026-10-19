# Requirements

## The project under test needs Clojure 1.11 and reloadable code

- Clojure 1.11 or later.
  The part of Deltatest that runs in the project's JVM does not assume 1.12.
- Code that [clj-reload] can reload cleanly.
  Every run reloads what changed with clj-reload, in a JVM that stays up
  between runs.

## Deltatest itself needs Clojure 1.12 or Babashka 1.12.194

Deltatest runs on Clojure 1.12 or later, or on Babashka 1.12.194 or later.

[clj-reload]: https://github.com/tonsky/clj-reload
