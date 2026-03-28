# Test Strategy

## Purpose

This document defines how moqserver and Studio should be validated as the system evolves.

## Testing Layers

## Layer 1: Domain and Format Unit Tests

Focus:

- typed project models
- validation rules
- fixture reference handling
- GraphQL matching normalization helpers

Expected style:

- fast
- deterministic
- no network

## Layer 2: Golden Project Tests

Focus:

- load known `.moqproj` fixtures
- save them back out
- compare against expected normalized output

Goal:

- ensure deterministic export behavior

## Layer 3: Runtime Behavior Tests

Focus:

- endpoint matching
- auth behavior
- variant selection priority
- GraphQL operation handling
- request rule enforcement

Goal:

- runtime must behave exactly as the format describes

## Layer 4: Import Pipeline Tests

Focus:

- OpenAPI import conversion
- HAR import grouping and normalization
- sensitive data redaction where required

Goal:

- imported projects should be reproducible and editable

## Layer 5: Companion Contract Tests

Focus:

- DTO compatibility
- provider list behavior
- config validation behavior
- error normalization
- redaction pipeline behavior

Goal:

- Studio and companion must agree on the same shapes and failure semantics

## Layer 6: Studio Use-Case Tests

Focus:

- open/save flows
- selection and dirty-state behavior
- validation state propagation
- editor integration boundaries

Goal:

- ensure deterministic authoring workflows before UI polish dominates

## Layer 7: End-to-End Smoke Tests

Focus:

- import or open project
- save/export project
- run through moqserver
- optionally invoke a companion endpoint in a mocked environment

Goal:

- validate the full authoring-to-runtime loop

## Test Fixtures

Recommended fixture groups:

- minimal valid projects
- invalid projects with known diagnostics
- GraphQL projects
- fixture-heavy projects
- imported OpenAPI samples
- imported HAR samples

## Determinism Requirements

Tests must verify:

- stable file ordering
- stable YAML output
- stable fixture references
- stable error codes and diagnostic locations where practical

## AI Testing Principles

Do not make real hosted provider calls part of normal automated tests.

Instead:

- mock provider responses at the companion boundary
- test redaction logic independently
- test result normalization independently
- keep provider integration smoke tests optional and manually controlled

## Release Gates

Before implementation is considered ready for broader testing:

1. format tests pass
2. runtime tests pass
3. Studio use-case tests pass for the current slice
4. companion contract tests pass when companion work is present
