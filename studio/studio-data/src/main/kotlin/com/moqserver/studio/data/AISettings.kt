package com.moqserver.studio.data

import kotlinx.serialization.Serializable

@Serializable
enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK,
}

@Serializable
enum class VariantReferenceSyncPreference {
    ALWAYS_UPDATE,
    NEVER_UPDATE,
}

@Serializable
data class AISettings(
    val selectedProviderId: String? = null,
    val themeMode: ThemePreference = ThemePreference.SYSTEM,
    val variantReferenceSyncByProject: Map<String, VariantReferenceSyncPreference> = emptyMap(),
    val ollama: OllamaSettings = OllamaSettings(),
    val openai: OpenAISettings = OpenAISettings(),
    val anthropic: AnthropicSettings = AnthropicSettings(),
    val gemini: GeminiSettings = GeminiSettings(),
)

@Serializable
data class OllamaSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val defaultModel: String = DEFAULT_MODEL,
) {
	companion object {
		/** Keep in sync with OllamaAIProvider defaults in studio-ai. */
		const val DEFAULT_BASE_URL = "http://localhost:11434"
		const val DEFAULT_MODEL = "llama3.2:latest"
	}
}

@Serializable
data class OpenAISettings(
    val apiKey: String = "",
    val baseUrl: String = DEFAULT_BASE_URL,
    val defaultModel: String = DEFAULT_MODEL,
) {
    companion object {
        /** Keep in sync with OpenAIAIProvider defaults in studio-ai. */
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o"
    }
}

@Serializable
data class AnthropicSettings(
    val apiKey: String = "",
    val baseUrl: String = DEFAULT_BASE_URL,
    val defaultModel: String = DEFAULT_MODEL,
) {
    companion object {
        /** Keep in sync with AnthropicAIProvider defaults in studio-ai. */
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"
        const val DEFAULT_MODEL = "claude-sonnet-4-6"
    }
}

@Serializable
data class GeminiSettings(
    val apiKey: String = "",
    val baseUrl: String = DEFAULT_BASE_URL,
    val defaultModel: String = DEFAULT_MODEL,
) {
    companion object {
        /** Keep in sync with GeminiAIProvider defaults in studio-ai. */
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"
        const val DEFAULT_MODEL = "gemini-1.5-flash"
    }
}
