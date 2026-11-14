# Studio response editor verification

Verified on macOS on 2026-11-06 against the local editor composite containing the fixes through
`28790a500c93efc717acf03ab475f988e236087d`. Existing unrelated work was preserved.

## Automated checks

The full Studio task set passed:

```bash
./gradlew --no-configuration-cache test allTests desktopTest detektAll :composeApp:packageDistributionForCurrentOS
```

The relevant suites were also rerun with `MOQSERVER_FORMAT_BINARY` explicitly pointing at the freshly
built `server/.build/release/moq-format`: 132 desktop, 110 domain, and 66 project-format tests,
with zero failures, errors, or skips. Desktop tests, lint, and DMG packaging passed again after the
final visual changes. Workflow changes passed `actionlint`; the artifact probe passed `bash -n`.

Focused coverage includes endpoint and variant switching with identical initial content,
external AI-style body replacements, local drafts, Save/Cancel, unchanged fixture preservation,
changed fixture conversion to inline content, invalid JSON literals, diagnostics, asynchronous
format cancellation, stale-result rejection, exact format/undo restoration, and Tab/Shift+Tab
focus traversal. A 50,000-character string with escapes and a precise numeric literal survives
format/undo byte-for-byte. The UI fixture reveals the tail beyond 16,000 characters, formats it,
undoes, and saves without changing its file-backed body or firing an update callback.

## Reviewed captures

These are headless Compose captures of the real Studio body pane, rather than native window or
assistive-technology evidence. The dialog capture includes the test scene's unused black background.
The panel intentionally stays dark in both application themes. Its controls receive Studio's dark
theme and content color so the Copy icon and action labels remain visible in light mode.

| State | Capture |
| --- | --- |
| Light app, long wrapped string tail | [tail](evidence/body-editor-20261106/body-light-long-tail.png) |
| Light app, JSON diagnostic and explicit validation error | [diagnostic](evidence/body-editor-20261106/body-light-diagnostics.png) |
| Dark app, 500 dp pane and selection | [selection](evidence/body-editor-20261106/body-dark-narrow-selection.png) |
| Dialog above the body editor | [overlay](evidence/body-editor-20261106/body-dark-overlay.png) |

## Publication handoff

Studio, security, and release workflows retain a current editor source pin until
`CODE_EDITOR_PUBLISHED=true` is explicitly configured. Published mode omits the editor checkout,
disables sibling composites, checks the root/JVM POMs, and compiles against Maven Central.
Optional local composites remain available for development.

The public `0.1.0` POM probe returned HTTP 404 on this date. Gradle consumption without editor
substitution therefore remains pending publication; the source fallback remains active.
See [Studio README](../studio/README.md#response-body-editor-and-dependency-publication) for the
activation and local verification commands.

Library [release preflight](https://github.com/ArjangConsulting/kmp-code-editor/actions/runs/42307080067)
passed its canonical build checks, accepted the Central token for a read-only Publisher API request,
and verified POM metadata plus 29 signatures across five publications. The overall workflow failed
because the GitHub App installation lacks one or more requested write permissions. Namespace
ownership and public signing-key availability were not established by those checks.
