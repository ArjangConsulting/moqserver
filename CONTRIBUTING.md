# Contributing

Thanks for considering a contribution to `moqserver`.

## Project Layout

- `server/` contains the Swift/Vapor CLI runtime.
- `studio/` contains the Kotlin/Compose desktop authoring app.
- `format/` contains the shared `.moqproj` schema and examples.
- `samples/` contains sample server, iOS, and Android projects.

## Requirements

- Swift 5.10 or newer for the server.
- JDK 21 for Studio builds and tests.
- Docker is optional for image validation.

## Development Commands

Run commands from the repository root unless noted otherwise.

```bash
make build          # Swift server build
make test           # Swift server tests
make studio-build   # Studio desktop compile
make studio-test    # Studio test suite
make studio-lint    # Studio detekt lint
```

Useful direct commands:

```bash
cd server && swift run Run serve --spec ../samples/server/openapi.yaml --port 8080
cd studio && ./gradlew :composeApp:compileKotlinDesktop
```

## Pull Requests

- Keep changes focused and minimal.
- Add or update tests for behavior changes.
- Update docs when commands, formats, workflows, or user-visible behavior change.
- Do not commit real credentials, local environment files, build outputs, or machine-specific paths.
- For changes that touch both server and Studio, explain how `.moqproj` compatibility is preserved.

## Coding Standards

- Swift server code uses async/await and explicit domain types.
- Kotlin Studio code follows `studio/.editorconfig`; tabs are used with visual width 4.
- Preserve module boundaries: runtime behavior belongs in `server/`, authoring workflows belong in `studio/`, and shared format semantics belong in `format/` docs/schema plus each product's format implementation.

## Reporting Issues

When filing an issue, include:

- OS and tool versions.
- The command you ran.
- The expected result.
- The actual output or error.
- A minimal OpenAPI/HAR/`.moqproj` example when relevant.
