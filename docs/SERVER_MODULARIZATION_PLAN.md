# Server Modularization Plan

## Current State

The Swift package is currently centered on a single main library target plus a thin executable target.

That is workable for a small runtime, but it is the wrong long-term shape for:

- `.moqproj` support
- import pipelines
- shared domain logic

## Target State

Recommended target layout:

1. `MoqCore`
2. `MoqFormat`
3. `MoqParsing`
4. `MoqRuntime`
5. `MoqCLI`
6. `Run`

## Responsibilities

### `MoqCore`

- shared domain types
- endpoint keys
- auth requirements
- network behavior models
- validation diagnostics model

### `MoqFormat`

- `.moqproj` manifest and endpoint loading
- fixture reference handling
- semantic validation
- project export helpers if needed by shared tooling

### `MoqParsing`

- OpenAPI parser
- HAR parser
- import-specific normalization helpers

### `MoqRuntime`

- Vapor app bootstrap
- routing
- request matching
- variant selection
- admin/runtime endpoints

### `MoqCLI`

- command definitions
- wiring for serve, validate, and import-related modes

### `Run`

- executable entry point only

## Dependency Direction

Recommended direction:

- `MoqFormat` depends on `MoqCore`
- `MoqParsing` depends on `MoqCore`
- `MoqRuntime` depends on `MoqCore` and `MoqFormat`
- `MoqCLI` depends on `MoqRuntime`, `MoqParsing`, and `MoqFormat`
- `Run` depends on `MoqCLI`

Forbidden direction:

- `MoqCore` depending on anything above it
- runtime directly owning parser concerns
- CLI logic leaking into runtime internals

## Migration Order

1. Extract `MoqCore`
2. Extract `MoqFormat`
3. Move runtime-only code into `MoqRuntime`
4. Move command code into `MoqCLI`
5. Extract `MoqParsing`

This order minimizes risk because it separates domain and format first, before import complexity is introduced.

## Test Migration Rules

- keep existing tests passing throughout the split
- move tests alongside the target they validate where reasonable
- add new tests for target boundaries when behavior moves

## Acceptance Criteria

The modularization is complete when:

1. targets have clear dependency direction
2. existing behavior is preserved
3. `.moqproj` support has a clear home
4. future Studio-facing authoring concerns do not need to live in the runtime target
