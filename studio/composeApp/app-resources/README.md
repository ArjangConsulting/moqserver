# app-resources

Compose Desktop's `nativeDistributions.appResourcesRootDir`. Contents land in the packaged app and
are exposed at runtime via the `compose.application.resources.dir` system property (see
`FormatBinaryLocator`).

**Files must sit under a per-platform subdirectory** — `macos-arm64/`, `linux-x64/` (or `common/`
for files every platform should get). A file placed directly at this directory's root is silently
ignored; this isn't documented anywhere obvious and was confirmed by inspecting what actually
landed inside a locally built `.app`.

CI (`release.yml`'s `studio-macos`/`studio-linux` jobs) downloads the platform-matching
`moq-format` archive from the `format-binary` job and extracts it to `<platform>/moq-format` before
running `packageDmg`/`packageDeb`. Nothing under this directory is committed except this file —
the binaries are build artifacts, gitignored below.

To test packaging locally: build `moq-format` for your platform (`cd server && swift build -c
release --product moq-format`), copy the binary to `app-resources/macos-arm64/moq-format` (adjust
for your platform/arch), `chmod +x` it, then run `./gradlew :composeApp:createDistributable` (or
`:packageDmg`/`:packageDeb`). Verified this actually works end to end: the resulting
`.app/Contents/app/resources/moq-format` launches standalone (no env vars) and `FormatProcess`
successfully spawns it.
