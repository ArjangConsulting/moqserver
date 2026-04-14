# AGENTS.md

Canonical note: This is the single source of truth for AI agent guidance in this repo. Other agent files should reference this file instead of duplicating content.

## Mission and Scope
- This repo is **moqserver Studio**, a desktop Compose app for authoring `.moqproj` projects (`README.md`, `composeApp/`).
- Optimize for fast local workflows: domain logic is intentionally isolated from desktop/UI code.

## Architecture Map (Read This First)
- Entry point: `composeApp/src/desktopMain/kotlin/com/moqserver/studio/Main.kt` wires repositories/parsers/viewmodel and desktop callbacks.
- UI routing: `composeApp/src/commonMain/kotlin/com/moqserver/studio/App.kt` switches `ImportReviewScreen` vs landing vs workspace from `StudioState`.
- State owner: `studio-domain/src/commonMain/kotlin/com/moqserver/studio/domain/StudioRootViewModel.kt` is the single source of truth (`StateFlow<StudioState>`).
- Project format boundary: `studio-project-format/` owns `.moqproj` schema, YAML codec, validation, and disk I/O (`ProjectRepository`).
- Data/adapters boundary: `studio-data/` owns settings/credential adapters only; `studio-import/` owns import parsers; do not move project-format semantics here.

## Module Boundaries You Should Preserve
- `composeApp -> studio-domain + studio-project-format + studio-data + studio-import + studio-ai + studio-ui + studio-code-editor + studio-logging`.
- `studio-domain` stays pure workflow logic (no desktop/Compose/IO concerns). Owns `ImportModels`, `ImportConverter`, and `VariantReferenceSyncPreference`.
- `studio-data` depends on `studio-domain` + `studio-project-format` for adapter integration only. Owns settings/preferences repositories only (no import parsers).
- `studio-import` owns `OpenAPIImportParser` and `HARImportParser` (JVM-only). Depends on `studio-domain` + `studio-project-format` + `studio-logging`.

## Core Data Flows (Examples)
- Open/save project: file dialog -> `ProjectRepository.load/save` -> `StudioRootViewModel.projectLoaded/projectSaved`.
- Import OpenAPI/HAR: parser in `studio-import` -> `startImport` -> review UI -> `confirmImport` -> persisted `.moqproj`.
- AI action: UI trigger -> `AIActionHandler.executeAIAction` -> provider registry call -> `aiAction` state update.

## AI Integration
- Provider registry is built in `composeApp/.../AIActionHandler.kt` from persisted settings.
- AI providers are called directly from Studio via `studio-ai`; no separate localhost companion process is required.
- For AI debugging, use checked-in run config `studio/.run/Studio Debug.run.xml` (`-Dstudio.debug.failFast=true`, package debug logging).

## Build/Test/Lint Commands
- Build desktop compile: `./gradlew :composeApp:compileKotlinDesktop`
- Run app: `./gradlew :composeApp:run`
- Full tests: `./gradlew test`
- Focused tests: `./gradlew :composeApp:desktopTest` / `./gradlew :studio-domain:jvmTest` / `./gradlew :studio-project-format:jvmTest` / `./gradlew :studio-data:test` / `./gradlew :studio-import:test`
- Lint all modules: `./gradlew detektAll`
- Detekt nuance: KMP modules need target-specific tasks (e.g. `:composeApp:detektDesktopMain`), while JVM modules use plain `detekt`.

## Project-Specific Conventions
- Keep composable files small; extract pure logic into `*Utils.kt` (pattern used in `composeApp/.../endpointdetail/`).
- In `commonMain` composables, keep all user-facing strings in a per-file unique object like `AppStrings`.
- Reuse `StudioDimens`/`StudioColors` where available; for modules that cannot depend on `composeApp`, mirror constants locally with keep-in-sync comments.
- Prefer module-local constants (file-private object or companion object), not global constants modules.
- Follow `.editorconfig` for formatting (Kotlin uses tabs with size 4; max line length 140; trailing commas enabled).

## Safe Change Heuristics
- If behavior changes in viewmodel or project-format code, add module-local tests near that logic.
- If behavior changes in desktop workflow wiring or shared UI state, add a focused desktop test for the new branch or helper; compile-only verification is not enough for new features.
- Keep save/dirty invariants intact (`StudioState.isDirty`, `hasErrors`, `windowTitle`), since UI enablement depends on them.
- When touching `.moqproj` persistence, preserve fixture migration/cleanup behavior in `ProjectRepository.save()`.
