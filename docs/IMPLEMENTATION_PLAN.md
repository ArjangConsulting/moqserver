# moqserver Implementation Plan

## Purpose

This document is the execution roadmap for evolving moqserver from a single Swift mock server into a two-part system:

1. `moqserver` runtime and local companion in Swift/Vapor
2. `moqserver studio` desktop authoring tool in Compose Multiplatform

Product and architecture context lives in:

- `docs/PROJECT_SPEC.md`
- `docs/STUDIO_ARCHITECTURE.md`
- `docs/STUDIO_IMPLEMENTATION_PLAN.md`
- `docs/COMPANION_API.md`

## Delivery Principles

1. Build deterministic authoring before AI-assisted authoring.
2. Validate the `.moqproj` format before expanding feature breadth.
3. Prefer thin vertical slices over broad framework work.
4. Keep runtime behavior model-independent and AI-free.
5. Treat the local companion as a trust boundary, not an application brain.

---

## Phase 0: Documentation and Planning — COMPLETE

- [x] `docs/V1_SCOPE.md`
- [x] `docs/FORMAT_IMPLEMENTATION.md`
- [x] `docs/STUDIO_IMPLEMENTATION_PLAN.md`
- [x] `docs/COMPANION_API.md`
- [x] `docs/SERVER_MODULARIZATION_PLAN.md`
- [x] `docs/TEST_STRATEGY.md`
- [x] `docs/PROJECT_SPEC.md`
- [x] `format/schema.json` (JSON Schema for .moqproj)

---

## Phase 1: Format Foundation — COMPLETE

- [x] Domain models: ProjectManifest, EndpointDocument, ProjectVariant, AuthRequirement, NetworkBehavior, RequestRules, EndpointOperation, RuleMatcher, MoqProject
- [x] YAML load rules (ProjectLoader)
- [x] YAML write rules (ProjectWriter) with deterministic key ordering
- [x] Fixture extraction and referencing (body_file → fixtures/)
- [x] Deterministic export ordering (sorted by endpoint ID)
- [x] Schema-level shape validation (ProjectValidator)
- [x] Project-wide semantic validation (duplicate IDs, reserved paths, variant conflicts)
- [x] Round-trip tests pass (load → save → reload identical)
- [x] Sample project fixture: `format/examples/sample-app.moqproj`

---

## Phase 2: Server Modularization — COMPLETE

- [x] MoqCore — domain types, protocols, validation (zero dependencies)
- [x] MoqFormat — .moqproj loading, writing, validation (depends: MoqCore, Yams)
- [x] MoqParsing — OpenAPI/HAR parsers, spec validation (depends: MoqCore, OpenAPIKit, Yams)
- [x] MoqRuntime — Vapor routing, handlers, storage (depends: MoqCore, Vapor, Yams)
- [x] MoqCLI — CLI commands and composition root (depends: all above)
- [x] Run — executable entry point
- [x] `swift build` succeeds
- [x] `swift test` passes (117 tests across 17 suites)
- [x] Dependency graph matches documented direction

---

## Phase 3: Studio Deterministic Authoring Slice — IN PROGRESS

Goal: Ship the first useful desktop workflow without AI. A user can open, browse, edit, and save a `.moqproj` project.

### 3.0 Scaffolding — DONE

- [x] Compose Multiplatform project structure (`studio/`)
- [x] `composeApp` — entry point, window, navigation shell
- [x] `studio-domain` — StudioRootViewModel with StateFlow
- [x] `studio-data` — YamlProjectCodec (SnakeYAML), LocalCompanionClient (Ktor)
- [x] `studio-code-editor` — JsonCodeEditor (RSyntaxTextArea/Swing interop)
- [x] MaterialTheme + NavigationBar with Dashboard/Project routes

### 3.1 Domain Models (studio-domain) — DONE

Port the .moqproj domain into Kotlin data classes that Studio operates on.

