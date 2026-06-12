# Architecture Overview

## Products

`moqserver` is a two-product mono-repo:

- **Server** (`server/`) — Swift/Vapor 4 mock HTTP server that loads and serves `.moqproj` project bundles.
- **Studio** (`studio/`) — Kotlin/Compose Multiplatform desktop app for authoring `.moqproj` projects.

The shared artifact is the `.moqproj` directory bundle format, consumed by both products.

## Server System Flow

```
User runs: moqserver serve --project ./my-api.moqproj
    ↓
ProjectLoader reads project.yml + endpoints/*.yml + fixtures/
    ↓
ProjectValidator checks schema and semantic correctness
    ↓
ProjectToRuntimeConverter maps .moqproj models → domain Endpoint objects
    ↓
InMemoryMockStore registers all endpoints (actor, thread-safe)
    ↓
Vapor HTTP server starts (AuthRouter + AdminRouter + MockRouter)
    ↓
Incoming request
    ↓
Auth enforcement (bearer / basic / API key / OAuth2 / OpenID)
    ↓
Request validation (required query params / headers / body / content-type)
    ↓
InMemoryMockStore.lookup(method, path) — exact match then regex path-param match
    ↓
Variant selection (see priority below)
    ↓
HTTP response (with optional configured delay and network simulation)
```

## Core Concepts

**`.moqproj` Project** — A directory bundle containing:
- `project.yml` — manifest (name, version, defaults, global rules)
- `endpoints/*.yml` — one YAML file per endpoint with all its variants
- `fixtures/` — optional body files referenced by `body_file` in endpoint variants

**Endpoint** — A single API operation (HTTP method + path) with associated metadata:
- HTTP method and path
- Auth requirement
- Request rules (required headers, query params, cookies)
- Network simulation config (latency, jitter, packet loss)
- One or more response variants

**Response Variant** — A specific mock response for an endpoint:
- Name (e.g. `success`, `error-500`)
- HTTP status code
- Response headers
- Body (inline YAML map or `body_file` reference into `fixtures/`)
- Optional delay in milliseconds

**Variant Selection Priority** — How the server picks which variant to return:
1. `X-Mock-Variant` request header (exact variant name)
2. Admin runtime override (`PUT /_admin/endpoints/:method/**/variant`)
3. Config file `variantOverrides` map
4. `requestMatch` constraints (query, header, body substring matching)
5. `Accept` header content negotiation
6. First variant in the endpoint file (effectively the default)

**Admin API** — Runtime management endpoints under `/_admin/*` for listing endpoints, inspecting variant details, and setting/resetting active variant overrides. See [`docs/ADMIN_API.md`](docs/ADMIN_API.md).

## Swift Package Module Structure

The server is split into focused, layered Swift targets:

| Target | Responsibility |
|--------|----------------|
| `MoqCore` | Framework-agnostic domain types, protocols, auth models, request validation |
| `MoqFormat` | `.moqproj` file loading, writing, validation, runtime model conversion |
| `MoqRuntime` | Vapor app, routing, mock storage (`InMemoryMockStore`), admin API, auth enforcement |
| `MoqCLI` | ArgumentParser subcommands (`serve`, `validate`) wiring everything together |
| `Run` | `@main` executable entry point only |

Dependency direction (no cycles):
```
Run → MoqCLI → MoqRuntime → MoqFormat → MoqCore
```

## Studio Architecture

Studio is a Kotlin Multiplatform / Compose Multiplatform desktop application structured as a Gradle multi-project build:

| Module | Responsibility |
|--------|----------------|
| `composeApp` | Desktop entry point, Compose UI, screen navigation, desktop-specific concerns |
| `studio-domain` | Pure Kotlin business logic, ViewModels, state machines — no I/O or Compose dependencies |
| `studio-project-format` | `.moqproj` read/write, YAML serialization, format validation |
| `studio-data` | File system access, preferences, AI provider integration |
| `studio-export` | Code generation / export references feature |

State ownership: `StudioRootViewModel` in `studio-domain` is the single source of truth for all workflow state. UI composables observe it but do not own state.

## Key Design Decisions

- **In-memory, no database** — all endpoints loaded at startup, fast lookup via actor
- **Path parameter matching** — `{id}`-style templates compiled to regex; exact matches tried first
- **Local-only auth** — bearer/basic/API key credentials validated against config, never forwarded
- **Immutable project format** — Studio reads/writes `.moqproj`; the server only reads it
- **Actor-based concurrency** — `InMemoryMockStore` is a Swift actor; no locks needed
- **Raw `Data` bodies** — no decode/re-encode overhead in the serving path
- **Validation-first startup** — `moqserver serve` runs `ProjectValidator` before binding the port; invalid projects abort with a clear error
