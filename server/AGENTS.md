# AGENTS.md

Canonical guide for AI agents working in `server/` (the Swift/Vapor mock server).
This is the single source of truth for server architecture, commands, and conventions.
`server/CLAUDE.md` is a stub that points here.

For cross-repo workflow and shared expectations, see the root `AGENTS.md`.

## Tech Stack

- **Language**: Swift 5.10+ (package `swift-tools-version:5.10`); built and tested with the Swift 6.2 toolchain in CI
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
swift run moqserver serve --project Tests/MoqFormatTests/Fixtures/sample-app.moqproj --port 8080
swift run moqserver validate --project path/to/project.moqproj
```

Makefile shortcuts (run from repo root): `make smoke` (`SmokeTests` filter) and
`make e2e` (`MoqIntegrationTests` filter).

## Module Structure

The server is split into focused Swift package targets (see `Package.swift`):

| Target | Responsibility |
|--------|---------------|
| `MoqCore` | Framework-agnostic domain types, protocols, validation |
| `MoqFormat` | `.moqproj` file loading, writing, validation, runtime conversion, `ProjectStore` (mutating actor) |
| `MoqImport` | OpenAPI 3.x / HAR spec parsing and conversion into `MoqProject` |
| `MoqRuntime` | Vapor app, routing, mock storage, admin API, auth |
| `MoqCLI` | ArgumentParser subcommands wiring everything together |
| `Run` | `@main` entry point for the `moqserver` binary |
| `MoqMCP` | MCP server: tools/resources for agent-driven `.moqproj` authoring |
| `MoqMCPRun` | `@main` entry point for the standalone `moq-mcp` binary |

Dependency direction: `Run → MoqCLI → MoqRuntime → MoqFormat → MoqCore`, and separately
`MoqMCPRun → MoqMCP → MoqImport → MoqFormat → MoqCore`. `MoqCLI` does **not** depend on `MoqMCP` —
`moq-mcp` ships only as its own binary, so the MCP SDK and OpenAPIKit never enter the `moqserver`
binary's dependency graph.

> **Note:** AI provider calls live in Studio (`studio-ai`), not in a server-side
> companion process. The server is intentionally AI-free at runtime so that mocks
> serve deterministically. `moq-mcp` makes no LLM calls either — its tools are deterministic;
> the calling agent (Claude Code, etc.) generates bodies/headers/cookies itself.

## MCP Server

`moq-mcp` is a standalone binary (built from `MoqMCPRun`/`MoqMCP`) that lets an AI agent author
`.moqproj` bundles directly over the [Model Context Protocol](https://modelcontextprotocol.io),
using stdio transport. Build and run it locally with:

```bash
swift build --product moq-mcp
.build/debug/moq-mcp   # speaks JSON-RPC over stdin/stdout; register it with an MCP client
```

Tools cover project lifecycle (`moq_create_project`, `moq_open_project`, `moq_describe_project`,
`moq_save_project`), endpoint/variant authoring (`moq_upsert_endpoint`, `moq_remove_endpoint`,
`moq_upsert_variant`, `moq_remove_variant`, `moq_suggest_endpoint_id`), reading
(`moq_list_endpoints`, `moq_get_endpoint`), validation (`moq_validate_project`), and import
(`moq_import_har`, `moq_import_openapi`). Resources expose the format contract:
`moq://schema/moqproj.json`, `moq://docs/authoring-rules`, `moq://project/current`.

URL-based OpenAPI import is disabled unless the server process has `MOQ_MCP_ALLOW_NETWORK=1` set
— `moq_import_openapi` with a local file path always works.

## Scripted / CI Authoring

`moq-author` is a standalone binary (built from `MoqAuthorRun`/`MoqAuthorCLI`) for authoring a
bundle from a shell script or CI step, with no MCP client and no agent in the loop. `moq-mcp` and
`moq-format` both assume a long-lived client speaking a stateful protocol — exactly what a one-shot
script doesn't have — so `moq-author` instead runs one atomic operation per invocation: open (or
create) the project, apply exactly one mutation, save, exit. Build and run it with:

```bash
swift build --product moq-author
.build/debug/moq-author project create --path ./my-api.moqproj --name "My API"
.build/debug/moq-author endpoint upsert --project ./my-api.moqproj --json endpoint.json
.build/debug/moq-author variant upsert --project ./my-api.moqproj --json variant.json
```

Structured arguments (an endpoint, a variant, an import request) are passed as a JSON file or via
stdin (`--json -`) rather than as a bespoke flag per nested field — the same `Decodable` payload
shapes `moq-mcp` tool calls and `moq-format` JSON-RPC requests already decode, so a script authors
the identical shape regardless of entry point. Every failure prints `{"code", "message"}` on
stderr with a non-zero exit — the same error catalog (`E_...`) all three entry points share.

Kept as a separate target/binary rather than added to `moqserver`'s own `serve`/`validate`
subcommands so the `moqserver` binary's dependency graph stays free of `MoqImport`/OpenAPIKit, per
the note on `MoqCLI` above.

## Test Support for Consuming Apps

`server/MoqTestSupport/` is a **separate** Swift package (its own `Package.swift`, targeting
iOS/macOS) providing `MoqControl` — drives a running `moqserver`'s admin API from an app's own UI
tests (select/reset a variant, reset a call count). It is not a target of this directory's own
`Package.swift` and has its own `swift build`/`swift test`, run from `server/MoqTestSupport/`, since
it links XCTest and targets Apple platforms only — see its README for why and how a consuming app
adds it as a dependency.

## AI Skills for `.moqproj` authors

`server/skills/` ships repo-local skills for any agent authoring or driving a bundle via
`moq-mcp` — not for working on moqserver's own source (that's this file and the sections below).
They encode traps that have cost real debugging time in downstream consumers:

- `moqserver-authoring` — bundle layout, the tool sequence, HAR/OpenAPI import curation, the
  case-insensitive-variant-name and hand-edit-while-open traps.
- `moqserver-scenario-design` — `call_count` (bundle-wide, not test-scoped) vs runtime variant
  selection via the admin API (test-scoped).
- `moqserver-request-validation` — `request_rules`/`global_rules`, and why a bundle-wide auth rule
  breaks endpoints that must stay unauthenticated.
- `moqserver-serving-for-tests` — running a bundle locally/in CI and driving scenarios from a test.

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
| `MoqFormatTests` | ProjectLoader, ProjectValidator, ProjectWriter, ProjectStore |
| `MoqImportTests` | HARImporter, OpenAPIImporter, ImportConverter, SpecFetcher |
| `MoqRuntimeTests` | Admin API, auth integration, content negotiation |
| `MoqIntegrationTests` | End-to-end: project → serve → request → verify |
| `MoqCLITests` | CLI command wiring |
| `MoqMCPTests` | ProjectSession, and full tool/resource calls via an in-memory MCP client/server pair |

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
