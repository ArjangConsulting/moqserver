# Admin API Reference

The `moqserver` admin API is available under `/_admin/*` at runtime. Use it to inspect loaded endpoints, examine variant details, and switch the active variant for any endpoint without restarting the server.

## Authentication

Admin routes are **open by default**. To require authentication, set the `admin` block in your config file:

```yaml
admin:
  bearerToken: "admin-secret"      # optional — requires Authorization: Bearer <token>
  apiKeyHeader: "X-Admin-Key"      # optional — custom header name (defaults to X-Admin-Key)
  apiKey: "admin-key-value"        # optional — requires <apiKeyHeader>: <apiKey>
```

Either or both of `bearerToken` and `apiKey` may be configured. A request is authenticated if it satisfies **any** configured mechanism. If neither is configured, all admin requests are allowed without credentials.

When auth is required and fails, the server responds:

```
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Bearer realm="mock-server-admin"
```

```json
{
  "error": "Unauthorized",
  "reason": "Admin authorization required"
}
```

---

## Endpoints

### GET `/_admin/endpoints`

Returns all loaded mock endpoints, sorted by method + path.

**Request**

```bash
curl http://127.0.0.1:8080/_admin/endpoints
```

With auth:

```bash
# Bearer token
curl -H "Authorization: Bearer admin-secret" http://127.0.0.1:8080/_admin/endpoints

# API key
curl -H "X-Admin-Key: admin-key-value" http://127.0.0.1:8080/_admin/endpoints
```

**Response** — `200 OK` — array of `EndpointListItem`

```json
[
  {
    "method": "GET",
    "path": "/api/v1/users",
    "key": "GET /api/v1/users",
    "variants": ["success", "empty", "unauthorized", "server-error"],
    "activeVariant": null,
    "currentCallCount": 2
  },
  {
    "method": "POST",
    "path": "/graphql",
    "key": "POST /graphql#GetUser",
    "operation": "GetUser",
    "variants": ["success", "not-found"],
    "activeVariant": "not-found",
    "currentCallCount": 0
  }
]
```

**`EndpointListItem` schema**

| Field | Type | Description |
|-------|------|-------------|
| `method` | `string` | HTTP method in uppercase (`GET`, `POST`, etc.) |
| `path` | `string` | API path as defined in the endpoint file, including `{param}` templates |
| `key` | `string` | The key overrides, call counts, scenarios and config `variantOverrides`/`delayOverrides` use |
| `operation` | `string \| null` | GraphQL operation selector (its name, or `query:<hash>` for an anonymous one); `null` for REST |
| `variants` | `string[]` | All variant names available for this endpoint |
| `activeVariant` | `string \| null` | Currently active runtime override, or `null` if none is set |
| `currentCallCount` | `integer` | Calls since the last reset |

GraphQL operations that share a route are addressed by every per-endpoint route below with an
`operation` query parameter, for example
`PUT /_admin/endpoints/POST/graphql/variant?operation=GetUser`. Without it, a GraphQL route answers
`404` and the hint lists the selectors.

---

### GET `/_admin/endpoints/:method/**`

Returns full detail for a single endpoint including per-variant status codes, delays, and auth requirement.

`:method` is the HTTP method (case-insensitive). The remainder of the path is the API path.

**Request**

```bash
# GET /api/v1/users
curl http://127.0.0.1:8080/_admin/endpoints/GET/api/v1/users

# GET /api/v1/users/{id}
curl http://127.0.0.1:8080/_admin/endpoints/GET/api/v1/users/%7Bid%7D

# POST /api/v1/items
curl http://127.0.0.1:8080/_admin/endpoints/POST/api/v1/items
```

**Response** — `200 OK` — `EndpointDetail`

```json
{
  "method": "GET",
  "path": "/api/v1/users",
  "authRequirement": "bearer",
  "variants": [
    {
      "name": "success",
      "statusCode": 200,
      "hasBody": true,
      "delay": 0.05
    },
    {
      "name": "empty",
      "statusCode": 200,
      "hasBody": true,
      "delay": null
    },
    {
      "name": "unauthorized",
      "statusCode": 401,
      "hasBody": true,
      "delay": null
    },
    {
      "name": "server-error",
      "statusCode": 500,
      "hasBody": true,
      "delay": null
    }
  ],
  "activeVariant": null
}
```

**`EndpointDetail` schema**