- [x] `MoqProject` data class (manifest + endpoints + projectPath)
- [x] `ProjectManifest` data class (version, name, description, defaults, globalRules)
- [x] `EndpointDocument` data class (id, method, path, auth, variants, requestRules, network, operation)
- [x] `ProjectVariant` data class (name, isDefault, status, headers, body, bodyFile, delayMs)
- [x] `ProjectDefaults` data class (delayMs, auth, network)
- [x] `ProjectAuthConfig` data class (type, headerName) + `AuthType` enum
- [x] `NetworkBehavior` data class (latencyMs, jitterMs, packetLossPercent)
- [x] `RequestRules` data class (headers, verifyCookies, queryParams)
- [x] `EndpointOperation` data class (type, name, document) + `OperationType` enum
- [x] `YamlValue` sealed class (type-erased value, equivalent to Swift AnyCodableValue)

### 3.2 YAML Load/Save (studio-data) — DONE

Wire YamlProjectCodec to actually read/write .moqproj directories.

- [x] Load `project.yml` from .moqproj directory → `ProjectManifest`
- [x] Load `endpoints/*.yml` → list of `EndpointDocument`
- [x] Load `fixtures/*` file references (resolve body_file paths)
- [x] Assemble `MoqProject` from loaded components
- [x] Save `MoqProject` → `project.yml` + `endpoints/*.yml` + `fixtures/`
- [x] Deterministic key ordering matching Swift ProjectWriter output
- [x] Round-trip test: load sample-app.moqproj, save, reload, verify data matches
- [x] `ProjectRepository` class wrapping codec + file I/O

### 3.3 Project Open/Save (Slice 1) — DONE

- [x] File chooser dialog to select .moqproj directory
- [x] Load project on selection → populate ViewModel state
- [x] Display project name, description, endpoint count on Dashboard
- [x] Dirty-state tracking (flag when any edit changes project state)
- [x] Save action writes project back to disk
- [x] Save-as action to new directory
- [x] Unsaved changes warning on close
- [x] Recent projects list on Dashboard (in-memory)

### 3.4 Endpoint Browser (Slice 2) — DONE

- [x] Left sidebar: endpoint list grouped by first path segment
- [x] Each entry shows: method badge (GET/POST/etc), path, variant count
- [x] Click endpoint → show detail in right pane
- [x] Search/filter bar for endpoints
- [x] Selection state persists in ViewModel
- [x] Empty state when no project is loaded

### 3.5 Variant Editing (Slice 3) — DONE

- [x] Endpoint detail pane: structured form for metadata
  - [x] Method dropdown, path text field
  - [x] Auth config toggle with type dropdown, header name, verify switch
  - [x] Network behavior (latency, jitter, packet loss) fields
- [x] Variant tabs in detail pane: name, default badge
- [x] Add/remove variants
- [x] Variant detail: name, status code, headers key-value editor, delay field
- [x] Read-only inline body display (JsonCodeEditor integration deferred to next iteration)
- [x] body_file reference field
- [x] Changes flow through ViewModel → dirty state

### 3.6 Validation Panel (Slice 4) — DONE

- [x] Port ProjectValidator rules to Kotlin (full port, all rules from Swift)
- [x] Validation panel showing errors/warnings with severity badges
- [x] Click diagnostic → navigate to the offending endpoint
- [x] Auto-validate on every project change (block save if errors)
- [x] Summary bar with error/warning counts

### Phase 3 Exit Criteria

- [x] sample-app.moqproj can be opened, browsed, edited, and saved
- [x] saved output uses deterministic key ordering matching Swift ProjectWriter
- [x] editor and form changes flow through a single source of truth (ViewModel)

---

## Phase 4: Import Workflows — BACKEND COMPLETE, UI PARTIALLY COMPLETE

### 4.0 Backend — DONE

- [x] OpenAPIParser — 3.0.x and 3.1.x, JSON + YAML, multi-content-type variants
- [x] OpenAPISpecValidator — compliance and mock-readiness warnings
- [x] HARParser — HTTP Archive 1.2 format
- [x] EndpointConverter — ParsedSpec → runtime Endpoint models
- [x] Content negotiation — Accept header variant selection
- [x] Non-JSON content generation — XML, HTML, CSV, SVG, PDF, PNG stubs

