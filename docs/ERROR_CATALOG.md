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
| `missing_request_body` | `400` | The endpoint requires a request body but none was sent |
| `unsupported_content_type` | `415` | The request `Content-Type` does not match accepted types |

## Auth Errors

| Code | Status | Trigger |
|------|--------|---------|
| `unauthorized` | `401` | Missing or invalid bearer/basic/api-key/OAuth token |
| `forbidden` | `403` | Authenticated request lacks required OAuth scopes |

Auth failures also return `WWW-Authenticate` when applicable.

## Routing and Variant Errors

| Code | Status | Trigger |
|------|--------|---------|
| `endpoint_not_found` | `404` | No registered mock endpoint matches the request method/path |
| `variant_not_found` | `404` | No response variant matches the requested variant or request conditions |

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
