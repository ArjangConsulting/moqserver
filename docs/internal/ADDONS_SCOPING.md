# Add-ons — scoping

Status: **accepted 2026-09-23**. Option A (compiled in-process Swift, Codable hook payloads).
Started from Novalingo's mocked UI-test work (the consumer requests are at the end).

## Decisions

| Q | Decision |
|---|---|
| Q1 | No H6 in v1. `{{baseURL}}` becomes a **core** feature (item f). |
| Q2 | `addons.*` is opaque JSON to Studio. Only `moq-format` / `moqserver validate` check it. |
| Q3 | An unknown add-on id is an **error** in both `validate` and `serve`. |
| Q4 | The bundle chooses its add-ons (`addons:` in `project.yml`). There is no server flag. |
| Q5 | `verify_signature: false` is allowed only when the server binds to loopback, unless `serve --allow-unverified-jwt` is passed. |
| Q6 | `oauth-mock` keeps mounting `/_auth/*` as a compatibility alias. |

Progress (2026-09-23): add-on API (`MoqAddonKit`, H1/H2/H5/H7/H8), `addons:` in the schema,
validator and writer, `jwt-claims`, and the `oauth-mock` migration are built — see
`docs/ADDONS.md`. `oauth-mock` is the one exception to Q4: it is always on and configured by the
server config, so existing `/_auth` users don't break. Core items a (`MoqClient` history
helpers), c (`serve --require-session`) and f (`{{baseURL}}`) are also built. Next: e, d, b.

Order: add-on API (`MoqAddonKit`, the `addons:` key and schema, H1/H2/H5/H7) → `jwt-claims` →
`oauth-mock` migration → core items a–f.

## Why now

The first concrete need is small: read the claims from an incoming bearer token, so tests can
assert *which* user a request belongs to and pick a variant from a claim (for example
`premium: true` → the `premium` variant). Novalingo is moving its mocked tests to the **Firebase
Auth emulator** for sign-in, and the emulator issues unsigned JWTs. Every such test then carries a
token whose claims describe the test account.

