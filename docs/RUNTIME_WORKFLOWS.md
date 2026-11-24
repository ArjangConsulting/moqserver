# Scenarios, isolated tests, and request diagnostics

Open Studio's **Tools → Runtime Inspector** and enter the running server's URL. For a protected
admin API, enter its bearer token. Refresh shows the server's release and capabilities
(`GET /_admin/version`), lists scenarios, and lists the most recent requests. Each request shows
the returned status, selected variant, selection reason and call number. A request the server
refused shows its rejection (kind, code and message), and one no endpoint serves is marked
unmatched. With `serve --capture-request-headers`, each row also lists the captured headers (only
scheme, fingerprint and cookie names for credentials) and any `jwt-claims` claims. History holds at
most 500 requests per state store. Bodies and headers are recorded only with the capture flags, and
query strings never are; paths may still contain user identifiers. Clear history when it is no
longer needed.

Studio's **Preferences → General → HAR import** sets the same options as `moq-author import har`:
extra headers to redact and whether to infer required-header rules
([AUTHORING_CLI.md](AUTHORING_CLI.md)).

## Named scenarios

A scenario maps endpoint keys to variant names. A REST endpoint's key is its method and template
path; a GraphQL operation sharing a route adds `#<operation>` (its name, or the `query:<hash>`
selector of an anonymous operation). `GET /_admin/endpoints` lists every key:

```json
{"name":"checkout-failure","overrides":{"GET /cart":"success","POST /checkout":"error","POST /graphql#GetUser":"error"}}
```

Create it with `PUT /_admin/scenarios`, or enter its name and overrides in the inspector.
Activate with `PUT /_admin/scenario` and `{"name":"checkout-failure"}`. Activation validates every
entry before changing anything, replaces all runtime overrides, and resets call counters together.
Requests already in flight keep their captured selection. Each GraphQL operation also counts calls
and holds overrides on its own key, so `call_count` and admin overrides on one operation never
affect another; address one through the per-endpoint admin routes with `?operation=GetUser`.

### Scenarios in the bundle

Prefer declaring scenarios in `project.yml`. They load at startup, before any session exists, so
every session starts with them and there is nothing to load during test setup. In Studio, edit
them under File > Project Settings, which also holds the project defaults and project-wide
request rules:

```yaml
scenarios:
  checkout-fails:
    description: Payment is declined, checkout fails
    variants:            # endpoint id -> variant name or reference_name
      get-cart: empty
      post-lesson: payment-required
```

`moqserver validate` rejects unknown endpoint ids (`E_SCENARIO_UNKNOWN_ENDPOINT`), unknown variants
(`E_SCENARIO_UNKNOWN_VARIANT`), and scenarios with no selections (`E_INVALID_SCENARIO`). A
scenario may select a GraphQL operation's variant by that operation's endpoint id. Activate them with `PUT /_admin/scenario` or `MoqClient.activateScenario`,
exactly like scenarios defined at runtime.

`GET /_admin/scenarios` exports definitions as JSON. Definitions added through the admin API are in
memory, limited to 100 per store, and disappear on server restart; bundle scenarios are reloaded on
every start. `DELETE /_admin/state` clears runtime overrides and counters; configuration
overrides and request matching still apply. It does not delete scenario definitions or history.

## Parallel tests

1. `POST /_admin/sessions` returns `{"id":"..."}` and snapshots the registered endpoints/scenarios.
2. Send `X-Mock-Session: <id>` on **both** the app's mock requests and its admin requests.
3. Activate scenarios and inspect history within that session.
4. `DELETE /_admin/sessions/<id>` releases it in teardown.

There are at most 64 live sessions. Overrides and counters in one session cannot affect another.
A session expires after 30 minutes without a request or admin call that names it, so crashed test
runs no longer fill the server until restart; change the idle time with
`serve --session-idle-timeout <seconds>` (`0` keeps sessions until deleted). `GET /_admin/sessions`
lists live sessions with `createdAt`, `lastUsedAt` and `expiresAt` (Unix seconds). When all 64 are
in use, `POST /_admin/sessions` answers `409` (code `conflict`).
A request with no session header uses global state; an unknown session returns 404 instead of
silently falling back, and is recorded in the global history with a `session` rejection. Start the server with `serve --require-session` to reject mock requests that
have no session header instead (`428`, code `session_required`). This catches an app code path that
forgot the header. Rejected requests appear in the global `GET /_admin/requests` history with reason
`missing session`. Admin, health, and add-on routes are unaffected.

### Asserting what the app sent

