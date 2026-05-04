# Format Implementation

## Purpose

This document defines how `.moqproj` should be represented, loaded, validated, and written by both Studio and moqserver.

## Core Rule

The project format is the contract.

- Studio authors it
- moqserver executes it
- AI may propose edits to it
- neither runtime nor Studio should rely on hidden side channels to understand project behavior

## Directory Structure

Canonical structure:

```text
project-name.moqproj/
├── project.yml
├── endpoints/
│   └── *.yml
└── fixtures/
    └── *
```

Rules:

- `project.yml` is required
- `endpoints/` is required
- `fixtures/` is optional but expected in most real projects
- endpoint files should be independent and readable in isolation

## Domain Model

The format should be represented in code as explicit domain types, not loose maps.

Required top-level types (Swift names in `MoqCore/Project/`):

- `ProjectManifest` — top-level project metadata
- `ProjectDefaults` — default auth, delay, network config applied to all endpoints
- `EndpointDocument` — a single endpoint file with method, path, variants, etc.
- `EndpointOperation` — GraphQL operation matching config (type, name, document)
- `RequestRules` — required headers, query params, cookie verification config
- `ProjectVariant` — a single response variant (name, status, body/body_file, headers, delay_ms)
- `ProjectAuthConfig` — per-endpoint auth config (type, verify, header_name)
- `NetworkBehavior` — network simulation settings (latency_ms, jitter_ms, packet_loss_percent)

Note: `body_file` references within `ProjectVariant` are plain `String` fields (relative paths into `fixtures/`); there is no separate `FixtureReference` type. The runtime auth domain type `AuthRequirement` is distinct from `ProjectAuthConfig` — the former is the internal representation after conversion, the latter is the on-disk format model.

## Serialization Rules

### YAML loading

- parse YAML into raw structures
- convert raw structures into typed domain objects
- reject ambiguous or invalid shapes with explicit diagnostics

### YAML writing

- write from typed domain objects only
- do not attempt comment-preserving round-trips in v1
- output should be stable across repeated saves when domain state is unchanged

## Deterministic Export Rules

Export must be deterministic.

That means:

- consistent file naming
- consistent key ordering
- consistent list ordering where ordering is semantically unimportant
- normalized scalar styles where practical

Recommended ordering:

1. `project.yml`
2. endpoint files sorted by endpoint id
3. fixtures sorted by relative path

Recommended key ordering in endpoint files:

1. `id`
2. `alias`
3. `description`
4. `method`
5. `path`
6. `tags`
7. `auth`
8. `reference_name`
9. `request_rules`
10. `operation`
11. `network`
12. `variants`

## Fixtures

Fixture rules:

- large JSON bodies may be externalized
- externalized bodies must use relative paths rooted inside `fixtures/`
- runtime and Studio must reject fixture paths that escape the project directory
- a body may be inline or external, but not both in the same variant

## Validation Split

Validation is split into two layers.

### Schema or shape validation

Used for:

- required fields
- field types
- enums
- obvious structural constraints

### Semantic validation

Used for:

- duplicate endpoint ids
- duplicate default variants
- missing fixture files
- invalid fixture references
- contradictory operation matching
- project-wide naming conflicts

## GraphQL Matching Rules

GraphQL endpoints are matched by:

1. operation name when provided
2. normalized operation document when operation name is absent

Normalization must ignore insignificant formatting differences.

## Load/Save Ownership

Studio and runtime should not each invent their own interpretation of the format.

Preferred ownership model:

- Swift runtime owns authoritative execution semantics
- shared documentation defines the contract
- Studio implements the same contract using equivalent domain rules
- cross-language golden tests verify both sides agree

## Acceptance Criteria

The format implementation is ready when:

1. sample projects round-trip cleanly
2. deterministic save tests pass
3. runtime behavior matches exported content without hidden defaults
4. validation errors identify exact file and field context
