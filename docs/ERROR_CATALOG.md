# Error Catalog

`moqserver` returns structured JSON errors in this shape:

```json
{
  "error": "Human readable message",
  "code": "stable_machine_code",
  "detail": "Optional extra context",
  "hint": "Optional remediation hint"
}
```

## Request Validation Errors

| Code | Status | Trigger |
|------|--------|---------|
| `missing_required_header` | `400` | A required header rule is missing or empty |
| `header_value_mismatch` | `400` | A header value fails its `match_type` rule |
| `missing_required_query_param` | `400` | A required query parameter is missing or empty |
| `query_param_value_mismatch` | `400` | A query parameter value fails its `match_type` rule |
| `missing_required_cookie` | `400` | Cookie verification is enabled but a required cookie is missing |
| `cookie_value_mismatch` | `400` | A cookie value fails its `match_type` rule |
| `missing_request_body` | `400` | `request_rules.requires_body: true` and the request has no body |
| `unsupported_content_type` | `415` | The request has a body whose `Content-Type` is not in `request_rules.accepted_content_types` |

A rule with `required: false` passes when its value is absent and is enforced only when present.
`not_equal_to` and `not_contains` also pass on an absent value unless the rule sets `required: true`.

## Auth Errors

| Code | Status | Trigger |
|------|--------|---------|
| `unauthorized` | `401` | Missing or invalid bearer/basic/api-key/OAuth token |
| `forbidden` | `403` | Authenticated request lacks required OAuth scopes |

Auth failures also return `WWW-Authenticate` when applicable.

## Routing and Variant Errors

| Code | Status | Trigger |
|------|--------|---------|
| `endpoint_not_found` | `404` | No registered mock endpoint matches the request path |
| `method_not_allowed` | `405` | The path is mocked, but not for this method; the `Allow` header lists the methods that are |
| `variant_not_found` | `404` | No response variant matches the requested variant or request conditions |
| `variant_not_found` | `406` | The request sent `Accept` and no named variant, and no variant's `Content-Type` is acceptable. Variants with no body or no `Content-Type` are acceptable under any `Accept` the client didn't exclude with `q=0` |
| `call_count_exceeded` | `409` | `strict_call_count: true` and no variant declares this call's `call_count` |
| `payload_too_large` | `413` | The request body exceeds `serve --max-body-size` (default 10 MiB) |
| `session_required` | `428` | `serve --require-session` is set and the request has no `X-Mock-Session` header |
| `not_found` | `404` | `X-Mock-Session` names a session that doesn't exist, was deleted, or expired after `--session-idle-timeout` |

A known path also answers `OPTIONS` with `204` and an `Allow` header, and `HEAD` through its `GET` endpoint.

## Admin API Errors

Admin errors use the same shape. `code` is derived from the HTTP status:

| Code | Status | Trigger |
|------|--------|---------|
| `bad_request` | `400` | Unknown variant, invalid scenario name, or a scenario override naming an unknown endpoint key or variant |
| `unauthorized` | `401` | The config has an `admin` section and the request lacks its credentials |
| `not_found` | `404` | Unknown endpoint, scenario, or session. For a GraphQL route the hint lists the `?operation=` selectors |
| `conflict` | `409` | Scenario or session capacity reached (100 scenarios, 64 sessions); list sessions with `GET /_admin/sessions` and delete unused ones |
| `unprocessable_entity` | `422` | `POST /_admin/reload` found the bundle invalid; the previous version keeps serving |
| `not_implemented` | `501` | `POST /_admin/reload` on a server not started from a project path |

Any other status a request is aborted with reports its reason phrase in snake_case (for example `gone`).

## Network Simulation Errors

| Code | Status | Trigger |
|------|--------|---------|
| `simulated_packet_loss` | `503` | Endpoint `network.packet_loss_percent` randomly dropped the request |

## Internal Errors

| Code | Status | Trigger |
|------|--------|---------|
| `internal_error` | `500` | Unhandled server exception |

## Notes

- `.moqproj` request rules now support `match_type` for headers, query params, and cookies.
- Supported `match_type` values are: `require`, `equal_to`, `not_equal_to`, `contains`, `not_contains`, `begins_with`, `ends_with`, `matches_regex`, `is_empty`, `not_empty`, `gt`, `gte`, `lt`, `lte`.
- `.moqproj` network simulation now applies endpoint latency, jitter, and packet loss during serving.

## Authoring and format-service recovery

| Code | Recovery |
|---|---|
| `E_PROJECT_IS_WORKING_DIRECTORY` | `moq-author` only: a command that saves was run from inside the bundle directory, which a save replaces. Run it from outside and pass the bundle path. |
| `E_PROJECT_BUSY` | Another cooperating process holds the bundle lock; retry after it finishes. |
| `E_PROJECT_CHANGED` | Disk content changed or the loaded bundle was deleted; reload or preserve edits with Save As. |
| `E_INVALID_ARGUMENTS` | Correct CLI flags; moq-author exits 64 with a JSON error on stderr. |
| `E_FORMAT_INCOMPATIBLE` | Studio requires its matching format protocol and capabilities; update the binary. |
| `E_UNKNOWN_ADDON` | `addons:` or `request_match.addons` names an add-on this build doesn't have; remove it or use a build that includes it. |
| `E_INVALID_SCENARIO` | A `scenarios:` entry has a bad name or no selections. |
| `E_SCENARIO_UNKNOWN_ENDPOINT` | A scenario names an endpoint id the bundle doesn't have. |
| `E_SCENARIO_UNKNOWN_VARIANT` | A scenario selects a variant (by name or reference_name) the endpoint doesn't have. |
| `E_INVALID_REGEX` | A `matches_regex` rule has no `match` pattern, or the pattern doesn't compile. Without this check every request would get a 400. |
| `E_INVALID_ADDON_ID` | Add-on ids are lowercase alphanumeric with hyphens. |
| `E_INVALID_ADDON_CONFIG` | Fix the add-on's config at the reported field (see [ADDONS.md](ADDONS.md)). |
| `E_ADDON_NOT_ENABLED` | A variant matches on an add-on that `project.yml` doesn't enable; add it under `addons:`. |
| `E_INVALID_ADDON_MATCH` | Fix the add-on's `request_match` spec at the reported field. |
| `E_FORMAT_UNAVAILABLE` | Retry the format service from Studio's Tools menu. |

`moq-author` suppresses routine library logs so failures are one parseable JSON document on stderr.