When the server itself refuses a matched request, the history row carries `rejection`:
`{kind, code, message}`. `kind` is `auth` (the endpoint's auth requirement failed), `request_rule` (a
`request_rules` header, query or cookie rule, the required body, or the content type failed),
`session` (`--require-session` and no session header, code `session_required`; or an
`X-Mock-Session` that doesn't exist, code `unknown_session`, always on), `call_count` (`strict_call_count` and no
variant for this call) or `no_variant`. `message` names what failed, e.g.
`Header 'Authorization' is required`, and never contains a credential or cookie value. A mocked 4xx
variant is a served response, not a rejection. Unmatched paths have `endpoint: null` instead.

Start the server with `serve --capture-request-headers` to record request headers as
`requestHeaders`: `[{name, value, truncated, redacted, scheme, fingerprint, cookieNames}]`. Names are
lowercased. Ordinary headers keep their value, cut to 256 characters. Credential headers never
store a value. A header is a credential when any of these holds:

- its name is `authorization`, `proxy-authorization`, `cookie`, `set-cookie`, `x-api-key`,
  `x-auth-token`, `x-csrf-token`, `x-forwarded-for` or `www-authenticate`;
- its name contains `auth`, `token`, `secret`, `password`, `passwd`, `credential`, `signature`,
  `session`, `cookie`, `key` or `appcheck`, so custom headers such as `X-Firebase-AppCheck` or
  `X-Refresh-Token` are covered without configuration (`x-mock-session`, `x-mock-variant` and
  `access-control-allow-credentials` are exempt);
- its value contains a JWT, signed or not (the Firebase Auth emulator issues unsigned ones);
- you named it with `serve --redact-header <name>` (repeatable), for a custom auth header none of the
  above catches.

What a credential header keeps:

- `authorization` and `proxy-authorization` keep `scheme` (e.g. `Bearer`) and `fingerprint`, the
  first 16 hex characters of the SHA-256 of the credential after the scheme. A test can compute the
  same fingerprint from the token it expects.
- `cookie` keeps only `cookieNames`.
- Other credential headers keep `fingerprint` of the whole value.

The same rules hide credential values in `request_rules` mismatch messages (`got a redacted value`),
with or without header capture.

Decoded token claims come from the [`jwt-claims` add-on](ADDONS.md#jwt-claims) in `addons`, not from
header capture. Its `trace_claims` records string, number and boolean claims as strings; array and
object claims are skipped.

To assert what the app sent in a body, start the server with `serve --capture-request-bodies <bytes>`. Each
history row then carries `requestBody`: `{value, encoding: "utf8"|"base64", size, truncated}`, cut to
at most that many bytes. It's off by default, because bodies can contain credentials or personal
data. From Swift, use `MoqRequestRecord.requestBody?.jsonObject`. Session IDs select test state, not an authentication boundary. Admin
credentials remain required when configured. If the app cannot attach a session header, use a
separate server process/port for each parallel suite.

Both test clients also list endpoints (`endpoints()`), read call counts (`callCount(for:path:)`),
define, list and remove scenarios, list sessions, and reload the bundle. Every call takes an
optional `operation` for GraphQL routes. Async tests use `client.asyncClient` (Swift) or
`client.suspending` (Kotlin) for the same calls without blocking a thread.

For Apple tests, `MoqClient.createSession()` returns a configured client with `sessionID`; pass
that ID to the app under test through its test configuration. Use `closeSession()` in teardown.
Call `assertNoUnmatchedRequests()` before closing, so a request the bundle doesn't mock fails the test
instead of the app silently tolerating a 404. `requests()` returns the session's full history.
The static `MoqControl` API remains available for existing serial suites. Android and JVM tests use
the equivalent Kotlin client in [`clients/kotlin`](../clients/kotlin/README.md).

## Reloading the bundle

`POST /_admin/reload` re-reads, validates and converts the bundle the server was started with, then
swaps it in for the global state and every live session. New and removed endpoints take effect
immediately. Overrides, call counts and the active scenario survive for endpoints and variants that
still exist; anything the new bundle can't keep is dropped and listed in the response's `warnings`.
Bundle scenarios replace the previous definitions in every session. If the bundle no longer
validates, the server answers `422` and keeps serving the previous version.

`serve --watch` does the same whenever a file in the bundle changes (polled once a second).

## Recovery in Studio

- **Project changed on disk:** Save As to keep local edits separately, or Tools → Reload Project.
  Reload asks before discarding dirty edits.
- **Project busy:** another writer holds the bundle lock. Retry after its operation finishes.
- **Format service unavailable:** Tools → Retry format service, then repeat the action. Local edits
  stay in the editor. Automatic session recovery verifies the original disk revision before saving.
- **Incompatible format service:** use the `moq-format` shipped with Studio. Check for an outdated
  `MOQSERVER_FORMAT_BINARY` override before retrying.

A timeout does not prove a server mutation was rolled back. Reconcile runtime state before
continuing; test clients cancel their request and throw an error instead of reporting success.
