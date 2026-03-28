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
        persistCredential(OPENAI_API_KEY_CREDENTIAL, settings.openai.apiKey)
        persistCredential(ANTHROPIC_API_KEY_CREDENTIAL, settings.anthropic.apiKey)
        persistCredential(GEMINI_API_KEY_CREDENTIAL, settings.gemini.apiKey)

        val persistedSettings = settings.copy(
            openai = settings.openai.copy(apiKey = ""),
            anthropic = settings.anthropic.copy(apiKey = ""),
            gemini = settings.gemini.copy(apiKey = ""),
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
        } catch (e: Exception) {
            throw IllegalStateException("Failed to save secure credential '$credentialKey': ${e.message}", e)
        }
    }

    companion object {
        private const val OPENAI_API_KEY_CREDENTIAL = "openai.api-key"
        private const val ANTHROPIC_API_KEY_CREDENTIAL = "anthropic.api-key"
        private const val GEMINI_API_KEY_CREDENTIAL = "gemini.api-key"

        fun defaultSettingsFile(): File {
            val home = System.getProperty("user.home")
            return File(home, ".moqserver/studio-settings.json")
        }
    }
}
