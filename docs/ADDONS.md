# Add-ons

Add-ons are features compiled into moqserver that a bundle turns on in `project.yml`. They keep
identity-provider or product-specific behaviour out of the core runtime. Design notes and the
decisions behind this page are in [internal/ADDONS_SCOPING.md](internal/ADDONS_SCOPING.md).

## Enabling an add-on

```yaml
# project.yml
addons:
  jwt-claims:
    verify_signature: false
```

- The bundle decides which add-ons run; there is no server flag. Every consumer of the bundle
  gets the same behaviour.
- An add-on id this build doesn't have is an error (`E_UNKNOWN_ADDON`), in both
  `moqserver validate` and `serve`.
- Each add-on checks its own config (`E_INVALID_ADDON_CONFIG`). Studio treats `addons` as opaque
  and saves it back unchanged.

A variant can match on facts an add-on computes, under `request_match.addons.<id>`:

```yaml
# endpoints/user-access.yml
variants:
  - name: free
    default: true
    status: 200
  - name: premium
    status: 200
    request_match:
      addons:
        jwt-claims: { claims: { premium: true } }
```

The add-on must be enabled in `project.yml` (`E_ADDON_NOT_ENABLED`), and the spec is checked by
the add-on (`E_INVALID_ADDON_MATCH`). Add-on predicates combine with `query`, `headers`, and
`body_contains` like any other `request_match` field: all must hold. They add a predicate, but
they don't change the variant-selection order.

Add-on routes live under `/_addons/<id>/…`. That path is reserved for mock endpoints.

## Built-in add-ons

### `jwt-claims`

Decodes the bearer token on each request, so variants can be chosen by claim and request history
records who made each call. It works with any identity provider that issues JWTs. It was built for
the Firebase Auth emulator, which issues unsigned tokens.

| Key | Default | Meaning |
|---|---|---|
| `verify_signature` | *(required)* | Must be `false`. Tokens are decoded without checking the signature. Verification (JWKS) is not implemented yet. |
| `header` | `Authorization` | Header that carries the token. |
| `scheme` | `Bearer` | Scheme prefix (case-insensitive). `""` reads the raw header value. |
| `trace_claims` | `[sub]` | Scalar claims recorded on each request-history row. |

**Matching.** `claims` is a subset match. Every listed claim must be present and equal, and
nested mappings are matched the same way, so
`claims: { firebase: { sign_in_provider: password } }` works. Arrays must be equal, and numbers
compare numerically. A request with no token, or with a token that doesn't decode, never matches.

**History.** Rows get `"addons": {"jwt-claims": {"sub": "…"}}`. Unmatched (404) requests are
annotated too.

**Exposure.** An unverified token can be forged by anyone who can reach the server, so `serve`
refuses to start this add-on unless it binds to loopback (`127.0.0.1`, `localhost`, `::1`). Pass
`--allow-unverified-jwt` to accept that exposure. Android emulators don't need the flag:
`10.0.2.2` routes to the host's `127.0.0.1`, so a loopback-bound server is reachable. The flag
is only for physical devices, remote hosts, or setups that bind `0.0.0.0` (e.g. Docker).

## Writing an add-on

Add-ons are Swift types conforming to `MoqAddon` (`server/Sources/MoqAddonKit`), registered in
`AddonCatalog.builtIn` (`server/Sources/MoqAddons/BuiltInAddons.swift`). Hook payloads are plain
`Codable` values with no Vapor types, so an out-of-process transport can wrap the same protocol
later.

| Hook | Member | When |
|---|---|---|
| H1 | `routes` | Mounted under `/_addons/<id>/` at startup |
| H2 | `enrich(_:)` | Once per request, after the session is resolved and before auth |
| H5 | `matches(_:facts:)` | For each variant's `request_match.addons.<id>` |
| H7 | `traceAnnotations(facts:)` | When the request-history row is written |
| H8 | `validateConfig(_:)`, `validateMatch(_:)` | `validate`, `moq-mcp`, `moq-format`, and `serve` startup |

`init(config:environment:)` may throw `AddonActivationError` to stop `serve`, for example when the
bind address isn't safe for the add-on. Facts are per request and read-only after H2. An add-on
must not keep state across requests unless it keeps it per mock session.
