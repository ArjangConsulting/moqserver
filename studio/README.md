# moqserver studio

Desktop-first authoring surface for `.moqproj` files.

## Local development

Use Java 21 for Gradle and IntelliJ. It is the release CI version and the toolchain used by all Studio modules.

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

The Studio modules use a Java 21 toolchain.

### Recommended debug loop

1. Set IntelliJ's Gradle JVM and Project SDK to Java 21.
2. Run `0. Studio Debug`.

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
- `studio-data` — local settings/credential adapters plus OpenAPI/HAR import adapters
- `studio-ai` — provider abstractions, prompt building, and direct AI integrations
- `studio-logging` — logging helpers

## Local skills

- `studio/skills/kotlin/SKILL.md`
- `studio/skills/compose-multiplatform/SKILL.md`

## Why this layout

This keeps domain logic portable, isolates JVM-only dependencies, and avoids mixing editor interop code into the main app shell.

See `docs/STUDIO_ARCHITECTURE.md` for the rationale and dependency choices.

### Response body editor and dependency publication

Response bodies use the Compose CodeEditor with soft wrapping and Tab/Shift+Tab focus traversal.
JSON editing uses local syntax diagnostics. Format JSON runs asynchronously on explicit request
and is one editor undo step. Save still parses through Studio and updates the project; Cancel
and document switching discard the draft and undo history. External body replacements, including
AI results, start a fresh session. Fixture bodies remain file-backed until a changed draft is saved.
Project parsing, validation, fixture IO, dirty state, and persistence remain Studio responsibilities.

The catalog targets `com.arjangconsulting.codeeditor:code-editor:0.1.0`. Until publication, Studio
and security CI use editor commit `28790a500c93efc717acf03ab475f988e236087d`. After publication,
run `bash studio/scripts/verify-published-editor.sh` from the repository root, then set the repository
variable `CODE_EDITOR_PUBLISHED=true`. Studio, security, and release workflows then omit the editor checkout, disable local
composites, check the root and JVM POMs, and compile against Maven Central. Keep the source fallback
until those workflows pass. An unavailable release fails the explicit published mode.

On a fresh clone without a sibling `kmp-code-editor` checkout, run `make studio-bootstrap` (every
`make studio-*` target runs it first). It clones the public editor repository at the pinned commit
into the git-ignored `studio/.code-editor`, which `settings.gradle.kts` includes automatically.
Keep the commit in `studio/scripts/fetch-code-editor.sh` in sync with the workflows' checkout `ref:`.

For local development, sibling composites remain enabled by default. Use
`-PcodeEditorCompositeBuild=/path/to/kmp-code-editor` for an explicit checkout. To verify published
consumption locally, unset `ORG_GRADLE_PROJECT_codeEditorCompositeBuild`, omit that property, and run
`./gradlew -PuseLocalCompositeBuilds=false :composeApp:desktopTest :composeApp:compileKotlinDesktop`.
The repository availability probe alone does not establish Gradle artifact consumption.

Reviewed captures and detailed check coverage are in the
[Studio editor verification report](../docs/STUDIO_EDITOR_VERIFICATION.md).
