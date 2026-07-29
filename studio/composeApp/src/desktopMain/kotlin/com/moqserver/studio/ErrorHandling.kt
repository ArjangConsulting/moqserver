package com.moqserver.studio

import com.moqserver.studio.logging.loggerFor
import kotlinx.coroutines.CancellationException
import java.io.File
import javax.swing.JOptionPane

private val errorLogger = loggerFor<Any>()

internal const val STUDIO_CRASH_LOG_PROPERTY = "studio.crash.log"
internal const val STUDIO_FAIL_FAST_PROPERTY = "studio.debug.failFast"

internal fun installCrashHandlers() {
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        reportFatal("Unhandled exception on ${thread.name}", throwable)
    }
    errorLogger.debug("Crash handlers installed (crash log: {})", crashLogFile().absolutePath)
}

internal fun reportFatal(context: String, throwable: Throwable) {
    val message = buildString {
        append(context)
        append(':')
        append(' ')
        append(throwable.message ?: throwable::class.java.name)
    }
    errorLogger.error("{}", message, throwable)
    appendCrashLog(message, throwable)
    if (isFailFastEnabled()) {
        return
    }
    if (!java.awt.GraphicsEnvironment.isHeadless()) {
        runCatching {
            JOptionPane.showMessageDialog(
                null,
                "$message\n\n${throwable.stackTraceToString()}",
                "moqserver studio crashed",
                JOptionPane.ERROR_MESSAGE,
            )
        }
    }
}

internal fun reportRecoverable(
    context: String,
    throwable: Throwable,
    onUserMessage: (String) -> Unit,
) {
    throwable.rethrowIfCancellation()
    val message = throwable.message ?: context
    errorLogger.error("{}: {}", context, message, throwable)
    appendCrashLog("$context: $message", throwable)
    onUserMessage(message)
}

internal fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}

internal fun isFailFastEnabled(): Boolean =
    System.getProperty(STUDIO_FAIL_FAST_PROPERTY)?.toBooleanStrictOrNull() == true

internal fun propagateFailure(context: String, throwable: Throwable): RuntimeException {
    return when (throwable) {
        is RuntimeException -> throwable
        else -> RuntimeException(context, throwable)
    }
}

private fun appendCrashLog(message: String, throwable: Throwable) {
    runCatching {
        val logFile = crashLogFile()
        logFile.parentFile?.mkdirs()
        logFile.appendText(
            buildString {
                appendLine("[${java.time.Instant.now()}] $message")
                appendLine(throwable.stackTraceToString())
                appendLine()
            },
        )
    }
}

private fun crashLogFile(): File {
    val override = System.getProperty(STUDIO_CRASH_LOG_PROPERTY)
    if (!override.isNullOrBlank()) {
        return File(override)
    }
    val home = System.getProperty("user.home")
    val osName = System.getProperty("os.name").lowercase()
    return when {
        "mac" in osName -> File(home, "Library/Logs/moqserver-studio/studio-crash.log")
        "win" in osName -> File(System.getenv("LOCALAPPDATA") ?: home, "moqserver-studio/studio-crash.log")
        else -> File(home, ".local/state/moqserver-studio/studio-crash.log")
    }
}
