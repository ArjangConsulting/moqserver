# moqserver studio

Desktop-first authoring surface for `.moqproj` files.

## Local development

Use Java 17 or 21 for Gradle and IntelliJ. The current Kotlin/Gradle setup does not load correctly on Java 25 and fails early with `IllegalArgumentException: 25.0.2`.

Open the `studio/` directory as the IntelliJ project. That is the canonical IDE setup for the desktop app.

Use the Gradle wrapper from the `studio/` directory:

```bash
cd studio
./gradlew test
```

To verify the desktop app compiles without packaging it:

```bash
cd studio
./gradlew :composeApp:compileKotlinDesktop
```

To launch the desktop app locally:

```bash
cd studio
./gradlew :composeApp:run
```

To create an unpackaged app image you can run directly:

```bash
cd studio
./gradlew :composeApp:createDistributable
```

On macOS the generated app bundle will be under:

```bash
studio/composeApp/build/compose/binaries/main/app/moqserver-studio.app
```

To package an installer for the current OS:

```bash
cd studio
./gradlew :composeApp:packageDistributionForCurrentOS
```

Platform-specific packaging tasks are also available:

```bash
cd studio
./gradlew :composeApp:packageDmg
./gradlew :composeApp:packageDeb
./gradlew :composeApp:packageMsi
```

Packaged artifacts are written under:

```bash
studio/composeApp/build/compose/binaries/main/
```

## IntelliJ run configurations

Shared JetBrains run configurations are checked in under:

```text
studio/.run/
```

The Studio modules target Java 17.

### Recommended debug loop

1. Set IntelliJ's Gradle JVM and Project SDK to Java 17 or 21.
2. Run `0. Studio Debug`.
3. Start the Swift companion separately from the repo root with `make companion` when you need AI actions.

`0. Studio Debug` now launches the desktop app as a real JVM application instead of a Gradle task, and enables:

- package-level debug logging via `-Dorg.slf4j.simpleLogger.log.com.moqserver.studio=debug`
- fail-fast exception handling via `-Dstudio.debug.failFast=true`

That means IntelliJ sees normal app stdout/stderr, debug logs are visible in the Run/Debug tool window, and exceptions stop the debug session instead of being converted into recoverable UI errors.

### Why this is the normal setup

For Compose Desktop, the straightforward setup is:

- keep the app inside a single Gradle project
- open that Gradle project directly in IntelliJ
- run the desktop entrypoint as an `Application` configuration when you want debugger-friendly behavior
- use Gradle tasks for packaging and CI, not as the primary debug entrypoint

The repository root contains other products and tooling, so Studio-specific IntelliJ metadata now lives only under `studio/`.

## Modules

- `composeApp` — desktop executable shell and screens
- `studio-project-format` — canonical `.moqproj` library: models, validation, YAML codec, and repository I/O
- `studio-domain` — shared state, DTOs, and app workflow logic
- `studio-data` — local companion client plus OpenAPI/HAR import adapters
- `studio-code-editor` — Swing-backed JSON/YAML editor wrappers
- `studio-logging` — logging helpers

## Local skills

- `studio/skills/kotlin/SKILL.md`
- `studio/skills/compose-multiplatform/SKILL.md`

## Why this layout

This keeps domain logic portable, isolates JVM-only dependencies, and avoids mixing editor interop code into the main app shell.

See `docs/STUDIO_ARCHITECTURE.md` for the rationale and dependency choices.