| Field | Type | Description |
|-------|------|-------------|
| `method` | `string` | HTTP method in uppercase |
| `path` | `string` | API path including `{param}` templates |
| `authRequirement` | `string` | Auth requirement string (see below) |
| `key` | `string` | Runtime state key (see `EndpointListItem.key`) |
| `operation` | `string \| null` | GraphQL operation selector, or `null` for REST |
| `variants` | `VariantDetail[]` | Full detail for each variant |
| `activeVariant` | `string \| null` | Currently active runtime override, or `null` |
| `currentCallCount` | `integer` | Calls since the last reset |
| `strictCallCount` | `boolean` | Whether an uncovered call number is rejected with `409` |

**`authRequirement` values**

| Value | Meaning |
|-------|---------|
| `"none"` | No auth required |
| `"bearer"` | Requires `Authorization: Bearer <token>` |
| `"basic"` | Requires `Authorization: Basic <base64>` |
| `"apiKey(X-API-Key)"` | Requires named API key header |
| `"oauth2"` | Requires OAuth2 bearer token, no specific scopes |
| `"oauth2(read:pets, write:pets)"` | Requires OAuth2 bearer with listed scopes |
| `"openIdConnect"` | Requires OpenID Connect bearer token |
| `"allOf(bearer, apiKey(X-Sig))"` | All listed requirements must be satisfied |
| `"anyOf(bearer, apiKey(X-Sig))"` | Any one listed requirement must be satisfied |

**`VariantDetail` schema**

| Field | Type | Description |
|-------|------|-------------|
| `name` | `string` | Variant name (e.g. `success`, `error-500`) |
| `statusCode` | `integer` | HTTP status code this variant returns |
| `hasBody` | `boolean` | Whether this variant has a response body |
| `delay` | `number \| null` | Response delay in seconds, or `null` for no delay |

**Error responses**

| Status | Condition |
|--------|-----------|
| `401 Unauthorized` | Admin auth required and credentials missing or invalid |
| `404 Not Found` | No endpoint matches the given method + path |

`404` response body:

```json
{
  "error": "Not Found",
  "reason": "Endpoint not found: GET /api/v1/unknown. Use GET /_admin/endpoints to list all available endpoints."
}
```

---

### PUT `/_admin/endpoints/:method/**/variant`

Sets a runtime variant override for the specified endpoint. Future requests to that endpoint will use this variant (highest priority after `X-Mock-Variant` header).

**Request**

```bash
curl -X PUT \
  http://127.0.0.1:8080/_admin/endpoints/GET/api/v1/users/variant \
  -H "Content-Type: application/json" \
  -d '{"variant": "server-error"}'
```

**Request body** — `SetVariantRequest`

| Field | Type | Description |
|-------|------|-------------|
| `variant` | `string` | Name of the variant to activate |
| `times` | `integer`, optional | Apply the override to only the next `times` requests that reach variant selection, then clear it so normal selection resumes. Requests rejected first (auth, request rules) don't use it up. Must be 1 or more. Omit to keep the override until reset |

`{"variant": "unauthorized", "times": 1}` is how a test makes the next call fail once, for example
to check that the app refreshes its token and retries. Sent with `X-Mock-Session`, it affects only
that session. Limited overrides are never persisted.

**Response** — `200 OK` — `MessageResponse`

```json
{
  "message": "Active variant set to 'server-error' for GET /api/v1/users"
}
```

**Error responses**

| Status | Condition |
|--------|-----------|
| `400 Bad Request` | Variant name not found on the endpoint |
| `401 Unauthorized` | Admin auth required and credentials missing or invalid |
| `404 Not Found` | No endpoint matches the given method + path |

`400` response body:

```json
{
  "error": "Bad Request",
  "reason": "Variant 'does-not-exist' not found for GET /api/v1/users"
}
```

---

### DELETE `/_admin/endpoints/:method/**/variant`

Clears the runtime variant override for the specified endpoint, returning it to normal variant selection (config override → request matching → Accept negotiation → declared default → declaration order).

**Request**

```bash
curl -X DELETE http://127.0.0.1:8080/_admin/endpoints/GET/api/v1/users/variant
```

**Response** — `200 OK` — `MessageResponse`

```json
{
  "message": "Variant reset to default for GET /api/v1/users"
}
```

**Error responses**

| Status | Condition |
|--------|-----------|
| `401 Unauthorized` | Admin auth required and credentials missing or invalid |
| `404 Not Found` | No endpoint matches the given method + path |

---

