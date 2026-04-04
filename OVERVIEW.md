# moqserver - High Level Overview

## What Problem Does This Solve?

When developing APIs, you often need a mock server for:
- Local development without hitting real backends
- Testing different scenarios (errors, timeouts, edge cases)
- CI pipelines that need a stable test environment
- Team collaboration with a shared mock API

moqserver automates this by reading your API definition or recorded traffic and serving mock responses. The broader product direction is to make mock authoring AI-assisted through a desktop Studio app so teams can generate stronger error coverage, cleaner mock structure, and more realistic scenarios with less manual work.

## Key Features

- **OpenAPI-Driven** - Your spec is the source of truth
- **AI-First Authoring Direction** - Use AI to analyze APIs, fill gaps, and generate better mocks
- **No Configuration Needed** - Register endpoints automatically
- **Multiple Responses** - Serve success/error/timeout variants
- **Authentication Mocking** - Simulate auth without real tokens
- **Simple to Deploy** - Docker container or standalone binary

## AI Provider Strategy

The planned AI layer is provider-agnostic and supports multiple operating modes:

- local models through `ollama`
- direct hosted APIs such as OpenAI and Claude
- generic OpenAI-compatible endpoints for self-hosted or gateway-based deployments
- future enterprise-managed providers such as Azure OpenAI and Claude on Vertex AI

The planned authoring surface is a desktop Studio app. Provider settings live in Studio and are used for bounded authoring tasks such as generating mock data, variants, and error cases.

## Architecture at 10,000 Feet

1. User provides OpenAPI spec (YAML/JSON)
2. System parses spec into normalized endpoint definitions
3. Endpoints stored in memory for fast lookup
4. HTTP server starts listening
5. For each incoming request:
   - Validate auth if required
   - Find matching endpoint
   - Pick response variant (header → config → default)
   - Return mock response

## Technology Stack

- **Language**: Swift 5.10+
- **Framework**: Vapor 4.121.x (async/await HTTP framework)
- **Format Support**: OpenAPI 3.x (YAML/JSON)
- **Runtime**: macOS, Linux, Docker

## What It's NOT

- Not a full OpenAPI compliance validator
- Not a real authentication system
- Not a performance testing tool
- Not a request/response recorder

## Typical Use Cases

1. **Local Development**
   ```bash
   swift run Run serve --spec ./api.yaml --port 8080
   # Now you have a local mock API running
   ```

2. **Testing Different Scenarios**
   ```bash
   curl -H "X-Mock-Variant: error-500" http://localhost:8080/api/users
   # Get error response instead of success
   ```

3. **CI Pipeline**
   ```bash
   docker run -p 8080:8080 moqserver serve --spec ./spec.yaml
   # Run integration tests against mock API
   ```

## Future Directions

- AI-assisted mock generation and API analysis
- desktop-first `.moqproj` authoring workflow and Studio app
- gRPC support (in addition to REST)
- Webhook simulation
- Response templating
- Advanced request matching
- Recorded traffic playback

---

This is a pragmatic tool. It's not trying to be perfect - it's trying to be useful for local development and testing.
