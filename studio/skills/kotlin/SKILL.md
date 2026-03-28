# Kotlin Skill

Use this guidance when editing Kotlin in Studio modules.

## Rules

- Keep `.moqproj` behavior in `studio-project-format`.
- Keep workflow and state transitions in `studio-domain`.
- Keep parser and network adapters in `studio-data`.
- Prefer immutable data and pure transformations.
- Add tests with any parser, validator, or serializer change.

## Verification

- `./gradlew :studio-project-format:test`
- `./gradlew :studio-domain:test`

## Red Flags

- New `.moqproj` logic outside `studio-project-format`
- Duplicate validation rules across modules
- Filesystem access inside UI or view-model code
