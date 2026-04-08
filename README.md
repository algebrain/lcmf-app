# lcmf-app

Thin app-level wiring library for [`LCMF`](https://github.com/algebrain/lcmf-docs).

Current scope:

- build module state map
- derive per-module dependency maps
- initialize modules in order
- run startup checks
- build final app map

The library intentionally stays small.
It is not a framework and does not own module behavior.

## Development

Primary local check flow:

- `bb test.bb`

Direct commands:

- `clojure -M:lint`
- `clojure -M:test`
- `clojure -M:format`

Tests run through `shadow-cljs` on the `cljs/node` runtime.
