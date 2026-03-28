# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Overview

This is a mono-repo for **moqserver** — a lightweight REST mock server driven by OpenAPI specs, and its companion desktop authoring app **moqserver Studio**.

| Project | Language / Framework | Directory |
|---------|---------------------|-----------|
| Server  | Swift 5.10+, Vapor 4 | `server/` |
| Studio  | Kotlin, Compose Multiplatform | `studio/` |

See `server/CLAUDE.md` and `studio/CLAUDE.md` for project-specific details.

## Common Commands (via Makefile)

All targets below can be run from the repo root.

```bash
# Server
make build          # swift build
make test           # swift test (all targets)
make smoke          # swift test --filter SmokeTests
make e2e            # swift test --filter MoqIntegrationTests
make run            # serve with samples/server/openapi.yaml on :8080
make companion      # AI companion server on :8081
make release        # release build
make docker-build   # docker build ./server
make docker-run     # docker-compose up

# Studio
make studio-build   # compile Kotlin desktop target
make studio-run     # run the desktop app
make studio-package # package for current OS
make studio-dmg     # macOS .dmg
make studio-deb     # Linux .deb
make studio-msi     # Windows .msi
```

## Product Architecture

```
┌─────────────────────────────────────┐
│         moqserver Studio            │  ← Kotlin/Compose desktop authoring UI
│  (Open/Import/Edit .moqproj files)  │
└──────────────┬──────────────────────┘
               │ HTTP (localhost)
               ▼
┌─────────────────────────────────────┐
│       Companion AI Server           │  ← Swift/Vapor, `companion` subcommand
│  /ai/analyze-spec, /ai/generate-…   │
│  Routes to: Ollama / OpenAI / Claude│
└─────────────────────────────────────┘

┌─────────────────────────────────────┐
│        moqserver (serve)            │  ← Swift/Vapor, `serve` subcommand
│  Reads .moqproj or OpenAPI spec     │
│  Serves mock HTTP responses         │
└─────────────────────────────────────┘
```

## Shared File Format: `.moqproj`

The `.moqproj` format is the interchange between Studio and the server runtime. It is a YAML directory bundle:
- `manifest.yaml` — name, version, description
- `endpoints/<method>_<path>.yaml` — one file per endpoint with variants

The JSON schema lives in `format/schema.json`.

## Engineering Expectations

- Prefer adding or updating tests alongside behavior changes.
- When new code introduces meaningful logic, branching, serialization, persistence, or request/response behavior, include tests in the same change.
- It is acceptable to skip new tests for trivial refactors, docs-only changes, or thin wiring with no practical behavior to verify, but call that out explicitly.
