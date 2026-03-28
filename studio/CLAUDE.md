# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Tech Stack

- **Language**: Kotlin, JVM 21
- **UI Framework**: Compose Multiplatform (desktop target only — macOS, Windows, Linux)
- **Architecture**: ViewModel + StateFlow (androidx.lifecycle)
- **HTTP client**: Ktor (CIO engine)
- **Serialization**: kotlinx.serialization + SnakeYAML Engine
- **Build**: Gradle with version catalog (`gradle/libs.versions.toml`)
- **Linting**: detekt 1.23.7 with detekt-formatting (ktlint wrapper)

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

# Tests — run all at once or per-module
./gradlew test
./gradlew :composeApp:desktopTest
./gradlew :studio-project-format:jvmTest
./gradlew :studio-domain:jvmTest
./gradlew :studio-data:test

# Linting
./gradlew detektAll                       # all modules (convenience task)
./gradlew :composeApp:detektDesktopMain   # single KMP module (use target-specific task)
./gradlew :studio-data:detekt             # single JVM module
```

### Detekt Notes

- For **KMP modules** (`composeApp`, `studio-domain`, `studio-project-format`, `studio-code-editor`, `studio-logging`, `studio-ui`), the plain `detekt` task shows NO-SOURCE. Use target-specific tasks: `detektMetadataMain`, `detektDesktopMain`, `detektJvmMain`, `detektCommonMain`.
- For **JVM modules** (`studio-data`, `studio-ai`), the plain `detekt` task works.
- The `detektAll` task in the root `build.gradle.kts` runs all target-specific detekt tasks across every module.
- Config lives at `config/detekt/detekt.yml`. Baselines are per-module (`<module>/detekt-baseline.xml`).
- To regenerate a baseline: `./gradlew :composeApp:detektBaselineDesktopMain` (or appropriate target variant).

## Module Structure

| Module | Responsibility |
|--------|---------------|
| `composeApp` | Compose UI screens and components (desktop target) |
| `studio-project-format` | Canonical `.moqproj` library: models, validation, YAML codec, repository I/O |
| `studio-domain` | ViewModels, app workflow state, import conversion |
| `studio-data` | OpenAPI/HAR import parsers and companion HTTP client |
| `studio-ai` | AI provider abstractions (Ollama, OpenAI, Anthropic) and action registry |
| `studio-ui` | Reusable Compose UI components (e.g. `MatchConditionEditor`) |
| `studio-code-editor` | `JsonCodeEditor` composable (syntax-highlighted editor) |
| `studio-logging` | `StudioLogger` + log extensions, wraps slf4j |

Dependency direction: `composeApp → studio-domain + studio-project-format + studio-data + studio-ai + studio-ui + studio-code-editor + studio-logging`; `studio-domain → studio-project-format`; `studio-data → studio-domain + studio-project-format`.

## Architecture

### State Management

All UI state lives in `StudioRootViewModel` (`studio-domain`) as a single `StudioState` data class exposed via `StateFlow`. Composables collect it with `collectAsState()`. The ViewModel is created platform-side in `Main.kt` (desktop) and passed into the `App()` composable.

```
Main.kt (desktop entry point — slim, delegates to helper files)
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

## composeApp File Layout

The `composeApp` module is the largest module and follows a structured layout:

### `commonMain` — Cross-platform Compose UI

```
com/moqserver/studio/
  App.kt                     ← top-level routing composable (screens, workspace layout)
  EndpointDetailPane.kt      ← orchestrator: tab bar + delegates to endpointdetail/ tabs
  EndpointBrowser.kt         ← sidebar endpoint list
  ImportReviewScreen.kt      ← import review UI
  AIResultsPanel.kt          ← AI analysis results display
  CompanionPanel.kt          ← companion server status/config
  ValidationPanel.kt         ← project validation results
  StudioTheme.kt             ← Material 3 theme definition
  endpointdetail/            ← extracted sub-package (was a single 1,785-line file)
    EndpointDetailModels.kt  ← constants, enums (VariantDetailTab, BodyFormat), helpers
    BodyEditorUtils.kt       ← pure logic: YAML↔JSON conversion, body parsing/validation
    VariantSummaryTab.kt     ← summary tab composable
    BodyTab.kt               ← body editor tab (format selector, JSON code editor)
    HeadersTab.kt            ← response headers CRUD table
    CookiesTab.kt            ← cookie rules CRUD table
    CriteriaTab.kt           ← auth config, network simulation, query params
    SharedUiHelpers.kt       ← shared UI utilities (placeholder colors, confirm dialog)
```

