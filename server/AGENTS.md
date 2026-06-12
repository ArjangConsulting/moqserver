# AGENTS.md

Canonical guide for AI agents working in `server/` (the Swift/Vapor mock server).
This is the single source of truth for server architecture, commands, and conventions.
`server/CLAUDE.md` is a stub that points here.

For cross-repo workflow and shared expectations, see the root `AGENTS.md`.

## Tech Stack

- **Language**: Swift 5.10+
- **Framework**: Vapor 4.121.x (async/await only — prepared for Vapor 5 migration)
- **Project Format**: `.moqproj` directory bundles (YAML manifest + endpoint files, parsed with Yams)
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
swift test --filter "testAdminAPI"     # single test case
swift run Run serve --project Tests/MoqFormatTests/Fixtures/sample-app.moqproj --port 8080
swift run Run validate --project path/to/project.moqproj
```

Makefile shortcuts (run from repo root): `make smoke` (`SmokeTests` filter) and
`make e2e` (`MoqIntegrationTests` filter).

## Module Structure

The server is split into focused Swift package targets (see `Package.swift`):

| Target | Responsibility |
|--------|---------------|
| `MoqCore` | Framework-agnostic domain types, protocols, validation |
| `MoqFormat` | `.moqproj` file loading, writing, validation, runtime conversion |
| `MoqRuntime` | Vapor app, routing, mock storage, admin API, auth |
| `MoqCLI` | ArgumentParser subcommands wiring everything together |
| `Run` | `@main` entry point only |

Dependency direction: `Run → MoqCLI → MoqRuntime → MoqFormat → MoqCore`

> **Note:** `Sources/MoqCompanionAI/` exists on disk but is **not** a target in
> `Package.swift` and is not built by the package, Makefile, or CI. Treat it as
> experimental/legacy. AI provider calls now live in Studio (`studio-ai`), not in a
> server-side companion process. Do not assume it compiles or ships; confirm with the
> maintainer before reviving or removing it.

## Architecture

### Request Flow

```
Incoming request
  → MockRouter (per-endpoint Vapor routes + catch-all 404 fallback)
  → MockHandler
      → InMemoryMockStore.lookup(method, templatePath)   // actor; GraphQL uses operation-level lookup
      → Variant selection (priority order below)
  → HTTP response
```

**Variant selection priority**: `X-Mock-Variant` header → `RequestMatch` constraints → `Accept` header content negotiation → first variant (default)

**Path parameter matching**: OpenAPI-style templates like `/users/{id}` are registered as Vapor `:id` route parameters, so Vapor's router handles matching natively.

### Key Design Decisions

- **`actor InMemoryMockStore`** — thread safety via Swift concurrency; no locks
- **Raw `Data` for response bodies** — no decode/re-encode overhead
- **ArgumentParser CLI** — not tied to Vapor's command system, so the lifecycle is controlled independently
- **Validation-first startup** — `serve` runs `ProjectValidator` before binding the port; invalid projects abort with a clear error

## Test Targets

| Target | Coverage |
|--------|---------|
| `MoqCoreTests` | AuthValidator, RequestValidator, EndpointConverter |
| `MoqFormatTests` | ProjectLoader, ProjectValidator, ProjectWriter |
| `MoqRuntimeTests` | Admin API, auth integration, content negotiation |
| `MoqIntegrationTests` | End-to-end: project → serve → request → verify |
| `MoqCLITests` | CLI command wiring |

## Code Style

### Imports
- **Always sort imports** alphabetically when modifying a file.
- **Remove unused imports** — do not leave stale imports after refactors.
- Standard Swift grouping order: `Foundation`/system frameworks → third-party packages → internal targets.
- Each group separated by a blank line; no blank lines within a group.

### General
- Prefer `async/await`; the server is already structured around async APIs.
- Preserve actor-based concurrency boundaries such as `InMemoryMockStore`.
- Avoid force unwraps; prefer throwing functions or explicit validation results over silent fallback.
- Build HTTP error responses with structured payloads, not plain strings.
- Keep parsing, format, runtime, and CLI concerns in their existing targets.
- Match existing naming: `TypeName` for types, `camelCase` for functions/properties, descriptive test names.

## Test Expectations

- New server code should usually ship with tests in the same change.
- Add focused unit tests for domain, parsing, format, and validation logic.
- Add integration or runtime tests when behavior crosses module boundaries, affects routing, auth, content negotiation, persistence, or end-to-end serving behavior.
- If a server change reasonably does not need a test, note that explicitly in the change summary.

## Related Docs

- `../ARCHITECTURE.md` — full system design and request flow
- `../docs/API_GUIDE.md` — running, config, variant switching, auth, Docker
- `../docs/ADMIN_API.md` — admin API reference
- `../docs/FORMAT_IMPLEMENTATION.md` — `.moqproj` format contract
- `../docs/ERROR_CATALOG.md` — structured error shapes
