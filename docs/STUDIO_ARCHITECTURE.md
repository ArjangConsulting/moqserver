# Studio Architecture

## Decision

The Studio app should use a Compose Multiplatform desktop shell with a small Gradle multi-project build:

- `composeApp` for the executable app shell and screens
- `studio-domain` for shared state, DTOs, and use-case contracts
- `studio-data` for JVM-only IO, YAML, validation, and companion networking
- `studio-code-editor` for the embedded structured text editor

This is the right balance for v1. It keeps the app modular enough to avoid another monolith, but avoids splitting import/export/AI/editor into ten tiny modules before the workflows are proven.

## Why This Architecture

### Compose Desktop for the shell

Compose Multiplatform already gives us:

- a current desktop-first app model
- first-party navigation support
- first-party common `ViewModel` support
- native packaging via the Compose Gradle plugin

This is enough for windowing, menus, panes, dialogs, navigation, and the bulk of Studio UI.

### StateFlow + ViewModel instead of a third-party architecture framework

The desktop app should use:

- `androidx.lifecycle.ViewModel`
- `MutableStateFlow` and `StateFlow`
- explicit intent/event handlers per screen or feature

That gives predictable state handling and testable logic without adding a second architectural opinion like Decompose, Voyager, Koin, or Redux-style middleware before the app needs them.

The current problem is not “we need a framework for our framework.” The current problem is establishing clean boundaries between domain state, JVM infrastructure, and the app shell.

### Swing interop for the editor

Compose Desktop does not provide a mature built-in source editor comparable to established JVM text components. For JSON and YAML editing, the most pragmatic v1 decision is:

- use Compose for the surrounding UI
- embed `RSyntaxTextArea` via `SwingPanel`
- keep that interop isolated in `studio-code-editor`

This gives us a real editor immediately:

- syntax highlighting
- line numbers
- bracket matching
- code folding
- find/replace
- undo/redo

That is materially better than building a half-editor on top of a `TextField` and then spending weeks rediscovering why text editing is an ancient boss fight.

## Module Layout

### `composeApp`

Responsibilities:

- app entry point
- window lifecycle
- top-level navigation
- menus, toolbars, panes
- screen composition

Should depend on:

- `studio-domain`
- `studio-data`
- `studio-code-editor`
- Compose navigation
- Compose Material 3

Should not contain:

- file parsing logic
- YAML emitters
- provider networking details
- raw Swing editor setup

### `studio-domain`

Responsibilities:

- root app state
- screen models
- selected project / selected endpoint / dirty-state models
- AI action DTOs
- domain events and use-case interfaces

Rules:

- no Swing
- no filesystem access
- no direct HTTP client usage
- no YAML library dependency

This should remain the cleanest module in the Studio build.

### `studio-data`

Responsibilities:

- YAML parsing and stable emission
- JSON formatting helpers
- file open/save and future file watching
- schema-backed validation adapters

This is the correct place for JVM-only dependencies.

### `studio-ai`

Responsibilities:

- AI provider integrations
- prompt building and result parsing
- provider configuration validation

### `studio-code-editor`

Responsibilities:

- wrap `RSyntaxTextArea` in Compose
- expose JSON/YAML editor composables
- centralize editor configuration and interop constraints

This keeps the unavoidable Swing dependency boxed away from the rest of the app.

## Dependency Choices

### Core UI and state

- Compose Multiplatform `1.10.3`
- Kotlin `2.3.20`
- Navigation Compose `org.jetbrains.androidx.navigation:navigation-compose:2.9.2`
- Lifecycle ViewModel Compose `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0`
- Coroutines `1.10.2`
- `kotlinx-coroutines-swing:1.10.2` for desktop `Dispatchers.Main`

### Serialization and local APIs

- `kotlinx-serialization-json:1.10.0`
- Ktor client `3.4.1`
- `ktor-client-content-negotiation:3.4.1`
- `ktor-serialization-kotlinx-json:3.4.1`
- `ktor-client-cio:3.4.1`

Use these for provider integrations and other local Studio networking needs.

### YAML and project formatting

- `org.snakeyaml:snakeyaml-engine:3.0.1`

Why:

- YAML 1.2 parser/emitter
- JVM-native
- safe basic structure model by default

The Studio should keep a canonical in-memory model and emit stable YAML from that model. It should not try to preserve arbitrary user whitespace or comment trivia in v1.

### Editor

- `com.fifesoft:rsyntaxtextarea:3.6.2`

Why:

- mature Swing editor component
- syntax highlighting for JSON/YAML-class configuration content
- line numbering and code folding
- much lower risk than embedding a browser editor runtime for v1 desktop

## Formatting Strategy

### JSON

Use `kotlinx.serialization.json.Json` for API DTOs and editor-side pretty formatting where data is already modeled.

Use a single shared `Json` configuration:

- `prettyPrint = true`
- `ignoreUnknownKeys = true` for companion DTOs
- `encodeDefaults = true` where export stability matters

### YAML

Use SnakeYAML Engine in `studio-data` to:

- parse `.moqproj` files into raw maps/lists
- transform into domain objects
- emit stable YAML with deterministic key ordering where appropriate

The exported file layout should be deterministic first and human-pleasant second.

## Editor Strategy

Use two editing modes:

1. Structured forms for most endpoint metadata
2. Embedded code editor only for large or complex JSON/YAML bodies

That avoids turning the whole app into a generic text editor while still giving advanced users a serious editing surface when they need it.

Recommended rules:

- keep the editor in a fixed pane or dialog to minimize Swing/Compose interop glitches
- do not put Compose popups on top of the embedded editor unless tested carefully
- prefer form-driven editing for small payloads and headers
- promote to fixture files when bodies become large

## Packaging Notes

Use the Compose desktop application DSL in `composeApp` for:

- `run`
- `createDistributable`
- `packageDistributionForCurrentOS`

Plan for JDK 17+ for packaging. Add explicit JDK modules in packaging config once the app surface is larger and we know the exact runtime needs.

## Recommended Next Implementation Slice

1. Land the multi-project Studio scaffold.
2. Implement project open/save in `studio-data`.
3. Add the first endpoint list/detail workflow in `composeApp` backed by `studio-domain`.
4. Wire the JSON editor pane through `studio-code-editor`.
5. Add companion health/provider discovery in `studio-data`.

## Research Sources

Official sources used for this decision:

- Compose project structure: https://kotlinlang.org/docs/multiplatform/compose-multiplatform-create-first-app.html
- Compose Swing interop: https://kotlinlang.org/docs/multiplatform/compose-desktop-swing-interoperability.html
- Compose navigation: https://kotlinlang.org/docs/multiplatform/compose-navigation-routing.html
- Compose ViewModel: https://kotlinlang.org/docs/multiplatform/compose-viewmodel.html
- Compose compatibility/versioning: https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html
- Compose desktop packaging: https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html
- Kotlin serialization: https://kotlinlang.org/docs/serialization.html
- Ktor client serialization: https://ktor.io/docs/client-serialization.html
- StateFlow API: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/
- RSyntaxTextArea project: https://github.com/bobbylight/RSyntaxTextArea
- RSyntaxTextArea Maven coordinates: https://central.sonatype.com/artifact/com.fifesoft/rsyntaxtextarea?smo=true
- SnakeYAML Engine project: https://github.com/snakeyaml/snakeyaml-engine
- SnakeYAML Engine Maven coordinates: https://central.sonatype.com/artifact/org.snakeyaml/snakeyaml-engine
- Gradle multi-project builds: https://docs.gradle.org/current/userguide/multi_project_builds.html
