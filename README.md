# moqserver

`moqserver` is a lightweight mock API platform written in Swift (Vapor 4) with an AI-first authoring direction.
Today it can read OpenAPI 3.x specs and HAR captures to serve mock endpoints immediately, including auth checks,
request validation, multiple response variants, and runtime variant switching. The planned direction expands this into an AI-assisted desktop authoring workflow for generating better mocks, edge cases, and project structure.

## Highlights

- OpenAPI 3.0.x and 3.1.x support (YAML or JSON; local file or URL)
- HAR 1.2 import support for recorded HTTP traffic
- AI-first product direction for mock generation, API analysis, and project refinement
- Auto-registers endpoints from the spec (including templated paths like `/pets/{petId}`)
- Multiple response variants per endpoint (`default`, `error-500`, etc.)
- Variant selection by header, admin override, config override, then default
- Request validation for required query/header/body and supported content types
- Auth simulation: bearer, basic, API key, OAuth2, OpenID Connect
- Mock OAuth endpoints at `/_auth/token` and `/_auth/authorize`
- Admin API for listing endpoints and setting active variants at runtime
- Optional persistence for runtime variant overrides
- Optional mock file overlays from a `mocks/` directory

## AI Direction

The longer-term product shape is not just “serve an OpenAPI spec.” It is:

- import OpenAPI or HAR
- analyze API coverage and gaps
- generate realistic success and error variants
- refine mocks into a clean project structure
- export a deterministic project that runs locally without AI at runtime

AI is intended to be a first-class authoring capability, not part of the request-serving path.

The preferred authoring surface is now a desktop Studio app rather than a browser-only UI.

### Planned provider support

The AI layer is being designed around multiple provider modes:

- `ollama` for local models
- direct `openai` via user-owned API key
- direct `anthropic` / Claude via user-owned API key
- generic `openai-compatible` endpoints for self-hosted or gateway-based providers
- future enterprise adapters such as Azure OpenAI and Claude on Vertex AI

In Studio, provider settings are configured directly by the user and used for bounded authoring tasks such as generating mock bodies, variants, and error cases.

## Quick Start

### 1) Build

```bash
make build
```

### 2) Create a minimal OpenAPI spec

```yaml
# openapi.yaml
openapi: "3.0.3"
info:
  title: Demo API
  version: "1.0.0"
paths:
  /pets:
    get:
      responses:
        "200":
          description: OK
          content:
            application/json:
              example:
                - id: 1
                  name: Fido
        "500":
          description: Server error
          content:
            application/json:
              example:
                error: Internal server error
```

### 3) Start the server

From source:

```bash
cd server
swift run Run serve --spec ../openapi.yaml --port 8080
```

Using a built binary:

```bash
./server/.build/debug/Run serve --spec ./openapi.yaml --port 8080
```

Docker:

```bash
docker build -t moqserver ./server
docker run --rm -p 8080:8080 \
  -v "$PWD/openapi.yaml:/app/spec/openapi.yaml:ro" \
  moqserver serve --spec /app/spec/openapi.yaml --hostname 0.0.0.0 --port 8080
```

### 4) Call the mock API

```bash
# default (typically success)
curl http://127.0.0.1:8080/pets

# force a named variant
curl -H "X-Mock-Variant: error-500" http://127.0.0.1:8080/pets
```

## Command Reference

```bash
cd server
swift run Run --help
swift run Run serve --help
swift run Run init --help
```

`serve` options:

- `--spec <path-or-url>` (required)
- `--format <auto|openapi|har>` (default: `auto`)
- `--port <port>` (default: `8080`)
- `--hostname <host>` (default: `127.0.0.1`)
- `--config <path>` optional YAML/JSON config
- `--mocks <dir>` optional mock-file overlay directory

`init` options:

- `--spec <path-or-url>` (required)
- `--format <auto|openapi|har>` (default: `auto`)
- `--output <dir>` (default: `./mocks`)

## Common Examples

### Start with config and mock overlays

```bash
cd server
swift run Run serve \
  --spec ../openapi.yaml \
  --config ../config/config.yaml \
  --mocks ../mocks \
  --hostname 0.0.0.0 \
  --port 8080
```

### Scaffold mock files from a spec

```bash
cd server
swift run Run init --spec ../openapi.yaml --output ../mocks
```

### Import a HAR capture into moqserver mocks

```bash
cd server
swift run Run init --spec ../session.har --format har --output ../mocks
```

This generates mock files plus `.meta.json` request-match metadata so different recorded requests to the same path can become separate variants.

### Change active variant via Admin API

```bash
# list endpoints
curl http://127.0.0.1:8080/_admin/endpoints | jq

# set active variant for GET /pets
curl -X PUT \
  http://127.0.0.1:8080/_admin/endpoints/GET/pets/variant \
  -H "Content-Type: application/json" \
  -d '{"variant":"error-500"}'

# reset to default behavior
curl -X DELETE http://127.0.0.1:8080/_admin/endpoints/GET/pets/variant
```

## Full Documentation

For complete API and configuration docs with detailed examples:

- [`docs/API_GUIDE.md`](docs/API_GUIDE.md)
- [`OVERVIEW.md`](OVERVIEW.md)
- [`ARCHITECTURE.md`](ARCHITECTURE.md)
- [`docs/PROJECT_SPEC.md`](docs/PROJECT_SPEC.md)
- [`samples/README.md`](samples/README.md) (iOS + Android showcase apps)

## Studio Development

The desktop Studio app is a separate Gradle project under [`studio/`](studio/). Open that directory directly in IntelliJ for the normal Compose Desktop debug workflow.

For contributor setup, testing, and pull request expectations, see [`CONTRIBUTING.md`](CONTRIBUTING.md).

## License

MIT