### DELETE `/_admin/endpoints/:method/**/call-count`

Resets the endpoint's call counter to zero, so `call_count`-scoped variants become eligible from
call 1 again. Independent of the variant override above — resetting one does not reset the other.

**Request**

```bash
curl -X DELETE http://127.0.0.1:8080/_admin/endpoints/GET/api/v1/users/call-count
```

**Response** — `200 OK` — `MessageResponse`

```json
{
  "message": "Call count reset for GET /api/v1/users"
}
```

**Error responses**

| Status | Condition |
|--------|-----------|
| `401 Unauthorized` | Admin auth required and credentials missing or invalid |
| `404 Not Found` | No endpoint matches the given method + path |

---

### Runtime routes

| Route | Purpose |
|-------|---------|
| `GET /_admin/requests`, `DELETE /_admin/requests` | Request history (newest first, at most 500) |
| `GET /_admin/scenarios`, `PUT /_admin/scenarios`, `DELETE /_admin/scenarios` | List, define (`{"name","overrides"}`) or remove (`{"name"}`) scenarios |
| `PUT /_admin/scenario` | Activate a scenario: `{"name": "..."}` |
| `DELETE /_admin/state` | Clear runtime overrides and call counts |
| `POST /_admin/sessions` | Create an isolated session; returns `{"id": "..."}` |
| `GET /_admin/sessions` | Live sessions: `[{"id","createdAt","lastUsedAt","expiresAt"}]` (Unix seconds; `expiresAt` is `null` when expiry is off) |
| `DELETE /_admin/sessions/:id` | Release a session |
| `POST /_admin/reload` | Reload the bundle from disk; returns `{"endpoints","scenarios","sessions","warnings"}`, `422` if the bundle is invalid |

Sessions expire after `serve --session-idle-timeout` seconds (default 1800) without a request or
admin call that names them. See [RUNTIME_WORKFLOWS.md](RUNTIME_WORKFLOWS.md) for scenarios,
sessions and reload semantics.

---

## Test-Support Package

For an iOS/macOS app, `server/MoqTestSupport` wraps these routes as `MoqClient` (sessions, variants,
call counts, scenarios, request history, reload; blocking or `asyncClient`) and the older static
`MoqControl`. Android and JVM tests use the Kotlin `MoqClient` in `clients/kotlin` (blocking or
`suspending`). See their READMEs rather than hand-rolling these HTTP calls per app.

---

## Variant Override Persistence

By default, runtime overrides set via `PUT /_admin/.../variant` are held in memory and lost on restart.

To persist overrides across restarts, set `overridesPersistencePath` in your config file:

```yaml
overridesPersistencePath: "./tmp/variant-overrides.json"
```

The file is created automatically on first write and loaded at startup.

---

## Integration and CI Usage

### Wait until server is ready

Poll `/_admin/endpoints` before running tests:

```bash
until curl -sf http://127.0.0.1:8080/_admin/endpoints > /dev/null; do
  echo "Waiting for moqserver..."
  sleep 1
done
echo "Server ready."
```

### Switch variants in test setup/teardown

```bash
# Force error scenario
curl -sf -X PUT \
  http://127.0.0.1:8080/_admin/endpoints/GET/api/v1/users/variant \
  -H "Content-Type: application/json" \
  -d '{"variant": "server-error"}'

# Run your test...

# Reset to default after test
curl -sf -X DELETE \
  http://127.0.0.1:8080/_admin/endpoints/GET/api/v1/users/variant
```

### List all available variants for an endpoint

```bash
curl -s http://127.0.0.1:8080/_admin/endpoints/GET/api/v1/users | \
  jq '[.variants[].name]'
```

### Verify no overrides are active

```bash
curl -s http://127.0.0.1:8080/_admin/endpoints | \
  jq '[.[] | select(.activeVariant != null)]'
# Should return [] when no overrides are set
```

## Scenarios, sessions and request history

All routes below require the same admin credentials as endpoint management. Send `X-Mock-Session`
for session-local operations; omit it for global state.

