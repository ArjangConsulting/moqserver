# Studio Implementation Plan

## Goal

Build a desktop-first authoring tool that can reliably create, inspect, edit, validate, import, and later AI-refine `.moqproj` projects.

## Module Responsibilities

### `composeApp`

- entry point
- window lifecycle
- navigation
- top-level screens
- menus, dialogs, panes

### `studio-domain`

- root state
- feature state
- UI-facing domain models
- use-case interfaces

### `studio-data`

- filesystem IO
- YAML load/save
- validation adapters
- import pipelines

### `studio-ai`

- AI provider integrations
- prompt building and response parsing
- provider configuration validation

### `studio-code-editor`

- JSON/YAML editing surface
- Swing interop wrapper
- editor-specific configuration

## State Management

Studio should use:

- `ViewModel`
- `StateFlow`
- explicit intent handlers
- derived UI state from a single source of truth

The app should not introduce a larger framework until the basic editing workflow proves insufficient.

## Feature Slices

## Slice 1: Project Open/Save

User outcome:

- open a `.moqproj`
- inspect loaded content
- save it back unchanged or with simple metadata edits

Required work:

- file selection flow
- domain mapping from disk
- dirty-state tracking
- save and save-as flow

Exit criteria:

- sample project can be opened and saved deterministically

## Slice 2: Endpoint Browser

User outcome:

- browse endpoints
- inspect variants
- switch selection cleanly

Required work:

- left navigation or split-pane endpoint tree
- detail pane routing
- selection state persistence

Exit criteria:

- project navigation works without editing conflicts

## Slice 3: Variant Editing

User outcome:

- edit variant metadata and status codes
- edit inline JSON bodies
- point variants at fixture files

Required work:

- structured forms
- JSON editor integration
- validation feedback in-editor or adjacent panel

Exit criteria:

- variant edits persist through save/load

## Slice 4: Validation Experience

User outcome:

- see deterministic errors before export or save
- jump from validation item to the relevant entity

Required work:

- validation service integration
- diagnostics model
- UI panel for errors and warnings

Exit criteria:

- invalid projects surface actionable diagnostics

## Slice 5: OpenAPI Import

User outcome:

- import an OpenAPI description into an editable project

Required work:

- import pipeline
- normalization rules
- alias and grouping defaults
- import summary screen

Exit criteria:

- imported project saves and runs through moqserver

## Slice 6: HAR Import

User outcome:

- import real captured traffic into a project draft

Required work:

- HAR parsing
- request grouping
- path normalization
- sanitization and redaction of sensitive values

Exit criteria:

- noisy captures become editable draft endpoints

## Slice 7: AI Provider Configuration

User outcome:

- Studio can configure a default AI provider and validate provider connectivity

Required work:

- settings UI for provider selection and credentials
- direct provider validation flow
- provider availability/status handling

Exit criteria:

- Studio shows provider availability and meaningful failures without requiring a separate local server

## Slice 8: AI-Assisted Actions

User outcome:

- request AI help for bounded authoring tasks
- review and accept structured changes

Required work:

- action entry points in Studio
- previewable result model
- accept/reject flow

Exit criteria:

- AI suggestions are structured, inspectable, and never silently applied

## UI Surfaces Required In V1

- project chooser/open screen
- endpoint list/tree
- detail pane
- variant JSON editor pane
- validation panel
- import entry points
- AI provider settings screen

## Risks

- Compose and Swing interop layout glitches
- editor focus behavior
- large file handling in a single editor pane
- drift between Studio rules and runtime rules

## Recommended First Code Slice

After docs are approved:

1. open/save project
2. endpoint browser
3. variant editing
4. save validation
