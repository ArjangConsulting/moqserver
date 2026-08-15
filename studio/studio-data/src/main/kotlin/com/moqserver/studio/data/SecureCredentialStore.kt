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
            return when {
                osName.contains("Mac", ignoreCase = true) -> MacOSKeychainCredentialStore()
                osName.contains("Linux", ignoreCase = true) -> LinuxSecretServiceCredentialStore()
                else -> UnsupportedSecureCredentialStore(osName)
            }
        }
    }
}

/**
 * Thrown when secure persistence cannot be provided. Callers must not fall back to plaintext.
 */
class SecureStorageUnavailableException(osName: String) :
    IllegalStateException("Secure credential storage is not available on $osName.")

private class UnsupportedSecureCredentialStore(
    private val osName: String,
) : SecureCredentialStore {
    override fun read(key: String): String? = null

    override fun write(key: String, value: String) {
        if (value.isBlank()) return
        throw SecureStorageUnavailableException(osName)
    }

    override fun delete(key: String) = Unit
}

private class LinuxSecretServiceCredentialStore(
    private val secretTool: File? = findExecutable("secret-tool"),
) : SecureCredentialStore {
    override fun read(key: String): String? {
        if (secretTool == null) return null
        val result = runCommand("lookup", ATTRIBUTE_NAME, key)
        return when (result.exitCode) {
            0 -> result.stdout.removeTrailingLineBreaks()
            1 -> null
            else -> throw commandFailure(result)
        }
    }

    override fun write(key: String, value: String) {
        if (value.isBlank()) {
            delete(key)
            return
        }
        if (secretTool == null) throw SecureStorageUnavailableException("Linux (secret-tool is not installed)")
        val result = runCommand(
            "store",
            "--label=$SECRET_LABEL",
            ATTRIBUTE_NAME,
            key,
            stdin = value,
        )
        if (result.exitCode != 0) throw commandFailure(result)
    }

    override fun delete(key: String) {
        if (secretTool == null) return
        val result = runCommand("clear", ATTRIBUTE_NAME, key)
        if (result.exitCode !in setOf(0, 1)) throw commandFailure(result)
    }

    private fun runCommand(vararg args: String, stdin: String? = null): CommandResult {
        val executable = checkNotNull(secretTool)
        val process = ProcessBuilder(listOf(executable.absolutePath) + args).start()
        if (stdin != null) process.outputStream.bufferedWriter().use { it.write(stdin) }
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        return CommandResult(process.waitFor(), stdout, stderr)
    }

    private fun commandFailure(result: CommandResult): IllegalStateException =
        IllegalStateException(result.stderr.ifBlank { "secret-tool exited with ${result.exitCode}" })

    companion object {
        private const val ATTRIBUTE_NAME = "moqserver-studio-key"
        private const val SECRET_LABEL = "moqserver Studio API key"
    }
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
        require("\n" !in value && "\r" !in value) { "Credential values must not contain line breaks." }
        // The secret is piped to `security -i` on stdin instead of being passed as a
        // process argument, where it would be visible to other local processes via `ps`.
        val command = "add-generic-password -a ${quoteForSecurityTool(accountName)} " +
            "-s ${quoteForSecurityTool(serviceName(key))} -w ${quoteForSecurityTool(value)} -U\n"
        val result = runSecurityCommand("-i", stdin = command)
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

    private fun runSecurityCommand(vararg args: String, stdin: String? = null): CommandResult {
        check(securityTool.exists()) { "macOS security tool is missing at ${securityTool.path}" }

        val process = ProcessBuilder(listOf(securityTool.path) + args)
            .redirectErrorStream(false)
            .start()

        if (stdin != null) {
            process.outputStream.bufferedWriter().use { it.write(stdin) }
        }
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            logger.warn(
                "Keychain command failed for '{}': {}",
                args.firstOrNull().orEmpty(),
                stderr.ifBlank {
                exitCode.toString()
            },
            )
        }
        return CommandResult(
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr,
        )
    }

    private fun serviceName(key: String): String = "$SERVICE_NAME_PREFIX$key"

    /** Quotes a value for the `security -i` interactive command parser. */
    private fun quoteForSecurityTool(value: String): String {
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

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
        get() = exitCode == ERR_SEC_ITEM_NOT_FOUND ||
            stderr.contains("could not be found", ignoreCase = true)

    private companion object {
        /** `security` exits with 44 (errSecItemNotFound) when no matching keychain entry exists. */
        private const val ERR_SEC_ITEM_NOT_FOUND = 44
    }
}

private fun String.removeTrailingLineBreaks(): String = removeSuffix("\n").removeSuffix("\r")

private fun findExecutable(name: String): File? = System.getenv("PATH")
    ?.split(File.pathSeparatorChar)
    ?.asSequence()
    ?.map { File(it, name) }
    ?.firstOrNull { it.isFile && it.canExecute() }
