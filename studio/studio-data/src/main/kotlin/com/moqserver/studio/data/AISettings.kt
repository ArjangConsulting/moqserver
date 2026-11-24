package com.moqserver.studio.data

import com.moqserver.studio.domain.VariantReferenceSyncPreference
import kotlinx.serialization.Serializable

@Serializable
enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK,
}

@Serializable
data class AISettings(
    val selectedProviderId: String? = null,
    val themeMode: ThemePreference = ThemePreference.SYSTEM,
    val variantReferenceSyncByProject: Map<String, VariantReferenceSyncPreference> = emptyMap(),
    val harImport: HARImportSettings = HARImportSettings(),
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
		const val DEFAULT_MODEL = "qwen3.8:27b-mlx"
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
        const val DEFAULT_MODEL = "gpt-6.1-sol"
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
        const val DEFAULT_MODEL = "claude-sonnet-5-5"
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
        const val DEFAULT_MODEL = "gemini-3.8-flash"
    }
}

/**
 * How Studio parses HAR captures. [redactHeaders] adds header names to the built-in credential
 * list; [inferHeaderRules] turns credential and `X-` headers every successful call sent into
 * presence-only request rules.
 */
@Serializable
data class HARImportSettings(
    val redactHeaders: List<String> = emptyList(),
    val inferHeaderRules: Boolean = false,
)
