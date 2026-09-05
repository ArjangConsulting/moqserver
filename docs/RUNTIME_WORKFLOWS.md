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

`GET /_admin/scenarios` exports definitions as JSON. Definitions are in memory, limited to 100 per
store, and disappear on server restart. Check exported definitions into your test repository and
load them at test setup. `DELETE /_admin/state` clears runtime overrides and counters; configuration
overrides and request matching still apply. It does not delete scenario definitions or history.

## Parallel tests

1. `POST /_admin/sessions` returns `{"id":"..."}` and snapshots the registered endpoints/scenarios.
2. Send `X-Mock-Session: <id>` on **both** the app's mock requests and its admin requests.
3. Activate scenarios and inspect history within that session.
4. `DELETE /_admin/sessions/<id>` releases it in teardown.

There are at most 64 live sessions. Overrides and counters in one session cannot affect another.
A request with no session header uses global state; an unknown session returns 404 instead of
silently falling back. Session IDs select test state, not an authentication boundary. Admin
credentials remain required when configured. If the app cannot attach a session header, use a
separate server process/port for each parallel suite.

For Apple tests, `MoqClient.createSession()` returns a configured client with `sessionID`; pass
that ID to the app under test through its test configuration. Use `closeSession()` in teardown.
The static `MoqControl` API remains available for existing serial suites.

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
