package com.moqserver.studio.data

import kotlinx.serialization.Serializable

@Serializable
data class AISettings(
    val selectedProviderId: String? = null,
    val ollama: OllamaSettings = OllamaSettings(),
    val openai: OpenAISettings = OpenAISettings(),
    val anthropic: AnthropicSettings = AnthropicSettings(),
    val gemini: GeminiSettings = GeminiSettings(),
)

@Serializable
data class OllamaSettings(
    val baseUrl: String = "http://localhost:11434",
    val defaultModel: String = "llama3.1",
)

@Serializable
data class OpenAISettings(
    val apiKey: String = "",
    val baseUrl: String = "https://api.openai.com/v1",
    val defaultModel: String = "gpt-4o",
)

@Serializable
data class AnthropicSettings(
    val apiKey: String = "",
    val baseUrl: String = "https://api.anthropic.com",
    val defaultModel: String = "claude-sonnet-4-6",
)

@Serializable
data class GeminiSettings(
    val apiKey: String = "",
    val baseUrl: String = "https://generativelanguage.googleapis.com",
    val defaultModel: String = "gemini-1.5-flash",
)
