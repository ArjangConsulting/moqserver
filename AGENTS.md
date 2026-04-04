# AGENTS.md

This file is the repo-level operating guide for coding agents working in `moqserver`.

Read this first, then follow the product-specific guide:
- Server: `server/CLAUDE.md`
- Studio: `studio/AGENTS.md`

Do not duplicate those product guides in new files unless the repo structure changes. Keep this file focused on cross-repo workflow, command discovery, and shared expectations.

## Repo Shape
- This is a mono-repo with two separate products.
- `server/` is the Swift/Vapor mock server that loads and serves `.moqproj` bundles.
- `studio/` is the Kotlin/Compose desktop app for authoring `.moqproj` projects.
- Shared artifact: `.moqproj` directory bundles consumed by both products.
- If a user request is ambiguous, confirm whether the change belongs to `server/` or `studio/` before editing code.

## Instruction Sources
- Root guidance: this file.
- Server details: `server/CLAUDE.md`.
- Studio details: `studio/AGENTS.md`.
- Studio `CLAUDE.md` is only a stub that points back to `studio/AGENTS.md`.
- Cursor rules: none found in `.cursor/rules/` or `.cursorrules`.
- Copilot rules: none found in `.github/copilot-instructions.md`.

## General Workflow
- Start by reading the relevant product guide before making non-trivial changes.
- Prefer the smallest correct change.
- Preserve module boundaries and existing architecture.
- Do not move logic across products.
- Add or update tests with behavior changes unless the change is truly trivial.
- If skipping tests, say why in the final summary.

## Common Commands
- Run all commands from the repo root unless noted otherwise.
- Shared clean: `make clean`
- Server build: `make build`
- Server test: `make test`
- Server smoke tests: `make smoke`
- Server end-to-end tests: `make e2e`
- Server run sample spec: `make run`
- Server release build: `make release`
- Docker image: `make docker-build`
- Docker compose run: `make docker-run`
- Studio build: `make studio-build`
- Studio run: `make studio-run`
- Studio test: `make studio-test`
- Studio lint: `make studio-lint`
- Studio package current OS: `make studio-package`
- Studio DMG: `make studio-dmg`
- Studio DEB: `make studio-deb`
- Studio MSI: `make studio-msi`

## Server Commands
- Build: `cd server && swift build`
- Full test suite: `cd server && swift test`
- Single test target: `cd server && swift test --filter MoqRuntimeTests`
- Single test case: `cd server && swift test --filter "testAdminAPI"`
- Run server: `cd server && swift run moqserver serve --spec ../samples/server/openapi.yaml --port 8080`
- Validate OpenAPI spec: `cd server && swift run moqserver validate-spec ../samples/server/openapi.yaml`
- Validate project bundle: `cd server && swift run moqserver validate path/to/project.moqproj`

## Server Test Targets
- `MoqCoreTests`
- `MoqParsingTests`
- `MoqFormatTests`
- `MoqRuntimeTests`
- `MoqIntegrationTests`

## Server Testing Notes
- `swift test --filter` accepts a target name or a test name substring.
- Prefer filtering by target while iterating on a module.
- Use a specific case filter when narrowing down one failure.
- Runtime and integration changes usually need tests because they affect request handling or end-to-end behavior.

## Studio Commands
- Build desktop target: `cd studio && ./gradlew :composeApp:compileKotlinDesktop`
- Run desktop app: `cd studio && ./gradlew :composeApp:run`
- Full test suite: `cd studio && ./gradlew test`
- Full lint suite: `cd studio && ./gradlew detektAll`
- Package current OS: `cd studio && ./gradlew :composeApp:packageDistributionForCurrentOS`
- Package release current OS: `cd studio && ./gradlew :composeApp:packageReleaseForCurrentOS`
- Register macOS app bundle: `cd studio && ./gradlew :composeApp:registerMacApp`
- Package uber jar: `cd studio && ./gradlew :composeApp:packageUberJarForCurrentOS`

## Studio Single-Test Commands
- Desktop tests module: `cd studio && ./gradlew :composeApp:desktopTest`
- Domain tests module: `cd studio && ./gradlew :studio-domain:jvmTest`
- Project format tests module: `cd studio && ./gradlew :studio-project-format:jvmTest`
- Data module tests: `cd studio && ./gradlew :studio-data:test`
- Single JVM test class: `cd studio && ./gradlew :studio-domain:jvmTest --tests "com.moqserver.studio.domain.StudioRootViewModelTest"`
- Single JVM test method: `cd studio && ./gradlew :studio-domain:jvmTest --tests "com.moqserver.studio.domain.StudioRootViewModelTest.someMethod"`
- Single desktop test class: `cd studio && ./gradlew :composeApp:desktopTest --tests "com.moqserver.studio.ProjectOperationsTest"`