### `desktopMain` — Desktop-only platform code

```
com/moqserver/studio/
  Main.kt                ← slim entry point: main() function, wires everything together
  ErrorHandling.kt       ← crash handlers, fail-fast, crash log writing
  FileDialogs.kt         ← native file/directory choosers, .moqproj path resolution
  AIActionHandler.kt     ← AI provider registry, action execution, project context
  ProjectOperations.kt   ← open/save project, about dialog, app icon, exit confirmation
  AISettingsScreen.kt    ← AI settings configuration UI
  StudioPreviews.kt      ← Compose desktop previews
```

### Refactoring Principles Applied

- **No composable file > ~500 lines.** Large files are split into a sub-package with an orchestrator + tab/section files.
- **Pure logic extracted from composables.** Conversion functions, parsing, validation live in dedicated `*Utils.kt` files and are unit-testable without Compose.
- **Public API preserved.** The `EndpointDetailPane` composable stays in the `com.moqserver.studio` package so callers (e.g. `App.kt`) need no import changes; internal details live in `com.moqserver.studio.endpointdetail`.
- **Desktop-only helpers extracted from Main.kt.** Platform-specific concerns (file dialogs, error handling, AI actions, project operations) are each in their own file rather than one monolith.

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

### Formatting & Linting
- **4-space indentation**, no tabs.
- **Max line length**: 140 characters.
- **Trailing commas** required on both call sites and declaration sites.
- **MagicNumber** rule is disabled (too noisy for Compose color/dimension code).
- **WildcardImport** has exclusions for common Compose imports (`androidx.compose.*`) and project format models — see `config/detekt/detekt.yml` for the full list.
- Detekt config: `config/detekt/detekt.yml`. Editor config: `.editorconfig`.

## Test Expectations

- New Studio code should usually include tests when the change adds meaningful behavior.
- Prefer module-local tests for project format, import conversion, parsing, repository I/O, and view model workflow behavior.
- Pure logic extracted from composables (e.g. `BodyEditorUtils.kt`, `FileDialogs.kt` helpers) should have unit tests.
- UI polish or thin wiring may not always justify automated tests, but that should be a deliberate exception rather than the default.
- **Detekt rule**: test classes with >15 functions trigger `TooManyFunctions`. Split large test classes into focused sub-classes (e.g. `YamlValueConversionTest`, `VariantAndRuleHelpersTest`) to stay under the threshold.

## Constants Conventions

All magic strings, numbers, colors, and dimensions have been extracted into named constants. Follow these patterns when adding new code:

### UI Strings
- Each composable file has a `private object <FileName>Strings { ... }` block at the top of the file containing all user-visible string literals as `const val` properties.
- In KMP `commonMain`, **each file must use a unique object name** (e.g. `AppStrings`, `CompanionPanelStrings`, `EndpointDetailStrings`) to avoid `Redeclaration` errors — do NOT use a generic `Strings` name.
- Extract **all** string literals into the Strings object, including short labels like "Save", "Cancel", etc.

### Colors and Dimensions (`StudioTheme.kt`)
- **`StudioColors`** — semantic color tokens (success green, warning container, editor panel colors, HTTP status code colors). Non-theme-aware constants; for theme-aware colors use `MaterialTheme.colorScheme`.
- **`StudioDimens`** — spacing tokens on a 4dp grid (`xxs`=2, `xs`=4, `s`=6, `m`=8, `l`=12, `xl`=16, `xxl`=20, `xxxl`=24), plus component sizes (spinners, dots, icons), table column widths, layout sizes, and typography.
- **One-off dimensions** (e.g. `380.dp`, `48.dp`, `1.dp`) stay as inline literals — only repeated values get tokens.
- Modules that **cannot depend on `composeApp`** (`studio-ui`, `studio-code-editor`) use module-local `private object` constants with `/** Keep in sync with StudioColors */` comments when duplicating a shared token.

### API/Protocol and Logic Constants
- Use `companion object` constants on the relevant class (e.g. `LocalCompanionClient`, `MoqProjectFormat`, `ProjectValidator`).
- When the same constant is needed in two modules with no shared dependency (e.g. `studio-data` and `studio-ai`), duplicate it and add `/** Keep in sync with <OtherClass> */` comments in both locations.

### General Rules
- Prefer module-local constants (companion objects, per-file private objects) over a shared "constants module".
- Preserve public APIs — extracting constants should not change call sites or require new imports.
- When adding new composable code, follow the existing `<FileName>Strings` + `StudioDimens`/`StudioColors` patterns from neighboring files.
