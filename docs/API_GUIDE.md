# moqserver API Guide

This guide documents how to run `moqserver`, configure it, and use its runtime APIs with concrete examples.

## 1. Running the Server

From source:

```bash
cd server
swift run moqserver serve --project ../path/to/my-api.moqproj
```

With explicit host and port:

```bash
cd server
swift run moqserver serve --project ../path/to/my-api.moqproj --hostname 0.0.0.0 --port 8080
```

With a runtime config file:

```bash
cd server
swift run moqserver serve \
  --project ../path/to/my-api.moqproj \
  --config ../config/config.yaml \
  --hostname 0.0.0.0 \
  --port 8080
```

Supported `serve` flags:

| Flag | Required | Default | Description |
|------|----------|---------|-------------|
| `--project` | Yes | — | Path to a `.moqproj` directory |
| `--port` | No | `8080` | Port to listen on |
| `--hostname` | No | `127.0.0.1` | Hostname to bind to |
| `--config` | No | — | Path to a YAML or JSON server config file |
| `--log-level` | No | `info` | `trace`, `debug`, `info`, `notice`, `warning`, `error`, or `critical` |

The server validates the project before binding the port. If there are validation errors it prints them and exits without starting.

At the default `info` level, every matched request logs one access-log line — `METHOD path → status (endpoint=... variant=...)` — so "did the app actually call this endpoint, and which variant did it get?" is answerable from the server's own output. An unmatched request logs at `warning`. Pass `--log-level debug` for finer per-request detail (request routing, variant-selection reasoning, applied delay); `--log-level warning` or higher silences the access log.

## 2. Project Format (`.moqproj`)

`moqserver` serves endpoints defined in `.moqproj` directory bundles. A project contains:

```text
my-api.moqproj/
├── project.yml               # manifest: name, version, defaults, global rules
├── endpoints/
│   ├── list-users.yml        # one file per endpoint
│   └── get-user.yml
└── fixtures/
    └── users-list.json       # body files referenced by body_file in endpoint YAMLs
```

Minimal `project.yml`:

```yaml
version: "1"
name: "My API Mock"
description: "Mock for the My API service"
defaults:
  delay_ms: 0
  auth:
    type: none
    verify: false
    header_name: null
  network:
    latency_ms: 0
    jitter_ms: 0
    packet_loss_percent: 0
```

Endpoint file example (`endpoints/list-users.yml`):

```yaml
id: list-users
alias: "List Users"
reference_name: listUsers
method: GET
path: /api/v1/users
tags: [users, core]

auth:
  type: bearer
  verify: true

request_rules:
  headers:
    - name: Accept
      match: "application/json"
      required: true

variants:
  - name: success
    reference_name: success
    default: true
    status: 200
    headers:
      Content-Type: application/json
    body_file: fixtures/users-list.json
    delay_ms: 50

  - name: empty
    status: 200
    body:
      users: []
      total: 0

  - name: server-error
    status: 500
    body:
      error: "Internal server error"

network:
  latency_ms: 100
  jitter_ms: 20
```

See [`docs/FORMAT_IMPLEMENTATION.md`](FORMAT_IMPLEMENTATION.md) for the full format reference.

## 3. Validating a Project

Before serving, validate your project for errors:

```bash
cd server
swift run moqserver validate --project ../path/to/my-api.moqproj
```

Exits `0` if valid. Exits non-zero and prints diagnostics if there are errors or warnings.

## 4. What Endpoints Are Served

`moqserver` serves:

- Mock endpoints from all `endpoints/*.yml` files in your project
- `/_auth/token` (POST) — mock OAuth token endpoint
- `/_auth/authorize` (GET) — mock authorization redirect endpoint
- `/_admin/*` — runtime admin endpoints

Supported HTTP methods for mock endpoints:

`GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`, `OPTIONS`, `TRACE`, `CONNECT`

Path templates like `/users/{id}` are registered as native Vapor path parameters and matched by Vapor's router.

## 5. Response Variant Selection

Variant selection priority (highest to lowest):

1. `X-Mock-Variant` request header — exact variant name
2. Admin runtime override set via `PUT /_admin/.../variant`
3. Config file `variantOverrides` map
4. `request_match` constraints (query params, headers, body substring)
5. `Accept` header content negotiation
6. The marked default, then the first eligible variant

Force a specific variant via header:

```bash
curl -H "X-Mock-Variant: server-error" http://127.0.0.1:8080/api/v1/users
```

## 6. Request Validation

For each matched endpoint, `moqserver` enforces the `request_rules` defined in the endpoint YAML:

- Required headers and optional value matching
- Required query parameters
- Cookie verification (`verify_cookies: true`)

Error behavior:

