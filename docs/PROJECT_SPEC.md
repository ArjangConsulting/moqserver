# moqserver — Project Specification

## Overview

moqserver is a two-part system for mocking REST and GraphQL APIs during mobile client development and testing:

1. **moqserver** (server) — A Swift/Vapor CLI tool that reads a project file and serves mock responses. Already scaffolded.
2. **moqserver studio** (desktop app) — A desktop authoring tool for importing HAR/OpenAPI, editing endpoints, configuring behaviors, and exporting project files that moqserver consumes.

Both live in a single monorepo. The **project format** is the contract between them.

The product should be **AI-first**. AI is not an optional add-on panel. It is a first-class authoring capability used to help users import specs, generate realistic error cases, analyze API surface area, fill gaps in mocks, and keep exported projects clean and deterministic.

---

## AI-First Product Principles

AI support should follow these rules:

1. **Human-reviewed, machine-assisted** — AI can suggest, generate, and refactor mocks, but users always review and accept changes before export.
2. **Deterministic runtime** — AI never changes how moqserver executes requests at runtime. Exported `.moqproj` files remain plain project files with no model dependency.
3. **Structured output over chat-only UX** — AI results should map into concrete project artifacts: variants, error payloads, tags, aliases, fixtures, request rules, and notes.
4. **Traceable provenance** — Studio should mark AI-generated or AI-edited content so users can inspect what was proposed versus what was imported verbatim.
5. **Local-first where practical** — Parsing, validation, sanitization, and deterministic transformations stay local. AI is used for reasoning, generation, and summarization where heuristics alone are weak.
6. **Safe by default** — Sensitive values discovered during HAR/OpenAPI import must be redacted before they are sent to any external AI provider.

AI should help users produce a better `.moqproj` faster, but the exported project must still be understandable and maintainable without AI.

---

## Repository Structure

```
moqserver/
├── Sources/              # Swift server (existing)
├── Tests/                # Server tests (existing)
├── Package.swift         # Existing
├── studio/               # Desktop app (Compose Multiplatform)
│   ├── build.gradle.kts
│   ├── src/
│   └── ...
├── format/               # Shared format schema + validation
│   ├── schema.json       # JSON Schema for .moqproj
│   └── examples/         # Example project files
├── docs/                 # Combined documentation
└── README.md
```

The server stays at the repo root (no file moves). Studio is a self-contained desktop app in `studio/`. The `format/` directory holds the canonical schema that both sides reference.

---

## System Flow

```
HAR file ─────┐
              ├──→ Studio (desktop app) ──→ AI-assisted authoring ──→ .moqproj file ──→ moqserver (CLI) ──→ mock API
OpenAPI spec ─┘         │
                        ├─ import & parse
                        ├─ analyze API surface
                        ├─ suggest structure and variants
                        ├─ group endpoints
                        ├─ edit variants, rules, auth
                        ├─ generate missing error cases
                        ├─ strip sensitive data
                        └─ export project
```

The desktop app is the authoring tool. The server is the runtime. They communicate through the project file and nothing else.

AI lives inside the authoring loop, not the runtime path.

---

## Project Format (.moqproj)

A `.moqproj` is a directory with this structure:

```
my-project.moqproj/
├── project.yml           # Main manifest
├── endpoints/            # One file per endpoint group
│   ├── list-users.yml
│   ├── get-user.yml
│   └── create-order.yml
└── fixtures/             # Large response bodies (referenced by endpoints)
    ├── users-list.json
    └── order-detail.json
```

Splitting endpoints into individual files keeps diffs readable and avoids merge conflicts when multiple people edit the same project.

The `.moqproj` format remains the canonical output even when content is AI-generated. AI may help create or refine project data, but runtime behavior must always be expressible using the existing project format.

### project.yml (manifest)

```yaml
version: "1"
name: "My App API Mock"
description: "Backend mock for iOS/Android testing"

defaults:
  delay_ms: 0
  auth:
    type: none            # none | bearer | basic | api-key | header
    verify: false
    header_name: null     # required for api-key and header
  network:
    latency_ms: 0
    jitter_ms: 0
    packet_loss_percent: 0

global_rules:
  required_headers: []
  verify_cookies: false
```

### Endpoint file (e.g., endpoints/list-users.yml)

```yaml
id: list-users
alias: "List Users"
method: GET
path: /api/v1/users
tags: [users, core]

# Overrides project-level defaults
auth:
  type: bearer
  verify: true            # presence-only check; values are never inspected
  header_name: Authorization

# Request validation — server checks incoming requests against these rules
# and returns 400 if they fail
request_rules:
  headers:
    - name: Accept
      match: "application/json"
      required: true
  verify_cookies: true    # presence-only cookie check
  query_params: []

# Response variants — each is a complete response the server can return
variants:
  - name: success
    default: true
    status: 200
    headers:
      Content-Type: application/json
    body_file: fixtures/users-list.json   # reference external file
    delay_ms: 50

  - name: empty
    status: 200
    body: { "users": [], "total": 0 }    # inline body

  - name: unauthorized
    status: 401
    body: { "error": "Invalid token" }

  - name: server-error
    status: 500
    body: { "error": "Internal server error" }

# Per-endpoint network override
# delay_ms is an additional response delay used to mimic slowness.
network:
  latency_ms: 100
  jitter_ms: 20
```

### Variant selection at runtime

When moqserver receives a request, it picks a variant using this priority:

1. `X-Mock-Variant` request header (e.g., `X-Mock-Variant: unauthorized`)
2. Server-level config override (CLI flag or env var)
3. The variant marked `default: true`
4. First variant in the list

### GraphQL handling

GraphQL endpoints share a single path (`POST /graphql`) but differ by operation. The project format uses an `operation` field to distinguish named and anonymous operations.

