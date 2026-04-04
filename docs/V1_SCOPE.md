# V1 Scope

## Goal

V1 should prove that moqserver can be authored as a local-first project, edited in a desktop app, and executed deterministically by the Swift runtime.

V1 is not the full product vision. It is the smallest version that validates the format, Studio workflow, and direct AI provider integration inside Studio.

## In Scope

### Runtime

- load `.moqproj` directories
- serve REST mocks
- serve GraphQL mocks using documented operation matching rules
- request matching and response variant selection
- auth presence validation already described in the project spec
- admin/runtime controls that still fit the deterministic runtime model

### Format

- project manifest
- endpoint files
- fixtures directory
- YAML file format
- deterministic export rules
- schema-backed and semantic validation

### Studio

- open/save `.moqproj`
- endpoint and variant browser
- structured editing for endpoint metadata
- JSON body editing for variants
- fixture inspection and basic extraction flow
- validation panel for deterministic problems

### Import

- OpenAPI import
- HAR import
- normalization into `.moqproj` models
- user review/edit before save

### AI-Assisted Authoring

- direct provider integrations inside Studio
- provider configuration and validation in Studio settings
- structured AI actions for a narrow set of authoring workflows:
  - analyze spec/project
  - generate variants
  - refine project structure
- bounded use of local or hosted providers for authoring assistance

## Explicitly Out of Scope

- browser Studio
- multi-user collaboration
- live shared editing
- cloud-hosted project sync
- preserving original YAML comments and formatting trivia
- full API mocking beyond REST and GraphQL
- advanced non-JSON response generation
- plugin ecosystem or extension API
- enterprise deployment and team tenancy concerns beyond documented future hooks

## V1 Success Criteria

V1 is successful if a user can:

1. import an API description or start from an example project
2. edit the resulting mock project locally in Studio
3. save it deterministically as `.moqproj`
4. run it through moqserver
5. optionally use local or hosted AI assistance to improve project quality with explicit review

## Nice To Have But Not Required For V1

- fixture extraction heuristics beyond obvious repeats
- advanced search/filter UI in Studio
- rich diff views for AI changes
- packaging polish beyond current OS support
- keychain-backed provider credentials
