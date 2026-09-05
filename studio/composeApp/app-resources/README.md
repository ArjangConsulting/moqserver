# app-resources

Compose Desktop's `nativeDistributions.appResourcesRootDir`. Contents land in the packaged app and
are exposed at runtime via the `compose.application.resources.dir` system property (see
`FormatBinaryLocator`).

**Files must sit under a per-platform subdirectory** — `macos-arm64/`, `linux-x64/` (or `common/`
for files every platform should get). A file placed directly at this directory's root is silently
ignored; this isn't documented anywhere obvious and was confirmed by inspecting what actually
landed inside a locally built `.app`.

Locally, `composeApp/build.gradle.kts`'s `bundleFormatBinary` task does this automatically: it runs
`swift build -c release --product moq-format` against `../../server` and stages the result under
`<platform>/moq-format` for you, on macOS-arm64 and Linux-x64 hosts (the only platforms CI builds
moq-format for). It's a dependency of `run`, `createDistributable`, `packageDmg`, and `packageDeb`,
and skips itself (falling back to `MOQSERVER_FORMAT_BINARY`/`$PATH`) on an unrecognized platform or
when no Swift toolchain is on `$PATH` — so it degrades gracefully rather than blocking a build.
Gradle's up-to-date check keys off `server/Sources/{MoqCore,MoqFormat,MoqFormatServiceRun}` and
`Package.swift`/`Package.resolved`, so it only re-runs `swift build` when those actually change.

CI (`release.yml`'s `studio-macos`/`studio-linux` jobs) instead downloads the platform-matching
`moq-format` archive from the `format-binary` job and extracts it to `<platform>/moq-format` before
running `packageDmg`/`packageDeb` — it doesn't invoke `bundleFormatBinary`, since each release
platform is built on its own runner rather than the packaging host. Nothing under this directory is
committed except this file — the binaries are build artifacts, gitignored below.

Verified end to end: the resulting `.app/Contents/app/resources/moq-format` launches standalone (no
env vars) and `FormatProcess` successfully spawns it.
