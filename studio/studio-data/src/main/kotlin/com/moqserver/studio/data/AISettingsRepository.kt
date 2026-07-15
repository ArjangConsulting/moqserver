package com.moqserver.studio.data

import com.moqserver.studio.logging.loggerFor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class AISettingsRepository(
    private val settingsFile: File = defaultSettingsFile(),
    private val credentialStore: SecureCredentialStore = SecureCredentialStore.default(),
) {
    private val logger = loggerFor<AISettingsRepository>()
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun load(): AISettings {
        val fileSettings = if (!settingsFile.exists()) {
            AISettings()
        } else {
            try {
                json.decodeFromString<AISettings>(settingsFile.readText())
            } catch (e: Exception) {
                logger.warn("Failed to load AI settings from ${settingsFile.path}: ${e.message}. Using defaults.")
                AISettings()
            }
        }
        val openAI = loadCredential(OPENAI_API_KEY_CREDENTIAL, fileSettings.openai.apiKey)
        val anthropic = loadCredential(ANTHROPIC_API_KEY_CREDENTIAL, fileSettings.anthropic.apiKey)
        val gemini = loadCredential(GEMINI_API_KEY_CREDENTIAL, fileSettings.gemini.apiKey)
        val loaded = fileSettings.copy(
            openai = fileSettings.openai.copy(
                apiKey = openAI.value,
            ),
            anthropic = fileSettings.anthropic.copy(
                apiKey = anthropic.value,
            ),
            gemini = fileSettings.gemini.copy(
                apiKey = gemini.value,
            ),
        )
        if (listOf(openAI, anthropic, gemini).all(CredentialLoad::migrationSucceeded)) {
            removeLegacyCredentials(fileSettings)
        }
        return loaded
    }

    fun save(settings: AISettings) {
        val persistedSettings = settings.copy(
            openai = settings.openai.copy(apiKey = ""),
            anthropic = settings.anthropic.copy(apiKey = ""),
            gemini = settings.gemini.copy(apiKey = ""),
        )

        val updates = listOf(
            OPENAI_API_KEY_CREDENTIAL to settings.openai.apiKey,
            ANTHROPIC_API_KEY_CREDENTIAL to settings.anthropic.apiKey,
            GEMINI_API_KEY_CREDENTIAL to settings.gemini.apiKey,
        )
        val previous = updates.associate { (key, _) -> key to credentialStore.read(key) }
        try {
            updates.forEach { (key, value) -> persistCredential(key, value) }
            writeSettingsAtomically(persistedSettings)
            logger.info("Saved AI settings to ${settingsFile.path}")
        } catch (error: Exception) {
            previous.forEach { (key, value) ->
                runCatching {
                    if (value == null) credentialStore.delete(key) else credentialStore.write(key, value)
                }.onFailure(error::addSuppressed)
            }
            throw error
        }
    }

    private fun loadCredential(
        credentialKey: String,
        legacyFallback: String,
    ): CredentialLoad = try {
        val stored = credentialStore.read(credentialKey)
        if (stored != null || legacyFallback.isBlank()) {
            CredentialLoad(stored.orEmpty(), migrationSucceeded = true)
        } else {
            credentialStore.write(credentialKey, legacyFallback)
            CredentialLoad(legacyFallback, migrationSucceeded = true)
        }
        } catch (e: Exception) {
        logger.warn("Failed to load secure credential '$credentialKey': ${e.message}")
        CredentialLoad(legacyFallback, migrationSucceeded = legacyFallback.isBlank())
    }

    private fun writeSettingsAtomically(settings: AISettings) {
        val parent = settingsFile.absoluteFile.parentFile
            ?: error("Settings file must have a parent directory: ${settingsFile.path}")
        parent.mkdirs()
        val temporary = File.createTempFile("${settingsFile.name}.", ".tmp", parent)
        try {
            temporary.writeText(json.encodeToString(settings))
            try {
                Files.move(
                    temporary.toPath(),
                    settingsFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), settingsFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun persistCredential(
        credentialKey: String,
        value: String,
    ) {
        try {
            if (value.isBlank()) {
                credentialStore.delete(credentialKey)
            } else {
                credentialStore.write(credentialKey, value)
            }
        } catch (e: SecureStorageUnavailableException) {
            throw IllegalStateException(
                "API keys cannot be saved because secure credential storage is unavailable. ${e.message}",
                e,
            )
        } catch (e: Exception) {
            throw IllegalStateException("Failed to save secure credential '$credentialKey': ${e.message}", e)
        }
    }

    private fun removeLegacyCredentials(settings: AISettings) {
        val legacyKeys = listOf(settings.openai.apiKey, settings.anthropic.apiKey, settings.gemini.apiKey)
        if (!settingsFile.isFile || legacyKeys.all(String::isBlank)) {
            return
        }
        val root = json.parseToJsonElement(settingsFile.readText()).jsonObject
        val sanitized = root.mapValues { (key, value) ->
            if (key in HOSTED_PROVIDER_KEYS && value is JsonObject) {
                JsonObject(value + (API_KEY_FIELD to kotlinx.serialization.json.JsonPrimitive("")))
            } else {
                value
            }
        }
        settingsFile.writeText(json.encodeToString(JsonObject(sanitized)))
        logger.warn("Removed legacy plaintext API keys from ${settingsFile.path}")
    }

    companion object {
        private const val OPENAI_API_KEY_CREDENTIAL = "openai.api-key"
        private const val ANTHROPIC_API_KEY_CREDENTIAL = "anthropic.api-key"
        private const val GEMINI_API_KEY_CREDENTIAL = "gemini.api-key"
        private const val SETTINGS_DIR = ".moqserver"
        private const val SETTINGS_FILENAME = "studio-settings.json"
        private const val API_KEY_FIELD = "apiKey"
        private val HOSTED_PROVIDER_KEYS = setOf("openai", "anthropic", "gemini")

        fun defaultSettingsFile(): File {
            val home = System.getProperty("user.home")
            return File(home, "$SETTINGS_DIR/$SETTINGS_FILENAME")
        }
    }

    private data class CredentialLoad(
        val value: String,
        val migrationSucceeded: Boolean,
    )
}