- Missing required header/query param: `400 Bad Request`
- Unsupported `Content-Type` for requests with bodies: `415 Unsupported Media Type`

## 7. Auth Simulation

Configured per-endpoint in the endpoint YAML via the `auth` block:

```yaml
auth:
  type: bearer       # none | bearer | basic | api-key | header
  verify: true       # if false, presence is not enforced
  header_name: Authorization
```

When `verify: true`, the server checks for the credential in the request. Known credentials are validated against the `auth` section of the server config. Without configured credentials, presence is enforced but any value is accepted.

Auth types:
- `none` — no auth required
- `bearer` — `Authorization: Bearer <token>`
- `basic` — `Authorization: Basic <base64>`
- `api-key` — custom API key header specified in `header_name`
- `header` — custom required header specified in `header_name`

### 7.1 Bearer Example

```bash
curl -H "Authorization: Bearer valid-bearer" http://127.0.0.1:8080/api/v1/users
```

### 7.2 Basic Example

```bash
BASIC=$(printf "admin:pass" | base64)
curl -H "Authorization: Basic $BASIC" http://127.0.0.1:8080/secured
```

### 7.3 API Key Example

```bash
curl -H "X-API-Key: valid-key" http://127.0.0.1:8080/secured
```

### 7.4 OAuth2 Scopes

When scopes are configured and the token lacks required scopes, the response is `403 Forbidden`:

```
WWW-Authenticate: Bearer ... insufficient_scope ...
```

## 8. Mock OAuth Endpoints (`/_auth/*`)

### 8.1 POST `/_auth/token`

Supported grant types:

- `client_credentials`
- `password`
- `authorization_code`
- `refresh_token`

Input formats: `application/x-www-form-urlencoded` or JSON body.

```bash
# client_credentials (body credentials)
curl -X POST http://127.0.0.1:8080/_auth/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&client_id=client1&client_secret=secret1"

# client_credentials (Basic auth client credentials)
CLIENT=$(printf "client1:secret1" | base64)
curl -X POST http://127.0.0.1:8080/_auth/token \
  -H "Authorization: Basic $CLIENT" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials"

# password grant
curl -X POST http://127.0.0.1:8080/_auth/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password&username=admin&password=pass&scope=read:users"

# authorization_code grant
curl -X POST http://127.0.0.1:8080/_auth/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=authorization_code&code=any-code&redirect_uri=http://localhost/callback"

# refresh_token grant
curl -X POST http://127.0.0.1:8080/_auth/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=refresh_token&refresh_token=anything"
```

Successful response:

```json
{
  "access_token": "mock-or-configured-token",
  "token_type": "Bearer",
  "expires_in": 3600,
  "refresh_token": "mock-refresh-token-..."
}
```

Error response:

```json
{
  "error": "invalid_client",
  "error_description": "Invalid client credentials"
}
```

### 8.2 GET `/_auth/authorize`

Returns a `302` redirect to an allowlisted `redirect_uri` with mock `code` and optional `state`. The default allowlist contains `http://localhost/callback`; configure `auth.oauth2RedirectUris` for other clients.

```bash
curl -i "http://127.0.0.1:8080/_auth/authorize?response_type=code&redirect_uri=http://localhost/callback&state=xyz"
```

## 9. Admin API (`/_admin/*`)

See [`docs/ADMIN_API.md`](ADMIN_API.md) for the full admin API reference including all response schemas, error codes, auth configuration, and CI/integration patterns.

Quick reference:

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/_admin/endpoints` | List all loaded endpoints |
| `GET` | `/_admin/endpoints/:method/**` | Endpoint detail with variant info |
| `PUT` | `/_admin/endpoints/:method/**/variant` | Set active variant override |
| `DELETE` | `/_admin/endpoints/:method/**/variant` | Reset variant to default |

```bash
# List all endpoints
curl http://127.0.0.1:8080/_admin/endpoints | jq

# Set active variant for GET /api/v1/users
curl -X PUT \
  http://127.0.0.1:8080/_admin/endpoints/GET/api/v1/users/variant \
  -H "Content-Type: application/json" \
  -d '{"variant": "server-error"}'

# Reset
curl -X DELETE http://127.0.0.1:8080/_admin/endpoints/GET/api/v1/users/variant
```

## 10. GraphQL Endpoint Matching

For GraphQL operations, `moqserver` can route different operations on the same `POST /graphql` path to different endpoints using the request body.

In the endpoint YAML, add an `operation` block:

```yaml
# Match by operationName field in the request body
method: POST
path: /graphql
operation:
  type: query
  name: GetUserProfile

# Match anonymous queries by document content
method: POST
path: /graphql
operation:
  type: query
  document: |
    query {
      currentUser { id name }
    }
