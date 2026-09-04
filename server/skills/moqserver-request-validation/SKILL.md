---
name: moqserver-request-validation
description: Use request_rules and global_rules to assert what a client sends, and avoid the bundle-wide-auth trap that breaks endpoints which must stay unauthenticated.
---

# Asserting what the client sends

`request_rules` (per endpoint, in `endpoints/<id>.yml`) and `global_rules` (bundle-wide, in
`project.yml`'s manifest) both hold a list of `RuleMatcher`s: `match_type` plus a header/query
name and, depending on `match_type`, an expected value. A request that fails a matching rule gets
a structured rejection instead of a mocked response — this is how you assert "the client actually
sent X," not just "the server can return X."

Relevant `match_type` values include exact/contains-style matches and **`is_empty`** — the one
that matters most for the trap below, since it asserts a header or query parameter is *absent or
blank*, not present with some value.

## The trap: a bundle-wide auth rule breaks endpoints that must stay unauthenticated

`global_rules` and endpoint-level `auth` both look like the right place to put "requests must
carry `Authorization`" once, for the whole bundle. They're wrong whenever *any* endpoint in the
bundle must never receive a token — a public/anonymous endpoint, a pre-login screen, a
webhook receiver. A single global rule requiring `Authorization` rejects legitimate calls to those
endpoints, and from the outside it looks like a codegen or client bug, not a mock-config problem.

Fix: put the auth requirement on `request_rules` **per endpoint** that actually needs it, and — for
the endpoints that must *never* see a token — add an explicit `is_empty` rule on `Authorization`
there. That makes "this endpoint must not be authenticated" a checked, visible assertion instead
of an accidental side effect of what the bundle-wide rule didn't say.

## `auth.type` is a different, complementary mechanism

Per-endpoint `auth.type` (`none`/`api-key`/`header`/`bearer`/...) simulates checking credentials
against configured valid values (`docs/API_GUIDE.md` §7) and returns a 401/403-shaped response on
failure. `request_rules`/`global_rules` validate arbitrary parts of the request (not just auth) and
are a separate, additive check. Use `auth.type` for "does the server accept this credential," and
`request_rules` for "did the client actually send (or not send) this."

## See also

- `moqserver-authoring` — where `request_rules`/`auth` live in `moq_upsert_endpoint`'s input.
- `moqserver-scenario-design` — this is about validating input, not about response selection.
