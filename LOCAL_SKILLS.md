# Local Skills

Repo-local skill index for `moqserver` and especially `studio/` work.

## Existing Built-In Skills We Already Use

- `compose-multiplatform-patterns`
- `qwen35-ollama`

## Local Skills We Added

### Architecture and UI

- `moqserver-studio-compose-architecture`
  - Use for Studio screen architecture, state ownership, module boundaries, and workflow placement.
- `moqserver-studio-state-machines`
  - Use for `StudioState` transitions, nested workflow states, and undo/redo or dirty-state invariants.
- `moqserver-studio-theme-debugging`
  - Use for dark mode, light mode, tokens, preview checks, and theme regressions.
- `moqserver-design-system-and-components`
  - Use for reusable component patterns, token usage, badges, spacing, and consistency reviews.
- `moqserver-compose-performance`
  - Use for recomposition review, list performance, remembered state, and UI responsiveness.

### Kotlin Async and State

- `moqserver-kotlin-coroutines-flows`
  - Use for `StateFlow`, async workflows, IO boundaries, sequential processing, and ViewModel transitions.

### AI, Import, Persistence

- `moqserver-studio-ai-workflows`
  - Use for provider registry wiring, prompt boundaries, import-time AI, and AI action execution.
- `moqserver-llm-prompting-for-mocks`
  - Use for improving AI prompts, structured variant output, and parser-friendly mock generation.
- `moqserver-import-and-conversion`
  - Use for OpenAPI/HAR import parsing, import review state, and `ImportConverter` behavior.
- `moqserver-openapi-import-quality`
  - Use for OpenAPI parser fidelity, examples, schema stubs, auth extraction, and imported-project usefulness.
- `moqserver-project-format-persistence`
  - Use for `.moqproj` load/save behavior, fixture persistence, cleanup, and project-format invariants.
- `moqserver-variant-and-reference-naming`
  - Use for alias generation, deterministic variant naming, reference-name collisions, and naming normalization.
- `moqserver-project-validation-rules`
  - Use for `ProjectValidator`, diagnostics design, persisted invariants, auth rules, and GraphQL checks.
- `moqserver-fixtures-and-body-files`
  - Use for fixture generation, `body` vs `bodyFile`, content-type extensions, and cleanup behavior.

### Testing

- `moqserver-studio-testing-playbook`
  - Use for choosing the right test module and the fastest useful Gradle task.

### UX and Accessibility

- `moqserver-accessibility-and-keyboard-ux`
  - Use for desktop shortcuts, content descriptions, tooltips, and keyboard-friendly Compose desktop UX.

### Diagnostics and Desktop Behavior

- `moqserver-error-handling-and-diagnostics`
  - Use for recoverable vs fatal failures, status-line behavior, transient diagnostics, and validation surfacing.
- `moqserver-logging-and-observability`
  - Use for workflow logging, provider/debug visibility, and log-level decisions.
- `moqserver-desktop-file-workflows`
  - Use for open/save/import dialogs, recent projects, OS file-open integration, and unsaved-change guards.
- `moqserver-preferences-and-provider-settings`
  - Use for AI settings, theme preference persistence, provider drafts, connection testing, and registry refresh behavior.
- `moqserver-review-checklists`
  - Use for final implementation review across architecture, UI, async flows, diagnostics, and testing.

## Recommended Combos

### New Studio Feature

- `moqserver-studio-compose-architecture`
- `moqserver-studio-state-machines`
- `moqserver-kotlin-coroutines-flows`
- `moqserver-studio-testing-playbook`

### Dark/Light Mode Bug

- `moqserver-studio-theme-debugging`
- `moqserver-design-system-and-components`
- `moqserver-studio-testing-playbook`

### AI Feature

- `moqserver-studio-ai-workflows`
- `moqserver-llm-prompting-for-mocks`
- `qwen35-ollama`
- `moqserver-kotlin-coroutines-flows`
- `moqserver-studio-testing-playbook`

### Import Feature

- `moqserver-import-and-conversion`
- `moqserver-openapi-import-quality`
- `moqserver-studio-compose-architecture`
- `moqserver-studio-testing-playbook`

### Keyboard and Discoverability Work

- `moqserver-accessibility-and-keyboard-ux`
- `moqserver-design-system-and-components`

### Persistence or Save/Load Change

- `moqserver-project-format-persistence`
- `moqserver-variant-and-reference-naming`
- `moqserver-fixtures-and-body-files`
- `moqserver-project-validation-rules`
- `moqserver-studio-testing-playbook`

### Error Handling or Workflow Robustness

- `moqserver-error-handling-and-diagnostics`
- `moqserver-logging-and-observability`
- `moqserver-studio-state-machines`

### Desktop Open/Save/Import Behavior

- `moqserver-desktop-file-workflows`
- `moqserver-error-handling-and-diagnostics`

### Preferences and Provider Configuration

- `moqserver-preferences-and-provider-settings`
- `moqserver-studio-ai-workflows`

### Final Review Pass

- `moqserver-review-checklists`
- `moqserver-studio-testing-playbook`

## Current Limitation

- These local skills exist on disk under `~/.agents/skills/`.
- A fresh agent session may be needed before the runtime skill registry can load and invoke them by name.
