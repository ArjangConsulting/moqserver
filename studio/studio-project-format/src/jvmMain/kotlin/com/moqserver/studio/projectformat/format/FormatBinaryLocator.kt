package com.moqserver.studio.projectformat.format

import java.io.File

/**
 * Locates the `moq-format` executable. Checked in order:
 *
 * 1. `MOQSERVER_FORMAT_BINARY` env var — explicit override, used by tests and by anyone running
 *    Studio from source against a locally built binary.
 * 2. `compose.application.resources.dir` — the directory Compose Desktop's packaging plugin
 *    stages `nativeDistributions.appResourcesRootDir` into, and points this system property at
 *    for both `:composeApp:run` and a packaged, installed app (JPackage sets it to
 *    `$APPDIR/resources` in the launcher's own JVM args). Studio's `composeApp/build.gradle.kts`
 *    points `appResourcesRootDir` at `composeApp/app-resources/`, where CI places the
 *    platform-matching `moq-format` binary — under a per-platform subdirectory
 *    (`macos-arm64/moq-format`, `linux-x64/moq-format`; only files nested one level under an
 *    `<os>-<arch>` or `common` folder are picked up, confirmed by inspecting what actually landed
 *    in a locally built `.app` — a flat `app-resources/moq-format` is silently ignored) — before
 *    packaging (see `release.yml`'s `studio-macos`/`studio-linux` jobs). This is the path that
 *    matters for a shipped `.app`/`.deb`; verified end to end by launching a locally packaged
 *    `.app` standalone (no env vars) and confirming it spawned the bundled binary.
 * 3. `moq-format` on `$PATH` — a reasonable fallback for local development.
 *
 * Throws rather than returning null: callers (`FormatProcess`) want a clear "could not locate"
 * failure surfaced through `FormatServiceState.Unavailable`, not a silent empty string.
 */
object FormatBinaryLocator {
    class NotFoundException(message: String) : Exception(message)

    fun locate(): String {
        System.getenv("MOQSERVER_FORMAT_BINARY")?.takeIf { it.isNotBlank() }?.let { override ->
            val file = File(override)
            if (file.canExecute()) return file.absolutePath
            throw NotFoundException("MOQSERVER_FORMAT_BINARY is set to \"$override\" but it is not an executable file")
        }

        System.getProperty("compose.application.resources.dir")?.let { resourcesDir ->
            val bundled = File(resourcesDir, "moq-format")
            if (bundled.canExecute()) return bundled.absolutePath
        }

        val pathDirs = System.getenv("PATH")?.split(File.pathSeparator).orEmpty()
        for (dir in pathDirs) {
            val candidate = File(dir, "moq-format")
            if (candidate.canExecute()) return candidate.absolutePath
        }

        throw NotFoundException(
            "Could not locate the moq-format binary. Set MOQSERVER_FORMAT_BINARY, or ensure it is bundled with " +
                "this build or present on PATH.",
        )
    }
}
