# Compose Multiplatform Skill

Use this guidance when editing Studio UI.

## Rules

- Keep shared UI in `composeApp/src/commonMain` unless JVM APIs are required.
- Keep file dialogs, app startup, and desktop integration in `desktopMain`.
- Pass state and callbacks from `StudioRootViewModel`; avoid local app truth.
- Do not move `.moqproj` serialization or validation into composables.

## Verification

- `./gradlew :composeApp:desktopTest`
- `./gradlew :composeApp:compileKotlinDesktop`

## Red Flags

- File I/O inside composables
- Platform dialog code in `commonMain`
- UI code inventing format defaults separate from `studio-project-format`
