# AGENTS.md

Canonical note: This is the single source of truth for AI agent guidance for **Studio** (`studio/`). `studio/CLAUDE.md` is a stub that points here. For cross-repo workflow and the server product, see the root `AGENTS.md`.

## Mission and Scope
- This repo is **moqserver Studio**, a desktop Compose app for authoring `.moqproj` projects (`README.md`, `composeApp/`).
- Optimize for fast local workflows: domain logic is intentionally isolated from desktop/UI code.

## Architecture Map (Read This First)
- Entry point: `composeApp/src/desktopMain/kotlin/com/moqserver/studio/Main.kt` wires repositories/parsers/viewmodel and desktop callbacks.
- UI routing: `composeApp/src/commonMain/kotlin/com/moqserver/studio/App.kt` switches `ImportReviewScreen` vs landing vs workspace from `StudioState`.
- State owner: `studio-domain/src/commonMain/kotlin/com/moqserver/studio/domain/StudioRootViewModel.kt` is the single source of truth (`StateFlow<StudioState>`).
- Project format boundary: `studio-project-format/` owns schema-generated models and the `moq-format` RPC/repository adapters. Swift `MoqFormat` owns YAML, validation, and persistence.
- Data/adapters boundary: `studio-data/` owns settings/credential adapters only. Import parsing is delegated to Swift `MoqImport` through `moq-format`.

## Module Boundaries You Should Preserve
- `composeApp -> studio-domain + studio-project-format + studio-data + studio-ai + studio-ui + studio-code-editor + studio-logging`.
- `studio-domain` stays pure workflow logic (no desktop/Compose/IO concerns). Owns `ImportModels`, `ImportConverter`, and `VariantReferenceSyncPreference`.
- `studio-data` owns settings/preferences repositories only (no import parsers).
- `studio-import` was removed. `RemoteImportParsing` adapts the format service's parsed specs for domain import review.

## Core Data Flows (Examples)
- Open/save project: file dialog -> `ProjectRepository.load/save` -> `StudioRootViewModel.projectLoaded/projectSaved` (completion must carry the saved snapshot).
- Import OpenAPI/HAR: parser in Swift `MoqImport` via `FormatClient` -> `startImport` -> review UI -> `confirmImport` -> persisted `.moqproj`.
- AI action: UI trigger -> `AIActionHandler.executeAIAction` -> provider registry call -> `aiAction` state update.

## AI Integration
- Provider registry is built in `composeApp/.../AIActionHandler.kt` from persisted settings.
- AI providers are called directly from Studio via `studio-ai`; no separate localhost companion process is required.
- For AI debugging, use checked-in run config `studio/.run/Studio Debug.run.xml` (`-PstudioDebugRun=true`, package debug logging).
- `settings.gradle.kts` pulls in `KMPComponents` and `kenwork` via `includeBuild(...)` (Gradle composite builds). IntelliJ's native JPS builder does not resolve composite-build project substitution, so an `Application`-type run config built with "Build and run using: IntelliJ IDEA" fails with `ClassNotFoundException: com.moqserver.studio.MainKt` (empty classpath). Because of this, `0. Studio Debug` is a Gradle-type run config (`:composeApp:run -PstudioDebugRun=true`, debuggable via the IDE's Debug button) rather than an `Application`-type one — Gradle always resolves the composite builds correctly regardless of IDE delegation settings. `composeApp/build.gradle.kts` reads the `studioDebugRun` project property to append `-Dorg.slf4j.simpleLogger.log.com.moqserver.studio=debug -Dstudio.debug.failFast=true` only for that launch, so `./gradlew :composeApp:run` stays quiet by default.
- Keep every `org.jetbrains.compose.*` artifact (`compose-runtime`, `compose-foundation`, `compose-ui`, `compose-material3`, ...) pinned to the same `compose` version ref in `gradle/libs.versions.toml`. A material3 pin once drifted to a newer alpha independently of `compose`, which pulled `runtime`/`foundation`/`ui` up transitively to a version the Compose compiler plugin wasn't generating bytecode for — surfaced as `LinkageError occurred while loading main class com.moqserver.studio.MainKt`. Prefer the plugin's own `compose.material3` accessor (as `KMPComponents:components` does) over a manually versioned catalog entry so this can't drift again; run `./gradlew :composeApp:dependencies --configuration desktopRuntimeClasspath` if a similar mismatch warning reappears.

## Build/Test/Lint Commands
- Build desktop compile: `./gradlew :composeApp:compileKotlinDesktop`
- Run app: `./gradlew :composeApp:run`
- Full tests: `./gradlew test`
- Focused tests: `./gradlew :composeApp:desktopTest` / `./gradlew :studio-domain:jvmTest` / `./gradlew :studio-project-format:jvmTest` / `./gradlew :studio-data:test`
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
- Do not add new `@Suppress` or `@file:Suppress` annotations just to satisfy Detekt, compile checks, pre-commit hooks, or commit-time verification. Refactor the code to satisfy the rule; if a suppression is truly unavoidable, stop and justify it explicitly instead of slipping it in during a fix.

## Format service and runtime diagnostics
- `FormatClient` verifies protocol version/capabilities for each subprocess generation.
- `ProjectRepository` serializes IO workflows and retains the loaded disk revision across restarts.
- Do not silently adopt a new disk baseline when recovering an unknown session.
- Runtime Inspector is available under Tools; workflow state is `StudioState.runtime`, with HTTP IO in desktop adapters.
- Local binary bundling tracks all format-service source dependencies. CI packaging uses `-PusePrebuiltFormatBinary=true` to preserve the staged artifact.
- To use published dependencies without optional sibling checkouts, pass `-PuseLocalCompositeBuilds=false`.
- Some local skills predate the format-service migration; use the current architecture above for ownership and command discovery.
