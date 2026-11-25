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
| `--require-session` | No | off | Reject mock requests without `X-Mock-Session` (`428`); see [runtime workflows](RUNTIME_WORKFLOWS.md#parallel-tests) |
| `--capture-request-bodies` | No | `0` | Record up to N bytes of each mock request body in request history |
| `--redact-header` | No | — | Treat this request header as a credential in history and validation messages, on top of the built-in list and the bundle's `redact:` ([credential names](RUNTIME_WORKFLOWS.md#credential-names)). Repeatable |
| `--redact-query-parameter` | No | — | Treat this query parameter as a credential. Repeatable |
| `--no-redact-header` | No | — | Keep this built-in credential header visible; `Authorization`, `Proxy-Authorization`, `Cookie` and `Set-Cookie` are refused. Repeatable |
| `--no-redact-query-parameter` | No | — | Keep this built-in credential query parameter visible (e.g. `token` used as a page cursor). Repeatable |
| `--capture-request-headers` | No | off | Record each mock request's headers in request history; credential headers keep only scheme, fingerprint and cookie names ([runtime workflows](RUNTIME_WORKFLOWS.md#asserting-what-the-app-sent)) |
| `--capture-request-query` | No | off | Record each mock request's query parameters in request history; credential parameters keep only a fingerprint ([runtime workflows](RUNTIME_WORKFLOWS.md#asserting-what-the-app-sent)) |
| `--allow-unverified-jwt` | No | off | Let the [`jwt-claims` add-on](ADDONS.md#jwt-claims) run on a non-loopback `--hostname` |
| `--max-body-size` | No | `10485760` | Largest mock request body in bytes; larger bodies get `413` (`payload_too_large`) |
| `--cors` | No | off | Allow every origin and answer CORS preflights, so browser clients can call the server. This also exposes `/_admin` to any page the browser opens; pair it with admin credentials off loopback |
| `--tls-cert`, `--tls-key` | No | — | Serve HTTPS with this PEM certificate chain and private key (both required together), for apps that enforce HTTPS |
| `--session-idle-timeout` | No | `1800` | Seconds an unused runtime session lives; `0` keeps sessions until deleted |
| `--watch` | No | off | Reload the bundle when any of its files change; `POST /_admin/reload` reloads on demand either way |

For local HTTPS, a self-signed certificate is enough when the test device trusts it:

```bash
openssl req -x509 -newkey rsa:2048 -nodes -days 30 -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1,IP:10.0.2.2" -keyout key.pem -out cert.pem
moqserver serve --project my-api.moqproj --tls-cert cert.pem --tls-key key.pem
```

The server validates the project before binding the port. If there are validation errors it prints them and exits without starting. It then starts the add-ons the bundle enables (see [ADDONS.md](ADDONS.md)); an add-on that refuses its config or bind address also stops startup.

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

Path templates like `/users/{id}` match one path segment per parameter. Beyond the declared
methods, every mocked path also answers:

- `HEAD` through its `GET` endpoint (same status and headers, no body)
- `OPTIONS` with `204` and an `Allow` header, unless the bundle declares `OPTIONS` itself
- any other method with `405` (`method_not_allowed`) and an `Allow` header; only paths no endpoint
  serves get `404`

Mock request bodies up to `--max-body-size` (10 MiB by default) are accepted.

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
- Cookie rules (`cookies:`) and cookie presence (`verify_cookies: true`), see [Cookie rules](#cookie-rules)
- `requires_body: true` — the request must have a body
- `accepted_content_types: [application/json, image/*]` — a body must use one of these media types

A rule with `required: false` and a `match` is optional but enforced when present. `not_equal_to`
and `not_contains` pass when the value is absent unless `required: true`. A rule with
`required: true` and no `match` only requires the value to be sent, whatever its `match_type`; one
that is not required and has no `match` checks nothing (`W_RULE_WITHOUT_MATCH_VALUE`). A `matches_regex` pattern
that doesn't compile fails validation (`E_INVALID_REGEX`) instead of rejecting every request.

### Cookie rules

Cookies are read from the request's `Cookie` header (`name=value; other=value`). There are two
independent checks:

- `cookies:` lists rule matchers, the same shape as `headers:` and `query_params:`. Each rule is
  evaluated on every request, whether or not `verify_cookies` is set. Cookie names are
  case-sensitive (header names are not).
- `verify_cookies: true` only demands that the request carries at least one cookie, and only when
  the endpoint has no `cookies:` rules. With rules, the rules decide. A request with no cookies gets
  `400 missing_required_cookie` ("At least one cookie is required").

`global_rules.verify_cookies` in `project.yml` is the default for every endpoint; an endpoint's own
`request_rules.verify_cookies` overrides it, `false` included. There is no global `cookies:` list.

```yaml
request_rules:
  verify_cookies: true
  cookies:
    - name: session_id
      required: true
      match_type: not_empty
    - name: theme
      match: dark
      match_type: equal_to
```

A missing required cookie gets `400 missing_required_cookie`, and a value that fails its rule gets
`400 cookie_value_mismatch`. Cookie values are never echoed: the message says
`Cookie 'theme' must match 'dark', got a redacted value`. To check that the app sends no cookies at
all, use the request-history check `assertRequest(endpoint:headers: [.absent("Cookie")])` (Kotlin:
`MoqHeaderExpectation.Absent("Cookie")`) with `serve --capture-request-headers`.

HAR import writes the cookies a capture sent as presence-only `cookies:` rules (`required: true`,
no `match`), because cookie values are always redacted. Sensitive query parameters (`code`,
`state`, `access_token`, ...) are imported the same way.

OpenAPI import fills `requires_body` and `accepted_content_types` from the `requestBody` of each
`POST`, `PUT` and `PATCH` operation. A `requestBody` on `GET`, `HEAD` or `DELETE` is ignored, as
OpenAPI 3.0 requires. Before re-importing a spec into a bundle apps already use, check that they send
the body and `Content-Type` the spec declares; a body with no `Content-Type` gets `415`. Delete
either key from an endpoint to keep it lenient.

Error behavior:

- Missing required header/query param: `400 Bad Request`
- Missing or mismatched cookie: `400` (`missing_required_cookie`, `cookie_value_mismatch`)
- Missing body with `requires_body: true`: `400` (`missing_request_body`)
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

These endpoints are served by the always-on [`oauth-mock` add-on](ADDONS.md#oauth-mock). They are
also available at `/_addons/oauth-mock/*`. The token endpoint reads form-urlencoded or JSON bodies.

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

Each operation keeps its own runtime state under the key `POST /graphql#<operation>` (the name,
or `query:<hash>` for an anonymous operation; `GET /_admin/endpoints` lists them). Call counts,
admin overrides, scenarios and config overrides on one operation never affect another. Address an
operation through the per-endpoint admin routes with `?operation=<selector>`.

## 11. Config File Reference

`--config` accepts YAML or JSON. All fields are optional.

```yaml
# Per-endpoint variant to use (key format: "METHOD /path", or "POST /graphql#Operation").
# At startup, a key that names no endpoint, or a variant the endpoint lacks, is printed as a warning.
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