```yaml
id: get-user-profile
alias: "User Profile Query"
method: POST
path: /graphql
operation:
  type: query             # query | mutation | subscription
  name: GetUserProfile    # operation name from the request body

variants:
  - name: success
    status: 200
    body:
      data:
        user:
          id: "123"
          name: "Test User"

  - name: not-found
    status: 200
    body:
      data: null
      errors:
        - message: "User not found"
          path: ["user"]
```

Anonymous GraphQL operations are supported by storing the normalized query document when no operation name is available:

```yaml
id: current-user
alias: "Current User Query"
method: POST
path: /graphql
operation:
  type: query
  document: |
    query {
      currentUser {
        id
        name
      }
    }

variants:
  - name: success
    status: 200
    body:
      data:
        currentUser:
          id: "123"
          name: "Test User"
```

The server matches GraphQL requests by parsing the JSON body.

- If `operationName` is present, it matches on `operation.name`.
- If `operationName` is absent, it matches on a normalized form of the GraphQL `query` document stored in `operation.document`.

Normalization should remove insignificant whitespace and comments so formatting differences do not break matching.

### format/schema

`format/` holds the canonical description of the project format shared by Studio and moqserver.

- `format/examples/` contains hand-written sample `.moqproj` directories used for development and tests.
- `format/schema.json` is the machine-readable contract for the project format. Even if the project files are authored in YAML, the schema still defines the allowed structure, required fields, field types, enums, and validation rules that both Studio and moqserver should follow.

For v1, using YAML for the actual project files is fine. The schema still matters because it gives both sides one source of truth for validating imported/exported data and prevents format drift over time.

### Schema vs validator responsibilities

The format contract is split across two layers:

- `format/schema.json` validates the shape of a parsed YAML document.
- `moqserver validate --project` enforces project-wide rules that require looking across the whole `.moqproj` directory.

Examples of schema-level validation:

- `auth.type` must be one of the allowed enum values.
- `auth.header_name` is required for `api-key` and `header`.
- a variant cannot define both `body` and `body_file`.
- a GraphQL `operation` must include `type` and at least one of `name` or `document`.

Examples of project-level validation:

- `project.yml` exists at the project root.
- endpoint ids are unique across all files in `endpoints/`.
- reserved routes are not overridden.
- every `body_file` path points to a real file in `fixtures/`.
- only one variant per endpoint is marked as the default.

### Contract summary (v1)

For v1, the project format should be treated as this contract:

- `project.yml` is required.
- `endpoints/` is required and must contain one or more endpoint YAML files.
- `fixtures/` is optional.
- `version`, `name`, and `defaults` are required in `project.yml`.
- Each endpoint file must define `id`, `method`, `path`, and `variants`.
- Endpoint `id` values must be unique within a project.
- Variant `name` values must be unique within an endpoint.
- `auth.header_name` is required when `auth.type` is `api-key` or `header`.
- `auth.header_name` may be set to `Authorization` for `bearer` and `basic`, but the server should still enforce the standard `Authorization` header semantics for those types.
- `verify_cookies` is a boolean and means presence-only cookie verification.
- `body` and `body_file` are mutually exclusive in a variant.
- GraphQL endpoint definitions in v1 must define `operation.type` and at least one of `operation.name` or `operation.document`.

### AI metadata

AI should not be required by the runtime contract. For v1, any provenance or review metadata generated by Studio should either:

- stay in Studio-only local state, or
- be stored in optional non-runtime metadata fields that moqserver ignores safely.

The server must not depend on an AI model, prompt history, or provider-specific output to load or execute a project.

### Validation rules (v1)

`moqserver validate --project` and Studio export validation should enforce the same rules:

1. `project.yml` must exist at the root of the `.moqproj` directory.
2. `endpoints/` must exist and contain at least one `.yml` file.
3. Every endpoint file must parse successfully as YAML.
4. Endpoint ids must be unique across the project.
5. Reserved paths `/health` and `/__admin/endpoints` may not be used by mock endpoints.
6. Every endpoint must include at least one variant.
7. At most one variant may have `default: true`.
8. A variant may define `body` or `body_file`, but not both.
9. Every `body_file` must point to an existing file inside `fixtures/`.
10. `auth.type` must be one of `none`, `bearer`, `basic`, `api-key`, or `header`.
11. `auth.header_name` is required when `auth.type` is `api-key` or `header`.
12. `request_rules.verify_cookies` must be a boolean.
13. For GraphQL endpoints, `path` must be `/graphql` and `operation.type` must be present.
14. GraphQL endpoints must define at least one of `operation.name` or `operation.document`.
15. If `operation.document` is present, it must be non-empty after normalization.

### Runtime precedence (v1)

The server should apply runtime behavior in this order:

1. Match the endpoint by method and path. For GraphQL, match `operationName` when present, otherwise match the normalized query document.
2. Enforce auth verification if `auth.verify: true`.
3. Enforce request validation rules, including header checks and cookie presence checks.
4. Select the response variant using this priority:
   - `X-Mock-Variant` request header
   - server-level override
   - variant marked `default: true`
   - first variant in the list
5. Apply response timing and network behavior.

If auth verification fails, the server returns the `unauthorized` variant when present, otherwise a generated `401` response. Auth failure takes precedence over manual variant selection.

### Response timing (v1)

`delay_ms` is an additional response delay used to mimic slowness. For v1, the effective response delay is:

`effective_delay_ms = defaults.delay_ms + variant.delay_ms + network.latency_ms + random(0...network.jitter_ms)`

Any omitted timing value is treated as `0`.

---

## Component 1: moqserver (Server)

### What exists

Phase 1 is complete: core models (`MockEndpoint`, `MockResponse`, `AuthType`), `Package.swift` with Vapor 5, `main.swift`, Dockerfile, docker-compose, example specs, and documentation.

### What needs to be built or updated

