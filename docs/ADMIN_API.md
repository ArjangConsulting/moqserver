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
    "variants": ["success", "empty", "unauthorized", "server-error"],
    "activeVariant": null
  },
  {
    "method": "GET",
    "path": "/api/v1/users/{id}",
    "variants": ["success", "not-found"],
    "activeVariant": "not-found"
  }
]
```

**`EndpointListItem` schema**

| Field | Type | Description |
|-------|------|-------------|
| `method` | `string` | HTTP method in uppercase (`GET`, `POST`, etc.) |
| `path` | `string` | API path as defined in the endpoint file, including `{param}` templates |
| `variants` | `string[]` | All variant names available for this endpoint |
| `activeVariant` | `string \| null` | Currently active runtime override, or `null` if none is set |

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
| `variants` | `VariantDetail[]` | Full detail for each variant |
| `activeVariant` | `string \| null` | Currently active runtime override, or `null` |

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

Clears the runtime variant override for the specified endpoint, returning it to normal variant selection (config override → request matching → first variant).

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

## Test-Support Package

For an iOS/macOS app, `server/MoqTestSupport` wraps the variant-override and call-count endpoints
above as `MoqControl` (select/reset variant, reset call count, reset-all) for use directly from a
UI test target — see its README rather than hand-rolling these HTTP calls per app.

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
