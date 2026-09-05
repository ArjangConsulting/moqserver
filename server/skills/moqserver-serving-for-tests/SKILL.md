---
name: moqserver-serving-for-tests
description: Run a .moqproj bundle locally and in CI, point an app or UI test at it, and drive per-test scenarios through the admin API instead of X-Mock-Variant.
---

# Serving a bundle for tests

## Running it

```bash
swift run moqserver serve --project path/to/api.moqproj --port 8080
```

`serve` validates the project before binding the port — it aborts with the diagnostic list on any
validation error, so a broken bundle fails at startup, not on the first request. Add
`--hostname 0.0.0.0` to expose the server to a device or simulator on the same network (moqserver
prints a warning if you do this without configuring admin credentials — take it seriously if the
host is reachable by anything other than your own test runner).

At the default `--log-level info`, every matched request logs one access-log line —
`METHOD path → status (endpoint=... variant=...)` — so "did the app actually call this endpoint,
and which variant did it get?" is answerable from the server's own output without adding anything
to the bundle. An unmatched request logs at `warning`. `--log-level debug` adds per-request
routing and variant-selection detail on top of that.

For CI, `moqserver validate --project path/to/api.moqproj` runs the same structural + semantic
validation `serve` runs at startup, without binding a port — use it as a fast pre-flight check in
a pipeline, separate from actually serving traffic.

## Driving scenarios from a test

Use the admin API (`docs/ADMIN_API.md`), **not** the `X-Mock-Variant` header, to select a variant
or reset call-count state for one test. The header only works when the code sending the request
controls its own headers — but in a UI test, the *app under test* sends the request, not the test
code, so the header is not something the test can set. The admin API lets the test process reach
in from the outside:

- Select the active variant for an endpoint (`PUT`/`POST` on `/_admin/...`).
- Reset a variant override, or reset call-count state, back to the bundle's defaults between
  tests.
- List currently-registered endpoints to confirm what the running server actually loaded.

Admin paths tolerate a trailing slash either way — match whatever your app's real paths look like.
For an iOS/macOS app, add `server/MoqTestSupport` (see its README) as a local Swift Package
dependency of your test target instead of hand-rolling these HTTP calls — `MoqControl.selectVariant`
/ `resetVariant` / `resetCallCount` / `resetAll` cover the admin API with error messages that name
the likely cause (wrong port, server not started, no such endpoint) rather than raw HTTP failures.
See `moqserver-scenario-design` for when to reach for this versus `call_count`.

## Scripted authoring in CI

`moqserver` itself only `serve`s and `validate`s — it has no authoring subcommands, by design (see
`server/AGENTS.md`'s note on `MoqCLI`'s dependency graph). For a script or CI step that needs to
create or edit a bundle without an MCP client, use the separate `moq-author` binary instead: each
subcommand (`project create`, `endpoint upsert`, `variant upsert`, `import har`, ...) is one atomic
open-mutate-save-exit operation, taking structured arguments as a JSON file (or stdin), and
printing `{"code", "message"}` on stderr with a non-zero exit on failure. See `server/AGENTS.md`'s
"Scripted / CI Authoring" section. Prefer a bundle that's already committed and validated for most
CI runs — reach for `moq-author` when a pipeline genuinely needs to generate or adjust one.

## See also

- `moqserver-authoring` — building the bundle you're about to serve.
- `moqserver-scenario-design` — `call_count` vs runtime variant selection, in more depth.