**Project loader** — Read a `.moqproj` directory, parse `project.yml` and all endpoint files, and hydrate into the existing `MockEndpoint` model. This replaces the current OpenAPI-only loading path as the primary input. OpenAPI remains supported as an import source (through Studio), but the server's native input is the project format.

**AI-safe validation boundary** — The server should treat AI-generated project content exactly like hand-authored content: validate it, reject invalid exports, and never assume generated payloads are structurally correct.

**GraphQL routing** — The current router assumes REST (method + path = unique endpoint). For GraphQL, the router needs a second-level match on either `operationName` or a normalized query document from the request body. All GraphQL requests hit the same path, so the handler must parse the body before selecting an endpoint.

**Request validation** — Check incoming requests against `request_rules`. If a required header is missing or doesn't match, return 400 with a descriptive error. Cookie verification is presence-only: if `verify_cookies: true`, the request must include at least one cookie. This is for validating that clients send correct requests, not for auth (auth is separate).

**Network simulation** — Apply `delay_ms`, `latency_ms`, `jitter_ms`, and `packet_loss_percent` before returning responses. `delay_ms` is an additional response delay used to mimic application slowness or network latency. Delay and jitter are straightforward (`Task.sleep`). Packet loss means occasionally returning no response instead of the response.

**Auth middleware updates** — The existing auth model supports none/bearer/basic/api-key. Add `header` type (check for presence of a named header). Auth and api-key checks are presence-only for security reasons; the server should not inspect or validate token or header values. Add the `verify` flag so endpoints can opt into auth verification while keeping auth configuration visible in Studio.

**Health and admin endpoints** — `GET /health` for Docker healthchecks. `GET /__admin/endpoints` to list registered endpoints (useful for debugging). These are reserved paths that can't be overridden by mock endpoints.

### CLI interface

```bash
# Serve from a project file (primary usage)
moqserver serve --project ./my-project.moqproj --port 8080

# Serve from OpenAPI directly (convenience, skips Studio)
moqserver serve --spec ./openapi.yaml --port 8080

# Override default variant for all endpoints
moqserver serve --project ./my-project.moqproj --variant error

# Validate a project file without starting the server
moqserver validate --project ./my-project.moqproj

# Analyze an API spec and summarize suggested mock coverage
moqserver analyze --spec ./openapi.yaml
```

`analyze` is optional for the first server milestone, but the product direction should reserve room for a CLI or Studio-backed analysis flow that can summarize endpoint coverage, likely missing variants, auth expectations, and schema quality issues.

---

## Component 2: moqserver studio (Desktop App)

A desktop application for authoring moqserver project files. It runs locally on the developer machine and is optimized for direct local file access, tight integration with moqserver, and future expansion into a richer local tooling surface.

### Technology

Kotlin Multiplatform + Compose Multiplatform, targeting desktop first. The initial Studio target should be macOS desktop, with the architecture kept portable to Windows and Linux later. All import, parsing, validation, and editing workflows run locally.

AI integration should use a provider abstraction so Studio can support local/on-device models, hosted APIs, or enterprise-managed endpoints without changing the core product model.

Provider secrets still should not be handled ad hoc inside the UI layer. Any integration that requires a secret key from an environment variable should use the local Swift/Vapor companion process, local gateway, or user-managed proxy rather than coupling provider secrets directly to UI code.

### Technology decision

For v1, the Studio app should use **Kotlin Multiplatform + Compose Multiplatform Desktop**, not a browser-first React app.

#### Why Kotlin Multiplatform is the better fit now

- The product value is moving toward a desktop-first local authoring environment.
- A desktop app has simpler and more reliable access to the local file system than a browser tool.
- A desktop app can integrate more tightly with the local moqserver runtime and the Swift/Vapor companion without browser trust-boundary friction.
- Compose Multiplatform officially supports desktop as a first-class target and provides native packaging for macOS, Windows, and Linux.
- It keeps the door open for future expansion into broader multiplatform tooling if the product later wants additional native surfaces.

#### Why not React for v1

React is still strong for browser tooling, but the browser is no longer the best product boundary for this app.

- Browser-based file system access, local service orchestration, and secret handling all require extra compromises that a desktop app avoids.
- Choosing React would optimize for web distribution while the product value is moving toward a local desktop authoring environment.

#### Revisit criteria

Reconsider a browser-based React Studio only if the product later decides that:

- zero-install web access is more important than deep local integration, or
- the team wants a separate lightweight web viewer/editor in addition to the desktop app

That would still be compatible with the `.moqproj` format, but it should not drive the v1 architecture.

### Core workflows

**Import HAR** — User drops a `.har` file. Studio parses it, extracts unique endpoint signatures (method + path, or method + path + operationName for named GraphQL operations, or method + path + normalized query document for anonymous GraphQL operations), groups duplicate calls together, and creates endpoint definitions with the captured responses as variants. Query parameters with varying values are normalized into the path pattern (e.g., `/users?id=1` and `/users?id=2` become `/users` with `id` as a documented query param).

**Import OpenAPI** — User drops a YAML/JSON spec. Studio walks paths and operations, extracts examples (preferring `example` over `schema`), and creates endpoint definitions. If no examples exist, generates stub JSON from the schema. If nothing is defined, uses `{}`. AI should then help enrich the import by proposing missing negative cases, more realistic error payloads, aliases, tags, fixture extraction, and request rule defaults.

**Sanitize on import** — During HAR import, Studio identifies and strips sensitive data: Authorization headers (replaced with `Bearer <MOCK_TOKEN>`), cookies (replaced with placeholder values), and any field matching common patterns (api_key, password, secret, token, ssn, credit_card). Users can review what was stripped and adjust before finalizing.