| Method/path | Behavior |
|---|---|
| `GET /_admin/scenarios` | List/export named scenario definitions |
| `PUT /_admin/scenarios` | Define `{name, overrides: {"METHOD /template": "variant"}}` |
| `DELETE /_admin/scenarios` | Remove the definition named by `{name}`; active overrides remain until reset |
| `PUT /_admin/scenario` | Activate `{name}`; atomically replace overrides and reset counters |
| `DELETE /_admin/state` | Reset all overrides and counters |
| `DELETE /_admin/endpoints/:method/**/state` | Reset one endpoint's override and counter together |
| `GET /_admin/requests` | Newest-first history, at most 500 records |
| `POST /_admin/requests/query` | Evaluate a request query (below) and return the verdict |
| `POST /_admin/requests/mark` | Returns `{timestamp}`, the server's current Unix time, for a later `since` |
| `DELETE /_admin/requests` | Clear history |
| `POST /_admin/sessions` | Create isolated state; returns `{id}` |
| `DELETE /_admin/sessions/:id` | Release session (idempotent) |

History records have `id`, `timestamp` (Unix seconds), `method`, `path`, `endpoint`, `status`,
optional `variant`, `reason`, optional `callNumber`, optional `requestBody` (with
`serve --capture-request-bodies`: `{value, encoding, size, truncated}`), optional `requestHeaders` (with
`serve --capture-request-headers`: `[{name, value, truncated, redacted, scheme, fingerprint, cookieNames}]`), optional
`rejection` (`{kind, code, message}` when the server refused the request itself; `kind` is `auth`, `request_rule`,
`session`, `call_count` or `no_variant`), and optional `addons` (add-on annotations
keyed by add-on id, e.g. `{"jwt-claims": {"sub": "user-1"}}` — see [ADDONS.md](ADDONS.md)). See [runtime workflows](RUNTIME_WORKFLOWS.md)
for limits, lifecycle, scenario examples and parallel-test setup.

### Request queries

`POST /_admin/requests/query` answers a question about request history on the server, so the Swift
and Kotlin test clients and any script evaluate the same predicates the same way. Send
`X-Mock-Session` to query that session's history. Every field is optional.

| Field | Type | Meaning |
|---|---|---|
| `endpoint` | `string` | Matched endpoint key, e.g. `GET /v1/users/{id}` (method compares case-insensitively) |
| `method`, `path` | `string` | Request method and concrete path, e.g. `/v1/users/42` |
| `since` | `number` | Only rows at or after this Unix time (from `POST /_admin/requests/mark`) |
| `rejected` | `boolean` | `true`: only rows with a `rejection`; `false`: only served rows |
| `headers` | `HeaderPredicate[]` | See below; needs `serve --capture-request-headers` (`409` otherwise) |
| `claims` | `{string: string}` | Each must equal the row's `addons["jwt-claims"]` value |
| `times` | `integer` | Exact number of matches expected; omitted means at least one |
| `scope` | `"session"` \| `"sessionless"` | `sessionless` (needs `X-Mock-Session`): global rows recorded since that session was created, i.e. requests that reached the server without a valid session while the test ran |
| `includeUnattributed` | `boolean` | With `sessionless` and `claims`: also count session-less rows that carry no token claims |

A `HeaderPredicate` is `{name, present?, scheme?, fingerprint?, fingerprintOf?, equals?}`. Every
field that is set must hold for one occurrence of the header; `present: false` alone means the
header was not sent. `scheme` compares case-insensitively. `fingerprint` is the recorded 16-hex
value; `fingerprintOf` is a credential the server fingerprints for you (the token without
`Bearer `). `equals` is refused (`400`) for credential headers, which store no value.

Rows are first narrowed by `scope`, `since`, `endpoint`, `method` and `path`; candidates that then
fail `rejected`, `headers` or `claims` are reported as near misses. The response is
`{ok, count, expected, matched, nearMisses: [{request, failed}], unattributed, message}`:
`matched` is oldest first (compare a retry's token with the first call's), `nearMisses` holds up to
10 rows with the labels of the predicates they failed, and `message` is a readable verdict to use as
an assertion failure message.

```sh
# GET /v1/me was called exactly once with a Bearer token whose sub is u1
curl -s -X POST http://127.0.0.1:8080/_admin/requests/query -H "X-Mock-Session: $SESSION" \
  -H 'Content-Type: application/json' \
  -d '{"endpoint":"GET /v1/me","times":1,"headers":[{"name":"Authorization","scheme":"Bearer"}],"claims":{"sub":"u1"}}'

# No request was rejected, and none reached the server without this session
curl -s -X POST .../_admin/requests/query -H "X-Mock-Session: $SESSION" -d '{"rejected":true,"times":0}'
curl -s -X POST .../_admin/requests/query -H "X-Mock-Session: $SESSION" -d '{"scope":"sessionless","times":0}'
```
