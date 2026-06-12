package com.moqserver.studio.data

import com.moqserver.studio.logging.loggerFor
import kotlinx.serialization.json.Json
import java.io.File

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
        return fileSettings.copy(
            openai = fileSettings.openai.copy(
                apiKey = loadCredential(OPENAI_API_KEY_CREDENTIAL, fileSettings.openai.apiKey),
            ),
            anthropic = fileSettings.anthropic.copy(
                apiKey = loadCredential(ANTHROPIC_API_KEY_CREDENTIAL, fileSettings.anthropic.apiKey),
            ),
            gemini = fileSettings.gemini.copy(
                apiKey = loadCredential(GEMINI_API_KEY_CREDENTIAL, fileSettings.gemini.apiKey),
            ),
        )
    }

    fun save(settings: AISettings) {
        val openaiInFile = persistCredential(OPENAI_API_KEY_CREDENTIAL, settings.openai.apiKey)
        val anthropicInFile = persistCredential(ANTHROPIC_API_KEY_CREDENTIAL, settings.anthropic.apiKey)
        val geminiInFile = persistCredential(GEMINI_API_KEY_CREDENTIAL, settings.gemini.apiKey)

        val persistedSettings = settings.copy(
            openai = settings.openai.copy(apiKey = if (openaiInFile) settings.openai.apiKey else ""),
            anthropic = settings.anthropic.copy(apiKey = if (anthropicInFile) settings.anthropic.apiKey else ""),
            gemini = settings.gemini.copy(apiKey = if (geminiInFile) settings.gemini.apiKey else ""),
        )

        settingsFile.parentFile?.mkdirs()
        settingsFile.writeText(json.encodeToString(persistedSettings))
        logger.info("Saved AI settings to ${settingsFile.path}")
    }

    private fun loadCredential(
        credentialKey: String,
        legacyFallback: String,
    ): String = try {
        credentialStore.read(credentialKey) ?: legacyFallback
        } catch (e: Exception) {
        logger.warn("Failed to load secure credential '$credentialKey': ${e.message}")
        legacyFallback
    }

    /**
     * Stores [value] in the secure credential store.
     * Returns `true` when the platform has no secure storage and the key must be kept
     * in the settings file instead (plaintext fallback, matched by [loadCredential]).
     */
    private fun persistCredential(
        credentialKey: String,
        value: String,
    ): Boolean {
        try {
            if (value.isBlank()) {
                credentialStore.delete(credentialKey)
            } else {
                credentialStore.write(credentialKey, value)
            }
            return false
        } catch (e: SecureStorageUnavailableException) {
            logger.warn(
                "No secure credential storage on this platform; '$credentialKey' will be stored " +
                    "in plain text in ${settingsFile.path}. ${e.message}",
            )
            return value.isNotBlank()
        } catch (e: Exception) {
            throw IllegalStateException("Failed to save secure credential '$credentialKey': ${e.message}", e)
        }
    }

    companion object {
        private const val OPENAI_API_KEY_CREDENTIAL = "openai.api-key"
        private const val ANTHROPIC_API_KEY_CREDENTIAL = "anthropic.api-key"
        private const val GEMINI_API_KEY_CREDENTIAL = "gemini.api-key"
        private const val SETTINGS_DIR = ".moqserver"
        private const val SETTINGS_FILENAME = "studio-settings.json"

        fun defaultSettingsFile(): File {
            val home = System.getProperty("user.home")
            return File(home, "$SETTINGS_DIR/$SETTINGS_FILENAME")
        }
    }
}
