---
name: moqserver-scenario-design
description: Choose between call_count sequencing and runtime variant selection when designing a moqserver test scenario, and understand why the wrong choice bleeds across tests.
---

# Designing a mock scenario

A scenario is "make this endpoint behave a particular way for this test." moqserver gives you two
different mechanisms for that, and they are not interchangeable.

## `call_count` — baked into the bundle, not test-scoped

A variant with `call_count: N` is only eligible on the Nth call to its endpoint, in the order
calls actually arrive. This is part of the **bundle**, not of any one test run.

The trap: "make the first call fail, then succeed" via `call_count: 1` on an error variant changes
the happy path for **every test in the run** that hits that endpoint, not just the test that wants
the failure — because "the first call" is whichever test's request lands first, and every other
test still sees a variant sequence it didn't ask for. `strict_call_count: true` on the endpoint
makes this worse to get wrong silently: it rejects any call with no exact `call_count` match
(`409 call_count_exceeded`) instead of falling back to normal selection, so an unaccounted-for call
from an unrelated test fails outright.

Use `call_count` for a genuinely sequence-dependent behavior that's true of the endpoint itself
(e.g., "the third poll of a job always returns done" as a fixed property of the mock), not as a
way to inject a one-off failure for a single test.

## Runtime variant selection — scoped to one test

Use the admin API (`docs/ADMIN_API.md`) to switch an endpoint's active variant, or reset its call
count, from the **test side** — before or during the test that needs the special behavior, and
reset it afterward (or let the next test's setup reset it). This is what actually gives you
test-local scenarios: no other test observes the change.

This has to go through the admin API and not the `X-Mock-Variant` header, because the header is
sent by the **app under test**, which the test code doesn't control. See
`moqserver-serving-for-tests` for wiring a test-control helper against the admin API.

## Decision rule

- "This test needs endpoint X to return an error, just for this test" → runtime variant selection.
- "This endpoint's Nth call is always different, as a property of the real API it mocks" →
  `call_count` (and only set `strict_call_count` if you actually want unaccounted calls to fail
  loudly rather than fall back).

## See also

- `moqserver-authoring` — setting `call_count`/`strict_call_count` via `moq_upsert_variant`.
- `moqserver-serving-for-tests` — driving the admin API from a UI test.