**Endpoint management** — The main editing interface. Endpoints are grouped and listed in a sidebar. Each endpoint shows its method, path, alias, and variant count. Users can:
- Rename endpoints (set alias)
- Add/remove/edit response variants
- Set status codes, headers, and bodies for each variant
- Mark which variant is the default
- Configure auth requirements
- Set request validation rules
- Override network conditions per endpoint

**AI-assisted mock generation** — For any endpoint or group of endpoints, users can ask Studio to generate:
- common error variants (`400`, `401`, `403`, `404`, `409`, `422`, `429`, `500`, `503`) based on method, auth, and schema context
- realistic error messages and payload shapes aligned with the API domain
- empty-state, partial-data, and degraded-state success responses
- GraphQL `errors` arrays with plausible messages and paths
- request validation suggestions from parameter and schema definitions

Generated results should appear as draft variants that the user can diff, edit, and accept.

**AI-assisted API analysis** — Studio should provide an analysis pass over imported specs/projects that can surface:
- endpoints missing examples or variants
- inconsistent naming, tagging, or status-code patterns
- auth requirements that are implied in the spec but missing in mocks
- high-risk flows worth mocking explicitly such as pagination, rate limiting, retries, empty states, and server failures
- opportunities to consolidate repeated inline JSON into fixtures

The output should be operational, not just descriptive. Each finding should offer an action such as “add variant,” “extract fixture,” “rename alias,” or “add request rule.”

**AI-assisted project refinement** — After import, Studio should help convert rough imported data into an elegant mock project by:
- suggesting endpoint aliases and grouping
- normalizing noisy HAR imports into cleaner endpoint definitions
- detecting repeated bodies and recommending fixture extraction
- proposing cleaner variant names and defaults
- identifying over-specified request matchers that should be simplified

**JSON body editor** — Variants with JSON bodies get a desktop-native editing surface built with Compose Multiplatform and a Swing-interop code editor. For v1, the editor should use `RSyntaxTextArea` embedded via `SwingPanel`, giving us syntax highlighting, line numbers, code folding, search/replace, and a mature text-editing experience on JVM without introducing a browser runtime inside the desktop app. Large bodies can be saved as fixture files.

The editor should support prompt-based transformations such as “turn this into an empty-state response,” “generate a 422 validation error,” or “make this payload look like a partial outage,” with changes applied as an explicit preview.

**Project management** — Save/load `.moqproj` directories. Switch between multiple projects. Each project is independent.

**Export** — Generate the `.moqproj` directory structure. The export is what gets committed to the repo and fed to moqserver.

Before export, Studio should run both deterministic validation and an optional AI review pass that highlights suspicious variants, unrealistic payloads, duplicated fixtures, and missing edge cases.

### UI structure

```
┌──────────────────────────────────────────────────────────┐
│  moqserver studio          [Import HAR] [Import OpenAPI] │
│  Project: My App API       [Export] [Settings]           │
├────────────────┬─────────────────────────────────────────┤
│ Endpoints      │ GET /api/v1/users                       │
│                │ Alias: List Users                       │
│ ▸ users (3)    │                                         │
│   GET /users   │ Auth: Bearer (required)                 │
│   GET /users/  │                                         │
│   POST /users  │ Variants:                               │
│ ▸ orders (2)   │ ● success (200) [default]     [edit]    │
│ ▸ graphql (4)  │ ○ empty (200)                 [edit]    │
│                │ ○ unauthorized (401)           [edit]    │
│ + Add endpoint │ ○ server-error (500)           [edit]    │
│                │ + Add variant                            │
│                │                                         │
│                │ Request Rules:                           │
│                │   Accept: application/json (required)    │
│                │                                         │
│                │ Network: 100ms latency, 20ms jitter     │
└────────────────┴─────────────────────────────────────────┘
```

---

## HAR Import — Detail

HAR (HTTP Archive) is a JSON format capturing browser/app network traffic. The import process:

1. **Parse** — Read the `.har` JSON, extract `log.entries[]`.
2. **Filter** — Keep only entries where `response.content.mimeType` is `application/json` (or user-selected types). Discard static assets, images, etc.
3. **Group** — Group entries by method + URL path (stripping query params and fragments). For GraphQL, additionally group by `operationName` when present, otherwise by normalized query document extracted from the request body.
4. **Normalize paths** — Detect path parameters by finding segments that vary across grouped entries. `/users/123/posts` and `/users/456/posts` become `/users/:id/posts`.
5. **Create variants** — Each unique response status code within a group becomes a variant. If multiple 200 responses exist with different bodies, keep the first as `success` and name others `success-2`, `success-3`, etc. (user can rename).
6. **Sanitize** — Replace sensitive values (see sanitization rules below).
7. **Present for review** — Show the user what was extracted, let them rename, delete, merge, or adjust before committing.

After deterministic HAR import, AI can optionally help cluster semantically similar responses, suggest better endpoint aliases, infer likely path parameter names, and turn raw captures into maintainable variant sets.

### Sanitization rules

Applied automatically during import. User can disable or customize.

| Pattern | Action |
|---------|--------|
| `Authorization` header | Replace value with `Bearer <MOCK_TOKEN>` or `Basic <MOCK_CREDENTIALS>` |
| `Cookie` / `Set-Cookie` headers | Replace values with `session=MOCK_SESSION` |
| Request/response body fields matching `password`, `secret`, `token`, `api_key`, `apiKey`, `access_token`, `refresh_token`, `ssn`, `credit_card`, `card_number` | Replace with `<REDACTED>` |
| Custom patterns (user-defined) | User can add regex patterns to match and replace |

---

## Auth Configuration — Detail

Auth mocking serves two purposes: (a) testing that the client sends correct auth, and (b) testing how the client handles auth failures.

### Auth types

