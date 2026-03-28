# Architecture Overview

## System Flow

```
User provides OpenAPI spec
    ↓
Parse spec into endpoints
    ↓
Store endpoints in registry
    ↓
Start HTTP server
    ↓
Incoming request
    ↓
Check authentication
    ↓
Validate required query/header/body inputs
    ↓
Look up endpoint
    ↓
Select response variant (by priority)
    ↓
Return HTTP response
```

## Core Concepts

**Endpoint** - A single API path (e.g., GET /users) with associated metadata
- HTTP method
- Path
- Authentication requirement
- Available response variants

**Response Variant** - A specific mock response for an endpoint
- HTTP status code
- JSON body
- Response headers
- Optional delay

**Variant Selection** - How to choose which variant to return
1. Check request header (`X-Mock-Variant`)
2. Check admin runtime override
3. Check configuration override
4. Use default variant
5. Apply optional per-variant request matchers (query/header/body contains)

**Authentication** - Endpoint protection (locally validated)
- None
- Bearer token
- Basic auth
- Custom API key header

## High Level Modules

- **Parsing** - Convert OpenAPI to internal model
- **Storage** - Keep endpoints in memory, fast lookup
- **Authentication** - Validate auth headers
- **Routing** - HTTP server integration
- **CLI** - Command line interface

## Design Principles

- In-memory, no database
- Local-only auth validation (not real auth)
- Pragmatic OpenAPI support (not 100% compliant)
- Simple HTTP routing (no regex or dynamic matching yet)
- Extensible for future features (gRPC, webhooks, etc.)
