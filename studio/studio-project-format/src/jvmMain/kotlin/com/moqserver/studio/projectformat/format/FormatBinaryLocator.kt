package com.moqserver.studio.projectformat.format

import java.io.File

/**
 * Locates the `moq-format` executable. Checked in order:
 *
 * 1. `MOQSERVER_FORMAT_BINARY` env var — explicit override, used by tests and by anyone running
 *    Studio from source against a locally built binary.
 * 2. A path next to the running JVM's resources, `app-resources/moq-format` — where the packaged
 *    app places the binary bundled into the distribution (see composeApp's `nativeDistributions`
 *    wiring; this is the only path that matters for a shipped `.app`/`.deb`).
 * 3. `moq-format` on `$PATH` — a reasonable fallback for local development.
 *
 * Throws rather than returning null: callers (`FormatProcess`) want a clear "could not locate"
 * failure surfaced through `FormatServiceState.Unavailable`, not a silent empty string.
 */
object FormatBinaryLocator {
    class NotFoundException(message: String) : Exception(message)

    fun locate(appResourcesDir: File? = null): String {
        System.getenv("MOQSERVER_FORMAT_BINARY")?.takeIf { it.isNotBlank() }?.let { override ->
            val file = File(override)
            if (file.canExecute()) return file.absolutePath
            throw NotFoundException("MOQSERVER_FORMAT_BINARY is set to \"$override\" but it is not an executable file")
        }

        appResourcesDir?.let { dir ->
            val bundled = File(dir, "moq-format")
            if (bundled.canExecute()) return bundled.absolutePath
        }

        val pathDirs = System.getenv("PATH")?.split(File.pathSeparator).orEmpty()
        for (dir in pathDirs) {
            val candidate = File(dir, "moq-format")
            if (candidate.canExecute()) return candidate.absolutePath
        }

        throw NotFoundException(
            "Could not locate the moq-format binary. Set MOQSERVER_FORMAT_BINARY, or ensure it is bundled with " +
                "this build or present on PATH.")
    }
}
