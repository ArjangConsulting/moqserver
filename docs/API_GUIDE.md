# moqserver API Guide

This guide documents how to run `moqserver`, configure it, and use its runtime APIs with concrete examples.

## 1. Running the Server

From source:

```bash
cd server
swift run Run serve --spec ../openapi.yaml --port 8080
```

With explicit host binding:

```bash
cd server
swift run Run serve --spec ../openapi.yaml --hostname 0.0.0.0 --port 8080
```

With config and mocks overlay:

```bash
cd server
swift run Run serve \
  --spec ../openapi.yaml \
  --config ../config/config.yaml \
  --mocks ../mocks
```

Supported `serve` flags:

- `--spec`: path or URL to OpenAPI spec (required)
- `--format`: input format (`auto`, `openapi`, `har`)
- `--port`: listen port (default `8080`)
- `--hostname`: listen hostname (default `127.0.0.1`)
- `--config`: optional server config file (YAML or JSON)
- `--mocks`: optional mock overlay directory

When `--format har` is used, `moqserver` imports HAR 1.2 traffic entries, groups them by HTTP method and path, and creates variants from the recorded responses. Query parameters and request bodies are preserved as request-match metadata when possible.

## 2. What Endpoints Are Served

`moqserver` serves:

- Dynamic mock endpoints from your OpenAPI `paths`
- `/_auth/token` (POST): mock OAuth token endpoint
- `/_auth/authorize` (GET): mock authorization endpoint
- `/_admin/*`: runtime admin endpoints

Dynamic routes include all HTTP methods:

- `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`, `OPTIONS`, `TRACE`, `CONNECT`

Path templates like `/pets/{petId}` are supported with runtime matching.

## 3. Response Variant Selection

Variant precedence is:

1. `X-Mock-Variant` request header
2. Admin runtime override (`/_admin/.../variant`)
3. Config override (`variantOverrides`)
4. Default endpoint variant

Example:

```bash
curl -H "X-Mock-Variant: error-500" http://127.0.0.1:8080/pets
```

If a requested variant is unavailable, `moqserver` falls back to default or first matching variant when possible.

## 4. OpenAPI Response to Variant Mapping

When parsing responses:

- First/primary success response is typically named `default`
- Client/server errors are named like `error-404`, `error-500`
- Response headers from spec are preserved
- Preferred response body source:
  - explicit `example`
  - generated schema stub
  - fallback body

Content type handling:

- `application/json` and `+json` media types return JSON bodies
- non-JSON media types are also supported

## 5. Request Validation Behavior

For each matched endpoint, `moqserver` validates required fields from OpenAPI:

- Required query parameters
- Required headers
- Required request body
- Accepted `Content-Type` for requests with bodies

Error behavior:

- Missing required query/header/body: `400 Bad Request`
- Unsupported body content type: `415 Unsupported Media Type`

Example `415` case:

```bash
curl -X POST http://127.0.0.1:8080/items \
  -H "Content-Type: text/plain" \
  -d 'hello'
```

## 6. Auth Simulation

Derived from OpenAPI security schemes:

- Bearer (`Authorization: Bearer ...`)
- Basic (`Authorization: Basic ...`)
- API key header (e.g. `X-API-Key`)
- OAuth2 and OpenID Connect with optional scopes
- Composite requirements (`allOf` / `anyOf`)

Auth token/credential validation comes from `config.auth` (if provided). Without configured credentials, presence checks are still enforced per scheme.

### 6.1 Bearer Example

```bash
curl -H "Authorization: Bearer valid-bearer" http://127.0.0.1:8080/secured
```

### 6.2 Basic Example

```bash
BASIC=$(printf "admin:pass" | base64)
curl -H "Authorization: Basic $BASIC" http://127.0.0.1:8080/secured-basic
```

### 6.3 API Key Example

```bash
curl -H "X-API-Key: valid-key" http://127.0.0.1:8080/secured-apikey
```

### 6.4 OAuth2 Scopes Example

```bash
curl -H "Authorization: Bearer valid-oauth-token" http://127.0.0.1:8080/pets/favorites
```

If the token lacks required scopes, response is `403 Forbidden` with `WWW-Authenticate: ... insufficient_scope ...`.

## 7. Mock OAuth Endpoints (`/_auth/*`)

### 7.1 POST `/_auth/token`

Supported grant types:

- `client_credentials`
- `password`
- `authorization_code`
- `refresh_token`

Input formats:

- `application/x-www-form-urlencoded`
- JSON body

### client_credentials (body credentials)

```bash
curl -X POST http://127.0.0.1:8080/_auth/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&client_id=client1&client_secret=secret1"
```

### client_credentials (Basic auth client credentials)

```bash
CLIENT=$(printf "client1:secret1" | base64)
curl -X POST http://127.0.0.1:8080/_auth/token \
  -H "Authorization: Basic $CLIENT" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials"
```

### password grant

```bash
curl -X POST http://127.0.0.1:8080/_auth/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password&username=admin&password=pass&scope=read:pets"
```

### authorization_code grant

```bash
curl -X POST http://127.0.0.1:8080/_auth/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=authorization_code&code=any-code&redirect_uri=http://localhost/callback"
```

### refresh_token grant

```bash
curl -X POST http://127.0.0.1:8080/_auth/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=refresh_token&refresh_token=anything"
```

Successful response shape:

