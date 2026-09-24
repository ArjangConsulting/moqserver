# Scenarios, isolated tests, and request diagnostics

Open Studio's **Tools → Runtime Inspector** and enter the running server's URL. For a protected
admin API, enter its bearer token. Refresh lists scenarios and the most recent requests; each
request shows the returned status, selected variant, selection reason, and call number. History
holds at most 500 requests per state store. It excludes bodies, query strings and headers; paths
may still contain user identifiers. Clear history when it is no longer needed.

## Named scenarios

A scenario maps REST method/template-path keys to variant names:

```json
{"name":"checkout-failure","overrides":{"GET /cart":"success","POST /checkout":"error"}}
```

Create it with `PUT /_admin/scenarios`, or enter its name and overrides in the inspector.
Activate with `PUT /_admin/scenario` and `{"name":"checkout-failure"}`. Activation validates every
entry before changing anything, replaces all runtime overrides, and resets call counters together.
Requests already in flight keep their captured selection. GraphQL operation-specific scenarios
are not supported by these REST keys; ambiguous entries are rejected.

### Scenarios in the bundle

Prefer declaring scenarios in `project.yml`. They load at startup, before any session exists, so
every session starts with them and there is nothing to load during test setup:

```yaml
scenarios:
  out-of-credits:
    description: Balance is zero, lesson purchase fails
    variants:            # endpoint id -> variant name or reference_name
      get-balance: empty
      post-lesson: payment-required
```

`moqserver validate` rejects unknown endpoint ids (`E_SCENARIO_UNKNOWN_ENDPOINT`), unknown variants
(`E_SCENARIO_UNKNOWN_VARIANT`), GraphQL operations, and scenarios with no selections
(`E_INVALID_SCENARIO`). Activate them with `PUT /_admin/scenario` or `MoqClient.activateScenario`,
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
A request with no session header uses global state; an unknown session returns 404 instead of
silently falling back. Start the server with `serve --require-session` to reject mock requests that
have no session header instead (`428`, code `session_required`). This catches an app code path that
forgot the header. Rejected requests appear in the global `GET /_admin/requests` history with reason
`missing session`. Admin, health, and add-on routes are unaffected.

To assert what the app sent, start the server with `serve --capture-request-bodies <bytes>`. Each
history row then carries `requestBody`: `{value, encoding: "utf8"|"base64", size, truncated}`, cut to
at most that many bytes. It's off by default, because bodies can contain credentials or personal
data. From Swift, use `MoqRequestRecord.requestBody?.jsonObject`. Session IDs select test state, not an authentication boundary. Admin
credentials remain required when configured. If the app cannot attach a session header, use a
separate server process/port for each parallel suite.

For Apple tests, `MoqClient.createSession()` returns a configured client with `sessionID`; pass
that ID to the app under test through its test configuration. Use `closeSession()` in teardown.
Call `assertNoUnmatchedRequests()` before closing, so a request the bundle doesn't mock fails the test
instead of the app silently tolerating a 404. `requests()` returns the session's full history.
The static `MoqControl` API remains available for existing serial suites. Android and JVM tests use
the equivalent Kotlin client in [`clients/kotlin`](../clients/kotlin/README.md).

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
