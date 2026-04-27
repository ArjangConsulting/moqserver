# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Tech Stack

- **Language**: Swift 5.10+
- **Framework**: Vapor 4.121.x (async/await only — prepared for Vapor 5 migration)
- **OpenAPI Parsing**: OpenAPIKit 5.0.0 (3.0.x + 3.1.x via `OpenAPIKitCompat`)
- **CLI**: ArgumentParser (decoupled from Vapor lifecycle)
- **Runtime**: macOS 12+, Linux

## Build & Test

```bash
# From repo root
make build && make test

# Or directly from server/
swift build
swift test
swift test --filter MoqRuntimeTests   # single test target
swift test --filter "testAdminAPI"    # single test case
swift run Run serve --spec ../samples/server/openapi.yaml --port 8080
swift run Run validate-spec --spec ../samples/server/openapi.yaml
swift run Run validate path/to/project.moqproj
```

## Module Structure

The server is split into focused Swift package targets:

| Target | Responsibility |
|--------|---------------|
| `MoqCore` | Framework-agnostic domain types, protocols, validation |
| `MoqParsing` | OpenAPI 3.0/3.1 + HAR parsing, spec validation |
| `MoqFormat` | `.moqproj` file loading, writing, validation, runtime conversion |
| `MoqRuntime` | Vapor app, routing, mock storage, admin API, auth |
| `MoqCLI` | ArgumentParser subcommands wiring everything together |
| `Run` | `@main` entry point only |

Dependency direction: `Run → MoqCLI → MoqRuntime → MoqFormat → MoqParsing → MoqCore`

## Architecture

### Request Flow

```
Incoming request
  → MockRouter (catch-all ** routes for all HTTP methods)
  → MockHandler
      → InMemoryMockStore.lookup(method, path)   // actor, regex path-param matching
      → Variant selection (priority order below)
  → HTTP response
```

**Variant selection priority**: `X-Mock-Variant` header → `RequestMatch` constraints → `Accept` header content negotiation → first variant (default)

**Path parameter matching**: Template paths like `/users/{id}` are compiled to regex `/users/[^/]+`. Exact matches are tried before regex.

### Key Design Decisions

- **`actor InMemoryMockStore`** — thread safety via Swift concurrency; no locks
- **Raw `Data` for response bodies** — no decode/re-encode overhead
- **`SchemaParser` protocol** — extensible for AsyncAPI/Postman/etc.
- **ArgumentParser CLI** — not tied to Vapor's command system, so the lifecycle is controlled independently

## Test Targets

| Target | Coverage |
|--------|---------|
| `MoqCoreTests` | AuthValidator, RequestValidator, EndpointConverter |
| `MoqParsingTests` | OpenAPIParser (3.0+3.1), HARParser, SpecValidator |
| `MoqFormatTests` | ProjectLoader, ProjectValidator, ProjectWriter |
| `MoqRuntimeTests` | Admin API, auth integration, content negotiation |
| `MoqIntegrationTests` | End-to-end: spec → serve → request → verify |

## Code Style

### Imports
- **Always sort imports** alphabetically when modifying a file.
- **Remove unused imports** — do not leave stale imports after refactors.
- Standard Swift grouping order: `Foundation`/system frameworks → third-party packages → internal targets.
- Each group separated by a blank line; no blank lines within a group.

## Test Expectations

- New server code should usually ship with tests in the same change.
- Add focused unit tests for domain, parsing, format, and validation logic.
- Add integration or runtime tests when behavior crosses module boundaries, affects routing, auth, content negotiation, persistence, or end-to-end serving behavior.
- If a server change reasonably does not need a test, note that explicitly in the change summary.
