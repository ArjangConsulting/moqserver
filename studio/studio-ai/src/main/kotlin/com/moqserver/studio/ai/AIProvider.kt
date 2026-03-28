package com.moqserver.studio.ai

/** A single AI backend that can complete text prompts. */
interface AIProvider {
    val id: String
    val displayName: String
    val kind: AIProviderKind
    val capabilities: Set<AIProviderCapability>

    /** Returns true if the provider is reachable and configured right now. */
    suspend fun checkAvailability(): Boolean

    /**
     * Validates configuration and returns a list of human-readable issues.
     * An empty list means the configuration is valid.
     */
    suspend fun validateConfig(): List<String>

    /** Sends a text prompt and returns the raw completion result. */
    suspend fun complete(
        prompt: String,
        model: String? = null,
        temperature: Double? = null,
    ): AICompletionResult
}

enum class AIProviderKind { LOCAL, HOSTED }

enum class AIProviderCapability {
    ANALYZE_SPEC,
    GENERATE_VARIANTS,
    REFINE_PROJECT,
}

data class AICompletionResult(
    val text: String,
    val model: String,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val latencyMs: Long? = null,
)
