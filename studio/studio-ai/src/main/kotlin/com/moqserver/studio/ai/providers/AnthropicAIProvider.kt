package com.moqserver.studio.ai.providers

import com.moqserver.studio.ai.AICompletionResult
import com.moqserver.studio.ai.AIProvider
import com.moqserver.studio.ai.AIProviderCapability
import com.moqserver.studio.ai.AIProviderException
import com.moqserver.studio.ai.AIProviderKind
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class AnthropicAIProvider(
    val apiKey: String,
    val baseUrl: String = "https://api.anthropic.com",
    val defaultModel: String = "claude-sonnet-4-6",
    private val httpClient: HttpClient = defaultClient(),
) : AIProvider {

    override val id = "anthropic"
    override val displayName = "Anthropic"
    override val kind = AIProviderKind.HOSTED
    override val capabilities = setOf(
        AIProviderCapability.ANALYZE_SPEC,
        AIProviderCapability.GENERATE_VARIANTS,
        AIProviderCapability.REFINE_PROJECT,
    )

    override suspend fun checkAvailability(): Boolean {
        if (apiKey.isBlank()) return false
        return try {
            val response = httpClient.get("$baseUrl/v1/models") {
                header("x-api-key", apiKey)
                header("anthropic-version", "2023-06-01")
            }
            response.status.isSuccess()
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun validateConfig(): List<String> {
        val issues = mutableListOf<String>()
        if (apiKey.isBlank()) {
            issues += "Anthropic API key is not configured."
        }
        return issues
    }

    override suspend fun complete(prompt: String, model: String?, temperature: Double?): AICompletionResult {
        val effectiveModel = model ?: defaultModel
        val start = System.currentTimeMillis()

        val bodyMap = buildMap<String, Any> {
            put("model", effectiveModel)
            put("max_tokens", 4096)
            put("messages", listOf(mapOf("role" to "user", "content" to prompt)))
            if (temperature != null) put("temperature", temperature)
        }

        val response = try {
            httpClient.post("$baseUrl/v1/messages") {
                contentType(ContentType.Application.Json)
                header("x-api-key", apiKey)
                header("anthropic-version", "2023-06-01")
                setBody(bodyMap)
            }
        } catch (e: Exception) {
            throw AIProviderException.Unavailable("Anthropic", e.message ?: "network error", retryable = true)
        }

        when (response.status.value) {
            401 -> throw AIProviderException.AuthInvalid("Anthropic")
            429 -> throw AIProviderException.Unavailable("Anthropic", "rate limit exceeded", retryable = true)
            !in 200..299 -> throw AIProviderException.Unavailable(
                "Anthropic",
                "HTTP ${response.status.value}",
                retryable = response.status.value >= 500,
            )
        }

        val result = response.body<AnthropicMessagesResponse>()
        val text = result.content.firstOrNull { it.type == "text" }?.text
            ?: throw AIProviderException.ParseFailure("Anthropic")

        return AICompletionResult(
            text = text,
            model = result.model ?: effectiveModel,
            promptTokens = result.usage?.inputTokens,
            completionTokens = result.usage?.outputTokens,
            totalTokens = (result.usage?.inputTokens ?: 0) + (result.usage?.outputTokens ?: 0),
            latencyMs = System.currentTimeMillis() - start,
        )
    }

    @Serializable
    private data class AnthropicMessagesResponse(
        val model: String? = null,
        val content: List<ContentBlock> = emptyList(),
        val usage: Usage? = null,
    )

    @Serializable
    private data class ContentBlock(
        val type: String,
        val text: String? = null,
    )

    @Serializable
    private data class Usage(
        @SerialName("input_tokens") val inputTokens: Int? = null,
        @SerialName("output_tokens") val outputTokens: Int? = null,
    )
}

private fun defaultClient() = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}