## Studio Lint Notes
- Detekt config: `studio/config/detekt/detekt.yml`
- Root lint task aggregates module-specific detekt tasks.
- KMP modules may expose source-set-specific detekt tasks such as `:composeApp:detektDesktopMain`.
- Kotlin compilation warnings are treated as errors in all Studio subprojects.

## Server Style
- Follow `server/CLAUDE.md` for architecture and module responsibilities.
- Sort imports alphabetically when editing Swift files.
- Remove unused imports.
- Group imports as: system frameworks, third-party packages, internal modules.
- Separate import groups with a single blank line.
- Prefer `async/await`; the server is already structured around async APIs.
- Preserve actor-based concurrency boundaries such as `InMemoryMockStore`.
- Use value types and protocol abstractions already present in the package.
- Keep parsing, format, runtime, and CLI concerns in their existing targets.
- Prefer explicit, typed domain models over ad hoc dictionaries except at format or boundary layers.
- Avoid force unwraps.
- Prefer throwing functions or explicit validation results over silent fallback.
- Build HTTP error responses with structured payloads, not plain strings, when following runtime patterns.
- Match existing naming: `TypeName` for types, `camelCase` for functions and properties, descriptive test names.
- Tests currently use Swift Testing annotations like `@Suite` and `@Test`; follow that style where already used.

## Studio Style
- Follow `studio/AGENTS.md` for module boundaries and UI workflow constraints.
- Respect `studio/.editorconfig`.
- Kotlin uses tabs, visual width 4, and max line length 140.
- Trailing commas are enabled for Kotlin declarations and call sites.
- Keep user-facing strings centralized in a file-local object such as `AppStrings` in common UI files.
- Reuse shared design tokens such as `StudioDimens` and `StudioColors` where available.
- Keep composable files focused; move pure logic into helper files when the UI file becomes dense.
- Preserve `StudioRootViewModel` as the main state owner rather than pushing workflow state into composables.
- Prefer immutable data classes and copy-based state updates.
- Avoid wildcard imports except where Detekt explicitly allows them.
- Follow naming rules from Detekt: functions in `camelCase`, constants in `UPPER_SNAKE_CASE`, private properties in lower camel or leading underscore.
- Prefer explicit validation and diagnostics over hidden correction of invalid project data.
- Keep `studio-domain` free of desktop/Compose/IO concerns.

## Formatting and Comments
- Follow existing file formatting rather than rewrapping unrelated code.
- Keep comments sparse and useful.
- Add comments to explain non-obvious behavior, not to narrate straightforward code.
- Preserve existing section markers like `// MARK:` in Swift where they are already used.

## Naming and Types
 - Use domain terms already established in the repo: endpoint, variant, manifest, project, provider.
- Prefer clear names that encode behavior instead of abbreviations.
- Do not introduce alternate names for existing concepts without a concrete reason.
- Prefer small helper functions over deeply nested control flow when it clarifies intent.
- Avoid generic `Any`/untyped maps unless working at serialization or interop boundaries.

## Error Handling
- Fail explicitly on invalid input, invalid config, or invalid persisted data.
- Preserve validation-first behavior in both products.
- Surface actionable diagnostics with enough context to locate the failing endpoint, field, or file.
- Do not swallow exceptions just to keep execution moving.
- When catching broad exceptions in Studio, ensure the catch is justified and consistent with the existing Detekt config.

## Testing Expectations
- New logic should usually ship with tests in the same change.
- Add unit tests for validation, parsing, naming, conversion, and state transitions.
- Add runtime or integration tests when request routing, auth, persistence, or end-to-end behavior changes.
- Keep tests near the module that owns the behavior.
- Prefer focused tests over broad incidental coverage.

## Safe Change Rules
- Never assume a request applies to both products.
- Do not collapse product-specific guidance into this file; reference the deeper guide instead.
- Do not remove or rewrite existing agent docs unless the user asks.
- If you add new repo-wide tooling or rules later, update this file and point to the authoritative config path.
