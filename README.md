# moqserver

`moqserver` is a local mock API platform. The Swift/Vapor server deterministically serves `.moqproj` bundles; the Kotlin/Compose Studio authors and imports those bundles. AI is optional authoring assistance and is never on the request-serving path.

## Features

- Typed `.moqproj` projects with inline responses or fixture files
- Multiple response variants selected by `X-Mock-Variant`, request matching, admin override, or content negotiation
- Request rules, auth simulation, latency, jitter, and packet-loss simulation
- Runtime Admin API and GraphQL operation matching
- Studio import from OpenAPI and HAR, editing, validation, and export
- Local server binaries, desktop packages, and a GHCR container image

## Quick Start

Requirements: Swift 6.1 or newer. CI and releases use Swift 6.2.

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

## Install And Update

| Product | Supported hosts | Install |
|---------|-----------------|---------|
| Server binary | Linux x86_64, macOS arm64 | Download the matching release archive, verify its `.sha256`, extract, and place `moqserver` on `PATH` |
| Server container | Any Docker/OCI host supported by the image | `docker pull ghcr.io/arjangconsulting/moqserver/server:<version>` |
| MCP server (`moq-mcp`) | Linux x86_64, macOS arm64 | Download the matching release archive, verify its `.sha256`, extract, and register it with an MCP client (Claude Code, Claude Desktop, etc.) as a stdio server; see [`server/AGENTS.md`](server/AGENTS.md) |
| Studio | macOS, Linux; Windows prereleases only until MSI signing is configured | Download the platform package from GitHub Releases |
| Source | macOS and Linux server; desktop hosts supported by Compose | Build with `make build` or `make studio-build` |

Update by downloading or pulling the new bare SemVer release. Uninstall the server by deleting the installed binary or image. Uninstall Studio with the OS package manager; on macOS, remove `moqserver-studio.app` and its user settings if desired.

Stable Studio releases are signed: macOS DMGs are Developer ID signed and notarized, and Linux DEBs include a detached GPG signature. Explicit SemVer prereleases such as `1.2.0-rc.1` may be unsigned. Every release includes SHA-256 checksums, an SBOM, and GitHub artifact attestations.

## Documentation

- [`docs/API_GUIDE.md`](docs/API_GUIDE.md): server CLI, format, variants, auth, Admin API, and Docker
- [`docs/ADMIN_API.md`](docs/ADMIN_API.md): Admin API reference
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
