# moqserver

`moqserver` is a local mock API platform. The Swift/Vapor server deterministically serves `.moqproj` bundles; the Kotlin/Compose Studio authors and imports those bundles. AI is optional authoring assistance and is never on the request-serving path.

## Features

- Typed `.moqproj` projects with inline responses, fixture files, or streamed bodies
- Multiple response variants selected by `X-Mock-Variant`, request matching, call count, admin override, or content negotiation
- Request rules (headers, query, cookies, required body, accepted content types), auth simulation, latency, jitter, and packet-loss simulation
- Scenarios that switch many endpoints at once, declared in the bundle or defined at runtime
- Isolated runtime sessions (`X-Mock-Session`) so parallel test suites never share state
- Runtime Admin API, request history, GraphQL per-operation matching, and live reload (`--watch`, `POST /_admin/reload`)
- Built-in add-ons such as `jwt-claims` and `oauth-mock` ([`docs/ADDONS.md`](docs/ADDONS.md))
- Browser and HTTPS clients: opt-in CORS (`--cors`), TLS (`--tls-cert`/`--tls-key`), automatic `HEAD`, `OPTIONS` and `405`
- Test-support clients for Apple (`server/MoqTestSupport`) and Kotlin/Android (`clients/kotlin`), blocking or async
- Studio import from OpenAPI and HAR, editing, validation, and export
- Local server binaries, desktop packages, and a GHCR container image

## Quick Start

Requirements: Swift 6.2 or newer. CI and releases use Swift 6.2.4.

```bash
git clone https://github.com/ArjangConsulting/moqserver.git
cd moqserver
swift run --package-path server moqserver validate --project samples/server/showcase.moqproj
swift run --package-path server moqserver serve \
  --project samples/server/showcase.moqproj \
  --config samples/server/config.yaml \
  --port 8080
```

In another terminal:

```bash
curl http://127.0.0.1:8080/public/health
curl -H 'X-Mock-Variant: error-500' http://127.0.0.1:8080/pets
```

Use a release archive by extracting it and running `./moqserver`.

## Project Format

```text
my-api.moqproj/
|-- project.yml
|-- endpoints/
|   `-- list-pets.yml
`-- fixtures/
    `-- pets.json
```

Start with the canonical project in [`format/examples/sample-app.moqproj`](format/examples/sample-app.moqproj). The machine-readable contract is [`format/schema.json`](format/schema.json), with cross-file rules documented in [`docs/FORMAT_IMPLEMENTATION.md`](docs/FORMAT_IMPLEMENTATION.md).

### Streaming response fixtures

A variant can add `stream: {chunk_bytes: 8, interval_ms: 25}` to deliver its
existing `body` or `body_file` in timed byte chunks. Byte boundaries may split UTF-8
scalars, exercising incremental decoders. Set `disconnect_after_bytes` to end the
stream with a transport error after that many bytes. `delay_ms` still controls the
initial response delay; `interval_ms` is the delay between writes (0–60000).
Fixtures remain unchanged on disk.

## Install And Update

| Product | Supported hosts | Install |
|---------|-----------------|---------|
| Server binary | Linux x86_64, macOS arm64 | Download the matching release archive, verify its `.sha256`, extract, and place `moqserver` on `PATH` |
| Server container | Any Docker/OCI host supported by the image | `docker pull ghcr.io/arjangconsulting/moqserver/server:<version>` |
| Authoring CLI (`moq-author`) | Linux x86_64, macOS arm64 | Download the matching release archive, verify its `.sha256`, extract, and place `moq-author` on `PATH`; see [`docs/AUTHORING_CLI.md`](docs/AUTHORING_CLI.md). This is the recommended way to author bundles, for scripts and AI agents alike |
| MCP server (`moq-mcp`), maintenance only | Linux x86_64, macOS arm64 | For MCP clients that cannot run a command. Download the matching release archive, verify its `.sha256`, extract, and register it as a stdio server; see [`server/AGENTS.md`](server/AGENTS.md) |
| Studio | macOS, Linux; Windows prereleases only until MSI signing is configured | Download the platform package from GitHub Releases |
| Source | macOS and Linux server; desktop hosts supported by Compose | Build with `make build` or `make studio-build` |

Update by downloading or pulling the new bare SemVer release. Uninstall the server by deleting the installed binary or image. Uninstall Studio with the OS package manager; on macOS, remove `moqserver-studio.app` and its user settings if desired.

Stable Studio releases are signed: macOS DMGs are Developer ID signed and notarized, and Linux DEBs include a detached GPG signature. Explicit SemVer prereleases such as `1.2.0-rc.1` may be unsigned. Every release includes SHA-256 checksums, an SBOM, and GitHub artifact attestations.

## Documentation

- [`docs/API_GUIDE.md`](docs/API_GUIDE.md): server CLI, format, variants, auth, Admin API, and Docker
- [`docs/ADMIN_API.md`](docs/ADMIN_API.md): Admin API reference
- [`docs/ERROR_CATALOG.md`](docs/ERROR_CATALOG.md): every structured error code the server returns
- [`docs/ADDONS.md`](docs/ADDONS.md): built-in add-ons and their configuration
- [`docs/RUNTIME_WORKFLOWS.md`](docs/RUNTIME_WORKFLOWS.md): scenarios, isolated test sessions, and the Studio Runtime Inspector
- [`docs/AUTHORING_CLI.md`](docs/AUTHORING_CLI.md): authoring bundles with `moq-author`, for scripts, CI and AI agents
- [`server/MoqTestSupport/README.md`](server/MoqTestSupport/README.md) and [`clients/kotlin/README.md`](clients/kotlin/README.md): test-support clients
- [`ARCHITECTURE.md`](ARCHITECTURE.md): current architecture and recovery model
- [`docs/RELEASE_CHECKLIST.md`](docs/RELEASE_CHECKLIST.md): maintainer release process
- [`samples/README.md`](samples/README.md): iOS and Android showcase
- [`CONTRIBUTING.md`](CONTRIBUTING.md), [`SUPPORT.md`](SUPPORT.md), [`SECURITY.md`](SECURITY.md), and [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md): community policies

## Development

```bash
make test
make studio-test
make studio-lint
```

See [`AGENTS.md`](AGENTS.md) for repository commands and product boundaries.

## License

MIT
