# Export References — Code Generation

This document covers the **Export References** feature, which generates source code
constants from a project's endpoint and variant reference names.

## Architecture

```
studio-export          Pure generation logic (no Compose, no I/O)
  depends on -> studio-project-format (MoqProject, ProjectVariant, etc.)

composeApp             UI + file writing
  depends on -> studio-export
```

`studio-export` is intentionally free of desktop, Compose, Swing, AWT, and file I/O
dependencies. It takes a `MoqProject` and returns `List<GeneratedFile>` in memory.
This means a future CLI module can reuse `studio-export` unchanged.

## Supported Languages

| Language   | Output file     | Top-level construct                          |
|------------|-----------------|----------------------------------------------|
| Kotlin     | `MoqAPIs.kt`   | `object MoqAPIs` with nested objects + enums |
| Java       | `MoqAPIs.java`  | `final class MoqAPIs` with nested classes    |
| JavaScript | `moq-apis.js`   | ES module with `Object.freeze()` objects     |
| Swift      | `MoqAPIs.swift` | Caseless `enum MoqAPIs` with nested enums    |

## Key Types

### `studio-export` module

| Type                 | Role                                                           |
|----------------------|----------------------------------------------------------------|
| `ExportLanguage`     | Enum: KOTLIN, JAVA, JAVASCRIPT, SWIFT                         |
| `ExportOptions`      | Selected languages, description toggles, package names         |
| `ExportCatalog`      | Stable, sorted intermediate model built from `MoqProject`      |
| `ExportEndpoint`     | Endpoint entry (referenceName, method, path, description, variants) |
| `ExportVariant`      | Variant entry nested under endpoint                            |
| `GeneratedFile`      | fileName + content string                                      |
| `LanguageExporter`   | Interface: `generate(catalog, options) -> GeneratedFile`       |
| `ExportRegistry`     | Returns built-in exporters, drives batch generation            |
| `ExportCatalogBuilder` | Builds `ExportCatalog` from `MoqProject`                    |
| `SymbolSanitizer`    | Per-language reserved-word escaping and case converters         |

### `composeApp` module

| Type                      | Role                                              |
|---------------------------|---------------------------------------------------|
| `ExportReferencesState`   | UI state (selected languages, packages, folder)   |
| `ExportReferencesScreen`  | Composable: language cards, options, export button |

## Data Flow

1. User opens **File > Export References...** (enabled when a project is loaded).
2. A new `Window` opens with `ExportReferencesScreen`.
3. User selects languages, sets optional packages, toggles descriptions, picks a
   destination folder.
4. On **Export**:
   - `ExportCatalogBuilder.build(project)` produces a sorted `ExportCatalog`.
   - `ExportRegistry.generate(catalog, options)` produces one `GeneratedFile` per
     selected language.
   - Files are written to the destination folder on `Dispatchers.IO`.

## Design Constraints

- **Variant reference names are only unique within an endpoint**, not project-wide.
  Variants must always be nested under their endpoint in generated code.
- Generated comments always include the endpoint's `URL partial path: /...`.
- Endpoint (API) descriptions are included only when `includeApiDescriptions` is
  enabled in `ExportOptions` and the values are non-null/non-blank.
- Variant descriptions are included only when `includeVariantDescriptions` is
  enabled in `ExportOptions` and the values are non-null/non-blank.
- The two description toggles are independent — users can include API descriptions
  without variant descriptions, or vice versa.
- Structural docs (e.g. `URL partial path`) are always emitted regardless of the
  descriptions toggle.

## Symbol Sanitization

Each language exporter uses `SymbolSanitizer` to handle:

- Reserved keyword escaping (language-specific strategies: backtick-escaping for
  Kotlin/Swift, underscore-prefixing for Java/JavaScript).
- PascalCase conversion for type/object names.
- camelCase conversion for property names.
- UPPER_SNAKE_CASE conversion for Java enum constants.

## Module Package Structure

```
studio-export/src/main/kotlin/com/moqserver/studio/export/
  ExportModels.kt                        Public API: enums, data classes, GeneratedFile
  ExportCatalogBuilder.kt                Public API: MoqProject → ExportCatalog
  LanguageExporter.kt                    Public API: interface + ExportRegistry
  support/
    SymbolSanitizer.kt                   Internal: keyword escaping, case converters
    ExportSupport.kt                     Internal: name deduplication, comment sanitization
  lang/
    kotlin/KotlinExporter.kt             Internal: Kotlin code generator
    java/JavaExporter.kt                 Internal: Java code generator
    javascript/JavaScriptExporter.kt     Internal: JavaScript code generator
    swift/SwiftExporter.kt               Internal: Swift code generator
```

Tests mirror the same package layout under `src/test/`.

## Adding a New Language

1. Create `lang/newlang/NewLangExporter.kt` implementing `LanguageExporter`.
2. Add the language to `ExportLanguage` enum in `ExportModels.kt`.
3. Add a reserved-words set and identifier sanitizer in `support/SymbolSanitizer.kt` if needed.
4. Register the exporter in `ExportRegistry` (`LanguageExporter.kt`).
5. Add a corresponding icon mapping in `ExportReferencesScreen.languageIcon()`.
6. Add tests in `lang/newlang/NewLangExporterTest.kt`.

## Testing

```bash
# Export module tests
cd studio && ./gradlew :studio-export:test

# Project format tests (variant description round-trip)
cd studio && ./gradlew :studio-project-format:jvmTest

# Full test suite
cd studio && ./gradlew test

# Compile check
cd studio && ./gradlew :composeApp:compileKotlinDesktop
```

## Files

### Created
- `studio-export/build.gradle.kts`
- `studio-export/src/main/kotlin/com/moqserver/studio/export/ExportModels.kt`
- `studio-export/src/main/kotlin/com/moqserver/studio/export/ExportCatalogBuilder.kt`
- `studio-export/src/main/kotlin/com/moqserver/studio/export/LanguageExporter.kt`
- `studio-export/src/main/kotlin/com/moqserver/studio/export/support/SymbolSanitizer.kt`
- `studio-export/src/main/kotlin/com/moqserver/studio/export/support/ExportSupport.kt`
- `studio-export/src/main/kotlin/com/moqserver/studio/export/lang/kotlin/KotlinExporter.kt`
- `studio-export/src/main/kotlin/com/moqserver/studio/export/lang/java/JavaExporter.kt`
- `studio-export/src/main/kotlin/com/moqserver/studio/export/lang/javascript/JavaScriptExporter.kt`
- `studio-export/src/main/kotlin/com/moqserver/studio/export/lang/swift/SwiftExporter.kt`
- `studio-export/src/test/kotlin/...` (per-package test files mirroring source layout)
- `composeApp/src/desktopMain/kotlin/com/moqserver/studio/ExportReferencesScreen.kt`

### Modified
- `studio-project-format/.../ProjectModels.kt` — added `description` to `ProjectVariant`
- `studio-project-format/.../YamlProjectCodec.kt` — codec reads/writes variant description
- `studio-project-format/.../ProjectRepositoryTest.kt` — description round-trip tests
- `composeApp/.../endpointdetail/VariantSummaryTab.kt` — description editor UI
- `composeApp/build.gradle.kts` — `studio-export` dependency
- `settings.gradle.kts` — `studio-export` module include
- `composeApp/.../Main.kt` — export menu item, window, and wiring