```json
{
  "access_token": "mock-or-configured-token",
  "token_type": "Bearer",
  "expires_in": 3600,
  "refresh_token": "mock-refresh-token-..."
}
```

Error response shape:

```json
{
  "error": "invalid_client",
  "error_description": "Invalid client credentials"
}
```

### 7.2 GET `/_auth/authorize`

Returns a 302 redirect to `redirect_uri` with mock `code` and optional `state`.

```bash
curl -i "http://127.0.0.1:8080/_auth/authorize?redirect_uri=http://example.com/cb&state=xyz"
```

## 8. Admin API (`/_admin/*`)

Use this to inspect endpoints and force active variants at runtime.

If `config.admin` is set, admin routes require bearer and/or API key auth.

### 8.1 GET `/_admin/endpoints`

Returns all endpoints with available variants and active override:

```bash
curl http://127.0.0.1:8080/_admin/endpoints | jq
```

Response example:

```json
[
  {
    "method": "GET",
    "path": "/pets",
    "variants": ["default", "error-500"],
    "activeVariant": "error-500"
  }
]
```

### 8.2 GET `/_admin/endpoints/:method/**`

Endpoint detail:

```bash
curl http://127.0.0.1:8080/_admin/endpoints/GET/pets | jq
```

### 8.3 PUT `/_admin/endpoints/:method/**/variant`

Set active variant:

```bash
curl -X PUT \
  http://127.0.0.1:8080/_admin/endpoints/GET/pets/variant \
  -H "Content-Type: application/json" \
  -d '{"variant":"error-500"}'
```

### 8.4 DELETE `/_admin/endpoints/:method/**/variant`

Reset override:

```bash
curl -X DELETE http://127.0.0.1:8080/_admin/endpoints/GET/pets/variant
```

### 8.5 Admin auth examples

Bearer:

```bash
curl -H "Authorization: Bearer admin-token" http://127.0.0.1:8080/_admin/endpoints
```

API key:

```bash
curl -H "X-Admin-Key: admin-secret" http://127.0.0.1:8080/_admin/endpoints
```

## 9. Config File Reference

`--config` accepts YAML or JSON. Example:

```yaml
variantOverrides:
  "GET /pets": "error-500"
globalDelay: 0.15
delayOverrides:
  "POST /pets": 0.40
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
      - "read:pets"
      - "write:pets"
mocksDirectory: "./mocks"
overridesPersistencePath: "./tmp/variant-overrides.json"
admin:
  bearerToken: "admin-token"
  apiKeyHeader: "X-Admin-Key"
  apiKey: "admin-secret"
```

Field behavior:

- `variantOverrides`: default per-endpoint variant
- `globalDelay`: response delay in seconds for all endpoints
- `delayOverrides`: per-endpoint delay override
- `auth.*`: known credentials/tokens used for validation
- `mocksDirectory`: default overlay directory if `--mocks` is not passed
- `overridesPersistencePath`: JSON file path for runtime admin overrides
- `admin.*`: auth controls for `/_admin/*`

## 10. Mock Files Overlay (`--mocks`)

Mock files let you override or add variants without editing the OpenAPI spec.

Conventions:

```text
mocks/pets/GET.json                   -> GET /pets variant "default"
mocks/pets/GET.error-500.json         -> GET /pets variant "error-500"
mocks/pets/{petId}/GET.json           -> GET /pets/{petId} variant "default"
mocks/pets/GET.error-500.meta.json    -> metadata for that variant
```

If variant names collide, mock-file variants override spec variants with the same name.

Example metadata:

```json
{
  "statusCode": 503,
  "headers": {
    "Retry-After": "5"
  },
  "delay": 0.2,
  "requestMatch": {
    "query": { "mode": "chaos" },
    "headers": { "X-Test-Mode": "enabled" },
    "bodyContains": "simulate"
  }
}
```

`requestMatch` rules are ANDed:

- All specified query params must match
- All specified headers must match
- Body must contain `bodyContains` substring (if provided)

## 11. Bootstrapping a Mocks Directory

Use `init` to scaffold mock files from a spec:

```bash
cd server
swift run Run init --spec ../openapi.yaml --output ../mocks
```

This creates per-endpoint JSON files named by HTTP method and variant.

## 12. Docker Usage

The server image files live under `server/`.

Build/run directly:

```bash
docker build -t moqserver ./server
docker run --rm -p 8080:8080 \
  -v "$PWD/samples/server:/app/sample:ro" \
  moqserver serve --spec /app/sample/openapi.yaml --mocks /app/sample/mocks --config /app/sample/config.yaml --hostname 0.0.0.0 --port 8080
```

Compose:

```bash
cd server
docker compose up --build
```

## 13. Troubleshooting

### SwiftPM executable target name

This package’s executable target is `Run`, while the CLI name shown in help output and packaged binaries is `moqserver`. When running from source, use:

```bash
cd server
swift run Run serve --spec ../openapi.yaml
```

### 404 for expected endpoint

- Confirm spec path loaded correctly
- Confirm request path/method exactly matches OpenAPI path
- For templated paths (`{id}`), verify the request has a concrete segment

### 400/415 validation errors

Inspect required query/header/body/content-type in your OpenAPI operation.

### Variant not switching

- Check variant exists in endpoint detail (`/_admin/endpoints/:method/**`)
- Confirm `X-Mock-Variant` value exactly matches variant name
- Check whether `requestMatch` constraints are filtering that variant