| Type | What the server checks |
|------|----------------------|
| `none` | No auth check |
| `bearer` | `Authorization: Bearer <any-value>` header exists |
| `basic` | `Authorization: Basic <any-value>` header exists |
| `api-key` | A user-defined header (prefilled in Studio with something like `X-API-Key`) is present; the value is ignored |
| `header` | A user-defined named header is present; the value is ignored |

The server does **not** validate token contents, decode JWTs, inspect cookie values, or check credentials. It only checks presence. This is intentional — the goal is testing client behavior, not server security.

For `api-key` and `header` auth types, the project format must store the header name explicitly. Studio should let the user define the header name and may prefill a sensible default for API-key style auth.

### The `verify` flag

When `verify: true`, requests without the required auth headers get the `unauthorized` variant (or 401 if no such variant exists). When `verify: false`, the auth type is documented but not enforced — requests pass through regardless. This lets teams gradually add auth to their mocks.

### Cookie verification

When `verify_cookies: true`, the server only verifies that the request includes at least one cookie. Cookie names and values are not checked in v1.

---

## AI Capabilities — Detail

AI should be exposed as a first-class workflow in Studio and, where useful, as supporting CLI functionality.

### 1. Error case and message generation

This is one of the most valuable AI features and should be built early.

Given an endpoint definition, imported OpenAPI operation, or HAR-derived mock, AI should be able to generate draft variants for:

- validation errors
- authentication and authorization failures
- not found and conflict scenarios
- rate limiting and temporary outage scenarios
- server-side failures with domain-appropriate error bodies

Generation should use whatever context is available:

- HTTP method and path
- tags and operation summary
- request parameters and request body schema
- auth configuration
- existing success variants
- surrounding endpoints in the same resource group

The goal is not random error text. The goal is consistent, domain-plausible mock behavior that helps client teams test real UI and retry logic.

### 2. OpenAPI import copilot

OpenAPI import should not stop at schema-to-stub conversion. After initial import, AI should help with:

- filling in endpoints that lack examples
- proposing status-code coverage beyond `200`
- turning vague schemas into realistic sample objects
- identifying required request headers and query params worth validating
- generating better aliases and endpoint groupings for the project navigator

This is especially important for incomplete specs where examples are sparse but teams still want usable mocks quickly.

### 3. API analysis

AI analysis should work on both raw specs and already-authored `.moqproj` projects.

Expected outputs:

- a concise summary of the API surface
- risk hotspots and missing mock coverage
- recommendations for high-value scenarios to add
- quality warnings about inconsistent response structures
- suggestions for more maintainable project organization

The analysis should be actionable and tied to direct UI actions.

### 4. Natural-language authoring

Users should be able to describe desired behavior in plain language, for example:

- “Add a 429 variant for all list endpoints.”
- “Generate offline-friendly empty states for user-facing GET endpoints.”
- “Make auth failures consistent across the payments group.”
- “Create GraphQL errors for missing permissions.”

Studio should translate these requests into scoped project edits with preview and approval.

### 5. Elegant spec refinement

AI should help users turn mechanically imported mocks into elegant project structure. That means:

- cleaner naming
- less duplication
- more intentional variant sets
- better fixture extraction
- more readable request rules
- clearer grouping for large APIs

The product goal is not just “more mocks.” It is “cleaner, more maintainable mocks with less manual effort.”

### Provider and privacy model

The AI layer should be provider-agnostic.

- A provider interface should abstract prompt execution and structured results.
- Sensitive data must be redacted before any remote call.
- Teams should be able to disable remote AI entirely.
- Local or enterprise-hosted model support should be possible without changing project format semantics.

### AI provider connectivity

The product should support multiple provider modes from the start.

#### 1. Local Ollama

Support local models through Ollama as a first-class option.

- Preferred local endpoint: `http://localhost:11434`
- Native Ollama API support is useful for local-only workflows.
- Ollama also exposes OpenAI-compatible endpoints under `/v1`, which makes it a strong default target for a generic OpenAI-compatible adapter.
- This path is ideal for privacy-sensitive teams, offline-ish workflows, and cost control.

#### 2. Direct hosted providers via user-owned API keys

Support direct provider access for:

- OpenAI
- Anthropic Claude

However, the product architecture should be explicit about secret handling:

- API keys should come from user-managed Studio settings or another secure local credential source.
- The Studio app may call upstream providers directly for bounded authoring tasks.
- Teams that prefer a gateway or proxy can still use an OpenAI-compatible endpoint instead of direct hosted access.

This design keeps Studio desktop-first while still supporting local and hosted providers cleanly.

#### 3. Generic OpenAI-compatible endpoint

In addition to named providers, Studio should support a generic OpenAI-compatible mode with:

- `base_url`
- `model`
- optional API key env var name
- optional extra headers

This covers a large set of providers and self-hosted tools without bespoke integrations, including:

- Ollama via its OpenAI-compatible API
- local gateways and model runners that mimic the OpenAI API
- hosted aggregators or internal enterprise proxies that expose an OpenAI-style contract

This generic mode is likely the highest-leverage addition beyond the named providers because it lets the project support many deployment styles with one adapter.

#### 4. Enterprise cloud providers

The architecture should leave room for enterprise-managed providers where auth and endpoint shape differ from direct API-key access.

High-value future targets include:

- Azure OpenAI, which uses deployment-specific endpoints and either `api-key` or Microsoft Entra authentication
- Claude on Vertex AI, which uses Google Cloud auth and publisher model endpoints
- other enterprise-managed model platforms where teams want centralized billing, IAM, audit controls, or data residency guarantees

These should be separate adapters because they are not just a different base URL. Their auth and request envelopes differ in meaningful ways.

### Recommended provider architecture

Use a layered provider design:

1. `AIProvider` core interface
  - generate structured suggestions
  - stream text/status updates
  - return usage and provider metadata