### 4.1 OpenAPI Import in Studio

- [x] Import dialog: file picker for OpenAPI spec (YAML/JSON)
- [x] Parse spec → show import summary (endpoint count, warnings)
- [x] Map ParsedSpec → Studio domain models (MoqProject)
- [x] Review screen: endpoint list with accept/reject per endpoint
- [x] Create .moqproj from accepted endpoints
- [x] Open imported project in editor

### 4.2 HAR Import in Studio

- [x] Import dialog: file picker for .har files
- [x] Parse HAR → show import summary (request count, grouped endpoints)
- [ ] Path normalization UI (group similar paths, parameterize IDs)
- [ ] Sensitive data redaction preview (tokens, cookies, keys)
- [x] Review screen with accept/reject per endpoint
- [x] Create .moqproj from accepted endpoints
- [x] Open imported project in editor

### Phase 4 Exit Criteria

- [x] imported projects are editable and exportable
- [ ] common import noise is normalized predictably

---

## Phase 5: Companion and AI Integration — COMPLETE

### 5.1 Companion Server (Swift/Vapor)

- [x] Create `MoqCompanionAI` target in Package.swift
- [x] `GET /health` — companion reachability check
- [x] `GET /ai/providers` — provider discovery and capabilities
- [x] `POST /ai/validate-config` — validate provider settings
- [x] `POST /ai/analyze-spec` — analyze spec/project, return structured findings
- [x] `POST /ai/generate-variants` — generate draft variants for endpoints
- [x] `POST /ai/refine-project` — propose structural improvements
- [x] Common request/response envelope DTOs (per COMPANION_API.md)
- [x] Error model with standard codes (provider_unavailable, provider_auth_invalid, etc.)
- [x] `companion` CLI subcommand to start the companion on port 8081

### 5.2 Provider Abstraction

- [x] Provider protocol/interface for AI backends
- [x] Ollama local provider implementation
- [x] OpenAI hosted provider implementation
- [x] Anthropic hosted provider implementation
- [x] Provider config loading from disk/env
- [x] Provider capability reporting

### 5.3 Redaction Engine

- [x] Redaction policy rules (per COMPANION_API.md)
- [x] Strip bearer tokens, API keys, cookies from payloads
- [x] Preserve structural usefulness while masking values
- [x] Redaction summary in response metadata
- [x] Logging policy enforcement (no raw secrets in logs)

### 5.4 Studio Companion Integration — DONE

- [x] Wire LocalCompanionClient to real companion endpoints
- [x] Provider settings screen (list providers, status, configure)
- [x] AI action entry points in endpoint/variant editor
- [x] Preview/accept/reject flow for AI-generated changes
- [x] Error handling: companion unreachable, provider unavailable

### Phase 5 Exit Criteria

- [x] Studio can discover providers from the local companion
- [x] AI results return structured drafts, not freeform text
- [x] hosted calls respect redaction and secret handling policy

---

## Phase 6: Hardening and Packaging — COMPLETE

- [x] Tighten validation and error reporting across all layers
- [x] Studio packaging: macOS .app bundle (DMG), Linux deb, Windows MSI
- [x] Installer metadata and signing (macOS code signing + notarization via env vars)
- [x] Operational docs for companion mode
- [x] Release checklist and smoke tests
- [x] End-to-end test: import → edit → save → serve → verify responses

### Phase 6 Exit Criteria

- [x] Studio packages for current OS (`make studio-package`)
- [x] server runtime and companion are documented and testable locally

---

## Deferred Until After v1

- collaborative editing
- cloud sync
- browser-based Studio
- advanced team gateway auth flows
- comment-preserving YAML round-trips
- full provider feature parity across every AI backend
- plugin ecosystem or extension API

---

## Quick Reference: What's Next

**All phases complete.** See "Deferred Until After v1" above for post-v1 items.
