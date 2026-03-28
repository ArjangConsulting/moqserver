package com.moqserver.studio.data

import com.moqserver.studio.logging.loggerFor
import java.io.File

interface SecureCredentialStore {
    fun read(key: String): String?

    fun write(key: String, value: String)

    fun delete(key: String)

    companion object {
        fun default(): SecureCredentialStore {
            val osName = System.getProperty("os.name").orEmpty()
            return if (osName.contains("Mac", ignoreCase = true)) {
                MacOSKeychainCredentialStore()
            } else {
                UnsupportedSecureCredentialStore(osName)
            }
        }
    }
}

private class UnsupportedSecureCredentialStore(
    private val osName: String,
) : SecureCredentialStore {
    override fun read(key: String): String? = null

    override fun write(key: String, value: String) {
        if (value.isBlank()) return
        throw IllegalStateException("Secure credential storage is not available on $osName.")
    }

    override fun delete(key: String) = Unit
}

private class MacOSKeychainCredentialStore(
    private val securityTool: File = File(SECURITY_TOOL_PATH),
    private val accountName: String = KEYCHAIN_ACCOUNT_NAME,
) : SecureCredentialStore {
    private val logger = loggerFor<MacOSKeychainCredentialStore>()

    override fun read(key: String): String? {
        val result = runSecurityCommand(
            "find-generic-password",
            "-a",
            accountName,
            "-s",
            serviceName(key),
            "-w",
        )
        return when {
            result.exitCode == 0 -> result.stdout.removeTrailingLineBreaks()
            result.isMissingEntry -> null
            else -> throw IllegalStateException(result.stderr.ifBlank { "security exited with ${result.exitCode}" })
        }
    }

    override fun write(key: String, value: String) {
        if (value.isBlank()) {
            delete(key)
            return
        }
        val result = runSecurityCommand(
            "add-generic-password",
            "-a",
            accountName,
            "-s",
            serviceName(key),
            "-w",
            value,
            "-U",
        )
        if (result.exitCode != 0) {
            throw IllegalStateException(result.stderr.ifBlank { "security exited with ${result.exitCode}" })
        }
    }

    override fun delete(key: String) {
        val result = runSecurityCommand(
            "delete-generic-password",
            "-a",
            accountName,
            "-s",
            serviceName(key),
        )
        if (result.exitCode != 0 && !result.isMissingEntry) {
            throw IllegalStateException(result.stderr.ifBlank { "security exited with ${result.exitCode}" })
        }
    }

    private fun runSecurityCommand(vararg args: String): CommandResult {
        check(securityTool.exists()) { "macOS security tool is missing at ${securityTool.path}" }

        val process = ProcessBuilder(listOf(securityTool.path) + args)
            .redirectErrorStream(false)
            .start()

        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            logger.warn("Keychain command failed for '{}': {}", args.firstOrNull().orEmpty(), stderr.ifBlank { exitCode.toString() })
        }
        return CommandResult(
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr,
        )
    }

    private fun serviceName(key: String): String = "$SERVICE_NAME_PREFIX$key"

    companion object {
        private const val SECURITY_TOOL_PATH = "/usr/bin/security"
        private const val KEYCHAIN_ACCOUNT_NAME = "moqserver.studio"
        private const val SERVICE_NAME_PREFIX = "com.moqserver.studio."
    }
}

private data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val isMissingEntry: Boolean
        get() = stderr.contains("could not be found", ignoreCase = true)
}

private fun String.removeTrailingLineBreaks(): String = removeSuffix("\n").removeSuffix("\r")