2. `ProviderAdapter` implementations
  - `ollama`
  - `openai`
  - `anthropic`
  - `openai-compatible`
  - future: `azure-openai`, `vertex-claude`, others

3. `CredentialSource` abstraction
  - no-auth local mode
  - Studio-managed settings or secure local storage
  - OS keychain/secure local storage if a desktop wrapper is added later
  - ephemeral user session token if a team runs its own gateway

### Implementation blueprint

The implementation should keep the UI, project model, desktop infrastructure, and AI transport concerns sharply separated.

Detailed module boundaries, dependency choices, and editor rationale live in `docs/STUDIO_ARCHITECTURE.md`.

#### Studio module layout

The desktop Studio should start as a small Gradle multi-project build with explicit boundaries:

- `composeApp`
  - desktop executable entry point
  - window lifecycle, menus, navigation, app startup
  - feature composition and top-level screens
- `studio-domain`
  - project/session state
  - undo/redo contracts
  - selection, navigation, dirty-state tracking
  - AI action DTOs and editor-facing models
- `studio-data`
  - filesystem IO
  - YAML parsing/emission
  - schema/validation adapters
- `studio-ai`
  - provider integrations
  - prompt building and response parsing
  - provider configuration validation
- `studio-code-editor`
  - Compose-to-Swing interop wrapper for structured text editing
  - JSON/YAML editor widgets based on `RSyntaxTextArea`

That is enough modularity for v1: pure state and models stay isolated, JVM-only infrastructure stays out of shared code, and the editor integration remains boxed away from the rest of the UI.

#### Studio folder structure proposal

An acceptable v1 folder structure for `studio/` would look like this:

```text
studio/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/
│   └── libs.versions.toml
├── composeApp/
│   ├── build.gradle.kts
│   └── src/
│       ├── commonMain/
│       └── desktopMain/
├── studio-domain/
│   ├── build.gradle.kts
│   └── src/
│       └── commonMain/
├── studio-data/
│   ├── build.gradle.kts
│   └── src/
│       └── main/
└── studio-code-editor/
    ├── build.gradle.kts
    └── src/
        └── main/
```

This structure keeps the app shell small, isolates shared state from JVM-only infrastructure, and leaves room for future feature modules without prematurely exploding the build graph.

#### Studio dependency direction

Dependencies should flow inward:

- `composeApp` depends on `studio-domain`, `studio-data`, and `studio-code-editor`
- `studio-data` depends on `studio-domain`
- `studio-code-editor` depends on no Studio module
- `studio-domain` depends on no Studio module

That keeps the domain layer portable and testable while allowing the desktop shell to aggregate JVM-only capabilities.

#### State management guidance

Studio should use a unidirectional data-flow architecture built on `ViewModel`, `StateFlow`, and explicit user intents.

- user intent enters through actions
- reducers/state handlers update local application state
- side effects handle file IO, import jobs, AI calls, and export
- UI renders from derived state only

The goal is deterministic editing behavior, testable import/export flows, and easy preview/review of AI-generated drafts without introducing a heavyweight architecture framework too early.

#### Background work guidance

The desktop app should run expensive work off the UI thread:

- HAR parsing
- large OpenAPI parsing
- AI request preparation
- project validation
- export and fixture materialization

This should be implemented with Kotlin coroutines and structured concurrency so long-running jobs are cancelable and progress can be surfaced in the UI.

#### Swift/Vapor companion module layout

The Swift companion should live alongside the existing server code and reuse shared infrastructure where it makes sense.

Suggested layout:

- `Sources/MoqServer/AI/`
  - `AIProvider.swift`
  - `AIProviderRegistry.swift`
  - `AIRequestRedactor.swift`
  - `AIUsage.swift`
- `Sources/MoqServer/AI/Providers/`
  - `OllamaProvider.swift`
  - `OpenAIProvider.swift`
  - `AnthropicProvider.swift`
  - `OpenAICompatibleProvider.swift`
- `Sources/MoqServer/AI/API/`
  - request/response DTOs for the local Studio contract
- `Sources/MoqServer/AI/Routing/`
  - `AIProxyHandler.swift`
  - `AIProxyRouter.swift`
- `Sources/MoqServer/CLI/`
  - `AIProxyCommand.swift` or `StudioBridgeCommand.swift`

This keeps AI transport and provider logic isolated from runtime request mocking.

### Local API contract

The local Studio-to-companion contract should be intentionally narrow and provider-neutral.

#### Common request envelope

Every AI request should include:

- `operation`: the high-level intent such as `analyze_spec`, `generate_variants`, or `refine_project`
- `project_context`: a redacted, bounded context payload
- `provider_config_ref`: the selected provider configuration or local preset id
- `options`: temperature, output mode, or model-specific knobs that Studio exposes
- `client_request_id`: a Studio-generated correlation id

Example:

```json
{
  "operation": "generate_variants",
  "project_context": {
    "project_name": "My App API Mock",
    "selected_endpoint_ids": ["list-users"],
    "redacted_endpoints": [
      {
        "id": "list-users",
        "method": "GET",
        "path": "/api/v1/users",
        "tags": ["users"],
        "auth": { "type": "bearer", "verify": true },
        "variants": [
          {
            "name": "success",
            "status": 200,
            "body": { "users": [{ "id": "<REDACTED>", "name": "<REDACTED>" }] }
          }
        ]
      }
    ]
  },
  "provider_config_ref": "local-ollama-default",
  "options": {
    "max_variants": 4,
    "include_error_cases": true,
    "temperature": 0.2
  },
  "client_request_id": "3ec0d8d4-fc8f-48d3-80c0-2c64898f5db4"
}
```

#### Common response envelope

Every AI response should include:

- `status`: `ok`, `partial`, or `error`
- `result`: normalized structured payload for the requested operation
- `provider_metadata`
  - provider kind
  - model
  - request id if available
  - rate-limit or usage data if available
