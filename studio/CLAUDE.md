# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Tech Stack

- **Language**: Kotlin, JVM 21
- **UI Framework**: Compose Multiplatform (desktop target only — macOS, Windows, Linux)
- **Architecture**: ViewModel + StateFlow (androidx.lifecycle)
- **HTTP client**: Ktor (CIO engine)
- **Serialization**: kotlinx.serialization + SnakeYAML Engine
- **Build**: Gradle with version catalog (`gradle/libs.versions.toml`)

## Build & Run

```bash
# From repo root
make studio-run       # run the app
make studio-build     # compile only
make studio-package   # package for current OS

# Or directly from studio/
./gradlew :composeApp:run
./gradlew :composeApp:compileKotlinDesktop
./gradlew :composeApp:packageDistributionForCurrentOS
./gradlew :composeApp:packageDmg

# Tests
./gradlew test
./gradlew :studio-project-format:jvmTest
./gradlew :studio-data:test
```

## Module Structure

| Module | Responsibility |
|--------|---------------|
| `composeApp` | Compose UI screens and components (desktop target) |
| `studio-project-format` | Canonical `.moqproj` library: models, validation, YAML codec, repository I/O |
| `studio-domain` | ViewModels, app workflow state, import conversion |
| `studio-data` | OpenAPI/HAR import parsers and companion HTTP client |
| `studio-code-editor` | `JsonCodeEditor` composable (syntax-highlighted editor) |
| `studio-logging` | `StudioLogger` + log extensions, wraps slf4j |

Dependency direction: `composeApp → studio-domain + studio-project-format + studio-data + studio-code-editor + studio-logging`; `studio-domain → studio-project-format`; `studio-data → studio-domain + studio-project-format`.

## Architecture

### State Management

All UI state lives in `StudioRootViewModel` (`studio-domain`) as a single `StudioState` data class exposed via `StateFlow`. Composables collect it with `collectAsState()`. The ViewModel is created platform-side in `Main.kt` (desktop) and passed into the `App()` composable.

```
Main.kt (desktop)
  → creates StudioRootViewModel, ProjectRepository, LocalCompanionClient
  → passes callbacks (file pickers, save, companion refresh) into App()
App()
  → reads StudioState via collectAsState()
  → routes to: ImportReviewScreen | StudioLandingScreen | StudioWorkspaceScreen
```

### Data Flow

```
File picker (desktop) → ProjectRepository.load()  → StudioRootViewModel.projectLoaded()
Import OpenAPI/HAR    → OpenAPIImportParser / HARImportParser → ImportState → confirm → MoqProject
Save                  → ProjectRepository.save(project, path) → studioRootViewModel.projectSaved()
AI action             → LocalCompanionClient.POST /ai/…  → ViewModel.analyzeSpecCompleted() etc.
```

### Key Patterns

- **`studio-domain` is pure Kotlin** (no Android/Compose/IO imports) — keeps workflow logic testable without a desktop runtime.
- **`.moqproj` ownership lives in `studio-project-format`** — models, validation, YAML read/write, and repository I/O belong there.
- **`studio-data` does not own project format semantics** — it handles import parsers and the companion client.
- **Companion client** (`LocalCompanionClient`) talks to the Swift companion server running locally (default `:8081`).
- **`StudioState.isDirty`** tracks unsaved changes; Save button is disabled when `isDirty == false || hasErrors`.

## `.moqproj` File Format

Projects are stored as YAML directory bundles. `studio-project-format` handles serialization and repository I/O. The schema is at `../format/schema.json` (relative to repo root).

## Local Skills

- Kotlin skill: `skills/kotlin/SKILL.md`
- Compose Multiplatform skill: `skills/compose-multiplatform/SKILL.md`

## Code Style

### Imports
- **Always sort imports** alphabetically within each group when modifying a file.
- **Remove unused imports** — do not leave stale imports after refactors.
- Standard Kotlin import grouping order: `java`/`javax` → `kotlin` stdlib → third-party (`androidx`, `org.*`, `io.*`, etc.) → internal project modules.
- Each group separated by a blank line; no blank lines within a group.

## Test Expectations

- New Studio code should usually include tests when the change adds meaningful behavior.
- Prefer module-local tests for project format, import conversion, parsing, repository I/O, and view model workflow behavior.
- UI polish or thin wiring may not always justify automated tests, but that should be a deliberate exception rather than the default.
