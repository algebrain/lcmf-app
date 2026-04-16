# lcmf-app

Thin app-level wiring library for [`LCMF`](https://github.com/algebrain/lcmf-docs).

Current scope:

- build module state map
- derive per-module dependency maps
- initialize modules in order
- run startup checks
- build final app map

Current canonical public contract:

- `build-app` returns only the canonical app map:
  `:app/status`, `:app/deps`, `:app/modules`, `:app/startup`,
  `:app/shutdown!`
- startup failures use stable `:reason` keys for app assembly failures
- contract-level shape tests protect the public API

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

## Stability

The current public contract is documented in
`docs/INTERFACE.md`.

Breaking changes include:

- removing or renaming canonical `:app/*` keys
- incompatible changes to startup report shape
- removing or renaming stable startup failure `:reason` keys
- removing `stop-modules!` or `:app/shutdown!`