- `warnings`: redaction notes, truncation notes, or provider fallbacks
- `error`: populated only on failure

Example:

```json
{
  "status": "ok",
  "result": {
    "endpoint_id": "list-users",
    "draft_variants": []
  },
  "provider_metadata": {
    "provider_kind": "ollama",
    "model": "qwen3:8b",
    "request_id": null,
    "usage": {
      "input_tokens": 1250,
      "output_tokens": 420,
      "total_tokens": 1670
    }
  },
  "warnings": [],
  "error": null
}
```

#### Example validation schema

`validate-config` request:

```json
{
  "provider": {
    "kind": "anthropic",
    "base_url": "https://api.anthropic.com",
    "model": "claude-sonnet-4-6",
    "auth": {
      "mode": "settings"
    }
  }
}
```

`validate-config` response:

```json
{
  "valid": true,
  "issues": [],
  "resolved": {
    "kind": "anthropic",
    "base_url": "https://api.anthropic.com",
    "model": "claude-sonnet-4-6"
  }
}
```

#### Operation-specific result shapes

`analyze-spec` should return:

- coverage gaps
- naming inconsistencies
- suggested variants
- auth/request-rule suggestions
- maintainability suggestions

`generate-variants` should return:

- draft variants with status codes
- headers
- bodies or fixture recommendations
- rationale per generated variant

Example:

```json
{
  "endpoint_id": "list-users",
  "draft_variants": [
    {
      "name": "unauthorized",
      "status": 401,
      "headers": { "Content-Type": "application/json" },
      "body": {
        "error": {
          "code": "unauthorized",
          "message": "Authentication is required to list users."
        }
      },
      "rationale": "Endpoint already requires bearer auth; add a standard auth failure variant."
    },
    {
      "name": "rate-limited",
      "status": 429,
      "headers": {
        "Content-Type": "application/json",
        "Retry-After": "30"
      },
      "body": {
        "error": {
          "code": "rate_limited",
          "message": "Too many requests. Please try again later."
        }
      },
      "rationale": "Collection endpoints are common retry and backoff test targets."
    }
  ]
}
```

`refine-project` should return:

- rename suggestions
- grouping suggestions
- duplicate-body extraction candidates
- simplified request-rule recommendations

Example:

```json
{
  "rename_suggestions": [
    {
      "endpoint_id": "get-user-profile-2",
      "current_alias": "Get User Profile 2",
      "suggested_alias": "Get User Profile"
    }
  ],
  "fixture_extraction_candidates": [
    {
      "variant_refs": [
        "list-users:success",
        "list-admin-users:success"
      ],
      "suggested_fixture_path": "fixtures/users-list.json"
    }
  ],
  "request_rule_suggestions": [
    {
      "endpoint_id": "list-users",
      "suggested_change": "Remove header matcher for X-Request-Id because it appears capture-specific and not behavior-defining."
    }
  ]
}
```

### Configuration model

Studio should let users configure AI backends with a small, explicit config model.

Example fields:

- provider kind
- base URL
- model name
- auth mode
- env var name for secret lookup
- organization/project/deployment identifiers when required
- default temperature or reasoning settings where supported
- whether remote AI is allowed for the current project

This config should be separate from the `.moqproj` runtime contract. It is authoring configuration, not mock-server behavior.

For v1, this configuration can live in Studio-local settings. It does not need to be checked into the `.moqproj` unless the team later decides some non-secret defaults should travel with a project.

### Recommended support matrix

For initial implementation, the best pragmatic support matrix is:

- `ollama` as the zero-secret local default
- `openai` via direct Studio configuration
- `anthropic` via direct Studio configuration
- `openai-compatible` for everything that behaves like the OpenAI API

That combination gives strong coverage with limited complexity.

### Environment variable guidance

If the team wants env-var-based credentials, the spec should assume these patterns:

- `OPENAI_API_KEY`
- `ANTHROPIC_API_KEY`
- optional provider-specific base URL vars such as `OPENAI_BASE_URL`
- optional generic vars such as `MOQSERVER_AI_BASE_URL`, `MOQSERVER_AI_MODEL`, and `MOQSERVER_AI_API_KEY_ENV`

The important constraint is that these environment variables belong to the local process boundary, not to shipped frontend code.

### Review and acceptance model

Every AI-generated change should support:

- preview before apply
- explicit accept/reject
- per-change provenance
- deterministic re-validation after apply

AI should accelerate authoring, not obscure it.

---

## Network Simulation — Detail

Applied per-endpoint or globally via project defaults. The server applies these before sending the response.

| Setting | Behavior |
|---------|----------|
| `latency_ms` | Fixed delay added to every response |
| `jitter_ms` | Random additional delay (0 to jitter_ms) added on top of latency |
| `packet_loss_percent` | Probability (0–100) that the server drops the connection instead of responding. On "drop," the server closes the connection with no response, simulating a network timeout from the client's perspective. |

These are intentionally simple. Bandwidth throttling and complex network profiles are out of scope for v1. The three settings above cover the most common mobile testing scenarios: slow API, inconsistent API, and unreachable API.

---

## Development Phases

### Phase 1: Project Format + Server Loader
Define the `.moqproj` schema in `format/schema.json`. Implement the project loader in the Swift server so `moqserver serve --project ./x.moqproj` works. Write a few example project files by hand to validate the format. This unblocks everything else.

**Depends on:** Existing Phase 1 scaffold.
**Produces:** Working server that reads project files and serves mocks.

### Phase 2: Request Validation + Auth Updates
Add request rule checking (required headers, cookie verification) and the `verify` flag for auth. These are server-side only and can be tested with curl.

**Depends on:** Phase 1.
**Produces:** Server rejects malformed requests per endpoint rules.

