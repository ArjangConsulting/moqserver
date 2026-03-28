# Companion API

## Purpose

This document defines the local Studio-to-companion API.

The companion is a localhost Swift/Vapor process that:

- reads local provider configuration
- applies redaction policy
- forwards structured AI requests
- normalizes provider responses

## Constraints

- localhost only
- no browser-facing public API assumptions
- bounded request sizes
- structured payloads over chat transcripts

## Endpoint Set

## `GET /health`

Purpose:

- confirm the companion is reachable

Response:

```json
{
  "status": "ok",
  "version": "1.0.0"
}
```

## `GET /ai/providers`

Purpose:

- return provider availability and capability metadata

Response shape:

```json
{
  "providers": [
    {
      "id": "ollama",
      "displayName": "Ollama",
      "kind": "local",
      "available": true,
      "capabilities": ["analyze-spec", "generate-variants", "refine-project"]
    }
  ]
}
```

## `POST /ai/validate-config`

Purpose:

- validate provider settings before a user runs an AI workflow

Request shape:

```json
{
  "providerId": "openai"
}
```

Response shape:

```json
{
  "valid": true,
  "issues": []
}
```

## `POST /ai/analyze-spec`

Purpose:

- analyze imported or saved project/spec context

Response expectation:

- structured findings with actionable suggestions

## `POST /ai/generate-variants`

Purpose:

- generate draft variants for specific endpoints or endpoint groups

## `POST /ai/refine-project`

Purpose:

- propose structural improvements such as alias cleanup, grouping, and fixture extraction candidates

## Common Request Envelope

All AI action endpoints should use a common envelope:

```json
{
  "providerId": "ollama",
  "projectContext": {},
  "selection": {},
  "intent": {},
  "options": {}
}
```

## Common Response Envelope

```json
{
  "requestId": "req_123",
  "provider": {
    "id": "ollama",
    "model": "llama3.1"
  },
  "result": {},
  "warnings": [],
  "usage": {}
}
```

## Error Model

All failures should map to a normalized error shape:

```json
{
  "error": {
    "code": "provider_unavailable",
    "message": "OpenAI API key is not configured.",
    "retryable": false,
    "details": {}
  }
}
```

Suggested error codes:

- `provider_unavailable`
- `provider_auth_invalid`
- `provider_timeout`
- `request_rejected_redaction_policy`
- `request_too_large`
- `invalid_request_shape`
- `internal_error`

## Redaction Policy

The companion must redact or reject unsafe payloads before hosted provider calls.

Minimum rules:

- do not forward obvious secrets from HAR captures
- do not forward bearer tokens or API keys verbatim
- do not forward cookies verbatim
- preserve structural usefulness while masking sensitive values

## Logging Policy

Allowed:

- provider id
- model id
- request id
- latency
- failure code
- redaction count or summary

Not allowed:

- raw API keys
- raw tokens
- raw user data from imported traffic unless specifically sanitized

## Versioning

The local API should be versioned conservatively.

Preferred approach:

- keep endpoints stable for v1
- version DTOs by additive change where possible
- only introduce path versioning if breaking changes become necessary