Adding JWT claims straight into core would work. But this is the second auth-flavoured feature
in the runtime (`AuthRouter`'s mock `/_auth/token` is the first), more will follow, and each one
makes `MockHandler.handleResolved` larger. So the question is whether to add a way to plug
features in first.

## What moqserver must not become

- **Not a Firebase tool.** No product-specific logic in core. The claims feature must work for
  any identity provider that issues JWTs.
- **Not a process supervisor.** Starting sidecars (the Firebase emulator, databases) belongs to
  the consumer's runner script. That's a few lines of shell, and in moqserver it would mean
  depending on Java and Node.
- **Not a scripting host in v1.** No embedded JS/Lua/WASM until an in-process Swift add-on API
  has proven which hooks are actually needed.

## Where hooks can go (today's request flow)

`MockHandler.handleResolved` already runs in fixed stages. These are the candidate hooks, in order:

| # | Stage today | Hook | Example add-on |
|---|---|---|---|
| H1 | App bootstrap (`buildApp`) | **Routes** under `/_addons/<id>/…` | Mock OAuth (`/_auth` today), emulator helpers |
| H2 | After session resolve, before auth | **Enrich request context** with read-only facts | JWT claims decoder |
| H3 | Auth validation | **Auth requirement evaluator** for new `auth.type` values | `jwt` with claim constraints |
| H4 | Request rules | **Rule matchers** for new match types | `claims.sub equals …` |
| H5 | Variant selection, `request_match` step | **Match predicates** contributed to `request_match` | `request_match.claims: {premium: true}` |
| H6 | Response build | **Response transform** (non-stream bodies first) | `{{baseURL}}` templating |
| H7 | Trace recording | **Trace annotations** | record `sub` on each history row |
| H8 | `moqserver validate` / format load | **Config + extension-field validation** | reject a bad claim path at load time |

Deliberately **not** hookable in v1:
- the precedence order of variant selection (header → runtime override → config → request_match
  → Accept → default). Add-ons may add predicates at H5, but may not change the order.
- call counting, sessions and the admin API. These are core state.

**Q1.** Is H6 needed in v1? `{{baseURL}}` is the only use case today, and it could be a core
feature instead.

## How an add-on is packaged — options

| Option | How it works | Pros | Cons |
|---|---|---|---|
| **A. Compiled in-process Swift** | `protocol MoqAddon` in a new `MoqAddonKit` target. Add-ons are targets in this repo. A bundle enables them by id | Typed, fast, no IPC; built and tested with the server | A third party has to fork or rebuild |
| B. Out-of-process over JSON-RPC stdio | Reuses the `moq-format` framing and handshake. An add-on is any executable | Any language; isolated if it crashes | Per-request IPC latency, lifecycle and versioning complexity |
| C. HTTP webhook | The server calls a URL at each hook | Trivial for the add-on author | Worst latency; tests become non-deterministic when the hook is down |
| D. Embedded WASM/JS | Sandboxed scripts | Portable, sandboxed | Heavy dependency; JavaScriptCore isn't available on Linux |

**Recommendation: A now, keeping B possible.** Design the hook payloads as plain `Codable`
values (no Vapor types cross the boundary), so B can later wrap the same protocol over the
existing JSON-RPC framing without changing the API.

Sketch (for shape only, not final):

```swift
public protocol MoqAddon: Sendable {
    static var id: String { get }                         // "jwt-claims"
    init(config: AddonConfig) throws                      // H8: validated at load
    func routes(_ r: AddonRouteBuilder)                   // H1 (default: none)
    func enrich(_ req: AddonRequest) async throws -> [String: AddonValue]      // H2
    func evaluateAuth(_ type: String, _ req: AddonRequest, facts: AddonFacts) -> AddonAuthOutcome?  // H3
    func match(_ key: String, _ spec: AddonValue, facts: AddonFacts) -> Bool?  // H4/H5, nil = not mine
    func transform(_ body: Data, _ ctx: AddonResponseContext) throws -> Data  // H6
}
```

Facts are namespaced by add-on id (`facts["jwt-claims"]`), are read-only after H2, and are
per-request. Any state an add-on keeps must be kept per session: the session's
`InMemoryMockStore` gives it a keyed slot, so parallel sessions stay isolated.

## Bundle format

```yaml
# project.yml
addons:
  jwt-claims:
    header: Authorization        # default
    verify_signature: false      # explicit opt-in; the emulator issues unsigned tokens
```

```yaml
# endpoints/user-access.yml
variants:
  - name: premium
    request_match:
      addons:
        jwt-claims: { claims: { premium: true } }
```

- Add-on config and extension fields live under an `addons:` key, keyed by add-on id. Core
  schema properties are never extended directly, so `additionalProperties: false` still holds.
- **Q2 — schema and Studio.** Studio generates its Kotlin models from `format/schema.json`.
  Either (a) `addons.*` is opaque JSON to Studio, with validation only through `moq-format`; or
  (b) each add-on ships a schema fragment that is merged at build time. Suggest (a) for v1.
- **Q3 — unknown add-on id.** Validation error (suggested) or a warning? It must be an error at
  `serve` time, or a bundle would silently lose behaviour.
- Is the add-on list selected per bundle (suggested: it travels with the bundle, so every
  consumer gets the same behaviour) or by a server flag? **Q4.**

## Proving the API: first add-ons

1. **`jwt-claims`** (new). H2 decodes the token and exposes its claims. H4/H5 match on claims.
   H7 records `sub`. Signature checking needs `verify_signature: false` to be set explicitly
   (only allowed on loopback?) — **Q5**. Later, JWKS URL verification when it is `true`.
2. **`oauth-mock`** (migration). Move `AuthRouter` (`/_auth/token`, `/_auth/authorize`) behind H1.
   If the API can't express the existing built-in feature cleanly, the hooks are wrong.
   **Q6:** keep `/_auth` as a compatibility alias?
3. *(Maybe)* **`templating`**: `{{baseURL}}` taken from the request's Host header (H6). Depends on Q1.

## Work breakdown (if accepted)

1. `MoqAddonKit` target: protocol, Codable hook types, registry, `addons:` loading and validation.
2. Wire H1/H2/H5/H7 into `MockHandler` and `buildApp`. H3/H4/H6 only when an add-on needs them.
3. Add the `addons` property to `format/schema.json`; `moq_validate_project` and MCP report
   add-on config errors.
4. `jwt-claims` add-on with tests: an unsigned emulator-style token, a claim match choosing a
   variant, isolation across parallel sessions.
5. Migrate `AuthRouter` to `oauth-mock`.
6. Docs: `docs/ADDONS.md` for authors; a section in `ADMIN_API`/`RUNTIME_WORKFLOWS` on facts in
   request history.

## Core requests from Novalingo (not add-ons)

These came out of the same review. They are core runtime/test-support gaps, listed so they get
scheduled alongside the add-on work. Novalingo pins `MoqTestSupport` at a commit SHA, and there
is no tagged release yet (deliberately deferred).

| # | Request | Why |
|---|---|---|
| a | `MoqClient.requests()` + `assertNoUnmatchedRequests()` helpers over `GET /_admin/requests` (session-scoped) | Consumers fail a test when the app hits an unmocked endpoint. Novalingo's missing `/balance` fixture was tolerated silently |
| b | Kotlin/JVM client (parity with `MoqTestSupport`: sessions, variants, scenarios, history) | Android can't drive error/retry scenarios today |
| c | `serve --require-session`: reject mock requests with no `X-Mock-Session` | An app client that forgets the header silently uses global state. Novalingo iOS adds the header by hand in 5 places |
| d | Scenarios declared in the bundle (not only via the admin API, in memory) | The docs currently tell consumers to re-load scenario JSON at test setup |
| e | Opt-in, size-limited body capture in session history | Assert what the app sent (e.g. AI request action/language) |
| f | `{{baseURL}}` in response bodies | Removes Novalingo's runtime-copy URL rewrite and the Android `10.0.2.2` variant. May be the H6 add-on, see Q1 |