### Phase 3: Network Simulation
Add delay, jitter, and packet loss to the response pipeline. Simple implementation — sleep before responding, random drop.

**Depends on:** Phase 1.
**Produces:** Server simulates network conditions.

### Phase 4: GraphQL Support
Add operation-name-based routing for GraphQL endpoints. Parse request body, extract operationName, match to endpoint definitions.

**Depends on:** Phase 1.
**Produces:** Server correctly routes GraphQL queries/mutations.

### Phase 5: Studio — Scaffolding + Import
Create the Compose Multiplatform desktop app. Implement HAR import (parse, group, normalize, sanitize) and OpenAPI import. No editing yet — just import and display.

**Depends on:** Phase 1 (needs the format schema).
**Produces:** Desktop app that reads HAR/OpenAPI and shows extracted endpoints.

### Phase 6: AI Foundation
Introduce the provider abstraction, the Swift local AI companion, the redaction pipeline for AI requests, structured prompt/result contracts, provenance tracking, and review UI for draft changes.

**Depends on:** Phase 5.
**Produces:** Safe AI plumbing that can power multiple product workflows without coupling runtime behavior to any provider.

### Phase 7: AI-Assisted Import + Analysis
Add AI analysis for imported OpenAPI/HAR content. Generate suggested aliases, tags, missing variants, request rules, and fixture extraction opportunities.

**Depends on:** Phase 6.
**Produces:** Imports that are immediately more useful and easier to refine.

### Phase 8: Studio — Editing + Export
Build the endpoint management UI: variant editing, auth config, request rules, network settings, and AI-assisted editing actions. Implement export to `.moqproj`.

**Depends on:** Phase 5 and Phase 6.
**Produces:** Full authoring workflow: import → analyze → edit → export → serve.

### Phase 9: AI Error Generation + Natural Language Actions
Add focused AI workflows for generating error cases/messages, creating bulk variants from plain-language instructions, and refining large projects.

**Depends on:** Phase 8.
**Produces:** AI becomes a daily-use authoring surface, not a passive insight panel.

### Phase 10: Studio — Project Management + Polish
Save/load projects, multiple project support, fixture file management. UX polish, keyboard shortcuts, bulk operations, and stronger review affordances for AI-generated edits.

**Depends on:** Phase 8.
**Produces:** Production-ready authoring experience for teams managing large mock projects.

**Depends on:** Phase 6.
**Produces:** Production-quality authoring tool.

---

## Validation Error Taxonomy

Both `moqserver validate` and Studio export must emit structured errors so CI can fail deterministically and Studio can surface inline feedback. Every error has a `code`, a human-readable `message`, and an optional `location` pointing to the file and field that caused it.

### Format

```
[CODE] message
  at: <file> → <field path>
```

Example:

```
[E004] Duplicate endpoint id "list-users"
  at: endpoints/list-users-v2.yml → id

[E008] body_file "fixtures/missing.json" does not exist
  at: endpoints/get-user.yml → variants[0].body_file

[E011] auth.header_name is required when auth.type is "api-key"
  at: endpoints/create-order.yml → auth.header_name
```

### Error codes

#### Structure errors (E0xx) — caught by schema validation

| Code | Description |
|------|-------------|
| `E001` | `project.yml` is missing required field |
| `E002` | Endpoint file is missing required field (`id`, `method`, `path`, or `variants`) |
| `E003` | Invalid `auth.type` value |
| `E004` | `auth.header_name` missing for `api-key` or `header` auth type |
| `E005` | Variant defines both `body` and `body_file` |
| `E006` | Invalid HTTP method |
| `E007` | Invalid HTTP status code (must be 100–599) |
| `E008` | GraphQL operation missing both `name` and `document` |
| `E009` | `operation.document` is empty after normalization |

#### Project-level errors (E1xx) — caught by project-level validator

| Code | Description |
|------|-------------|
| `E101` | `project.yml` not found at project root |
| `E102` | `endpoints/` directory is missing or empty |
| `E103` | Endpoint file failed to parse as YAML |
| `E104` | Duplicate endpoint `id` across project files |
| `E105` | Endpoint path conflicts with reserved route (`/health` or `/__admin/endpoints`) |
| `E106` | More than one variant has `default: true` |
| `E107` | `body_file` path does not exist in `fixtures/` |
| `E108` | Duplicate variant `name` within an endpoint |

#### Runtime warnings (W1xx) — non-fatal, logged by moqserver at startup

| Code | Description |
|------|-------------|
| `W101` | No variant is marked `default: true`; first variant will be used |
| `W102` | `packet_loss_percent` is 100; endpoint will never respond |
| `W103` | `X-Mock-Variant` header references an unknown variant name at runtime |

### Behaviour on error

- `moqserver validate` exits with code `1` if any `E` error is found, `0` otherwise.
- `moqserver serve` runs validation at startup; if any `E` error is found, the server exits with code `1` before binding the port.
- Studio prevents export if any `E` error is present and highlights the affected fields inline.
- `W` codes are informational and never block startup or export.

---

## Out of Scope (v1)

- WebSocket / streaming mocking
- gRPC / protobuf
- Proxy mode (intercept live traffic and selectively mock)
- Collaborative editing / multi-user
- Cloud hosting of Studio
- Response templating / dynamic values (e.g., `{{randomEmail}}`)
- Stateful mocking (e.g., POST creates a resource that GET then returns)
- Bandwidth throttling (beyond simple latency)
- OpenAPI validation of request/response bodies against schemas

These are all reasonable v2+ features. The v1 goal is: import, configure, export, serve.

---

## Future Considerations

For a later phase, moqserver may support spinning up a shared Docker-hosted mock server that can be used across multiple clients and CI/CD jobs. If that happens, the project will likely need a unique per-client or per-test session identifier so one server instance can safely isolate session data across concurrent test runs. This is intentionally not part of v1.