```

Matching logic:
- If `operation.name` is set, the `operationName` field in the JSON request body must match.
- If `operation.document` is set, the `query` field in the JSON request body is compared for content equality.
- Endpoints without an `operation` block serve as catch-all for unmatched GraphQL requests.

## 11. Config File Reference

`--config` accepts YAML or JSON. All fields are optional.

```yaml
# Per-endpoint variant to use (key format: "METHOD /path")
variantOverrides:
  "GET /api/v1/users": "server-error"

# Global response delay in seconds for all endpoints
globalDelay: 0.15

# Per-endpoint delay overrides in seconds
delayOverrides:
  "POST /api/v1/users": 0.40

# Known credentials for auth validation
auth:
  bearerTokens:
    - "valid-bearer"
  basicCredentials:
    - username: "admin"
      password: "pass"
  apiKeys:
    X-API-Key: "valid-key"
  oauth2Tokens:
    - "valid-oauth-token"
  oauth2Clients:
    - clientId: "client1"
      clientSecret: "secret1"
  oauth2TokenScopes:
    valid-oauth-token:
      - "read:users"
      - "write:users"
  oauth2RedirectUris:
    - "http://localhost/callback"

# Path for persisting runtime variant overrides across restarts
overridesPersistencePath: "./tmp/variant-overrides.json"

# Admin API auth (if omitted, admin routes are open)
admin:
  bearerToken: "admin-token"
  apiKeyHeader: "X-Admin-Key"
  apiKey: "admin-secret"
```

**Config field reference**

| Field | Type | Description |
|-------|------|-------------|
| `variantOverrides` | `map[string]string` | Default variant per endpoint key (`"METHOD /path"`) |
| `globalDelay` | `number` | Response delay in seconds applied to all endpoints |
| `delayOverrides` | `map[string]number` | Per-endpoint delay overrides in seconds (overrides `globalDelay`) |
| `auth.bearerTokens` | `string[]` | Valid bearer tokens |
| `auth.basicCredentials` | `{username, password}[]` | Valid basic auth credentials |
| `auth.apiKeys` | `map[string]string` | Valid API keys, keyed by header name |
| `auth.oauth2Tokens` | `string[]` | Valid OAuth2 bearer tokens |
| `auth.oauth2Clients` | `{clientId, clientSecret}[]` | Valid OAuth2 client credentials |
| `auth.oauth2TokenScopes` | `map[string]string[]` | Scopes granted per OAuth2 token |
| `auth.oauth2RedirectUris` | `string[]` | Exact redirect URIs accepted by the mock authorization endpoint |
| `overridesPersistencePath` | `string` | File path for persisting admin variant overrides |
| `admin.bearerToken` | `string` | Bearer token required for admin routes |
| `admin.apiKeyHeader` | `string` | API key header name for admin routes (default: `X-Admin-Key`) |
| `admin.apiKey` | `string` | API key value required for admin routes |

## 12. Docker Usage

Build and run the server image directly:

```bash
docker build -t moqserver ./server

docker run --rm -p 8080:8080 \
  -v "$PWD/my-api.moqproj:/app/project.moqproj:ro" \
  moqserver serve --project /app/project.moqproj --hostname 0.0.0.0 --port 8080
```

With a config file:

```bash
docker run --rm -p 8080:8080 \
  -v "$PWD/my-api.moqproj:/app/project.moqproj:ro" \
  -v "$PWD/config.yaml:/app/config.yaml:ro" \
  moqserver serve \
    --project /app/project.moqproj \
    --config /app/config.yaml \
    --hostname 0.0.0.0 \
    --port 8080
```

Docker Compose (from `server/` directory):

```bash
cd server
docker compose up --build
```

## 13. Troubleshooting

### SwiftPM executable product name

The package's public executable product and packaged release binary are both named `moqserver`:

```bash
cd server
swift run moqserver serve --project ../my-api.moqproj
```

### Server exits immediately at startup

Project validation failed. Read the diagnostic output — it lists specific fields or endpoints with errors. Fix them and re-run. Use `swift run moqserver validate --project ...` to validate without starting the server.

### 404 for an expected endpoint

- Confirm the endpoint file exists in `endpoints/` and has a valid `method` and `path`.
- For templated paths (`{id}`), ensure the request segment does not contain `/`.
- Use `GET /_admin/endpoints` to confirm the endpoint was loaded.

### 400 or 415 validation errors

Inspect `request_rules` in the endpoint YAML. Ensure required headers and query params are present in your request, and that the `Content-Type` matches what the endpoint expects.

### Variant not switching

- Use `GET /_admin/endpoints/:method/**` to confirm the variant name exists.
- Confirm `X-Mock-Variant` header value exactly matches the variant name (case-sensitive).
- Check whether `request_match` constraints on the variant are filtering it out.
