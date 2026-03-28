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
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class GeminiAIProvider(
    val apiKey: String,
    val baseUrl: String = "https://generativelanguage.googleapis.com",
    val defaultModel: String = "gemini-1.5-flash",
    private val httpClient: HttpClient = defaultClient(),
) : AIProvider {

    override val id = "gemini"
    override val displayName = "Google Gemini"
    override val kind = AIProviderKind.HOSTED
    override val capabilities = setOf(
        AIProviderCapability.ANALYZE_SPEC,
        AIProviderCapability.GENERATE_VARIANTS,
        AIProviderCapability.REFINE_PROJECT,
    )

    override suspend fun checkAvailability(): Boolean {
        if (apiKey.isBlank()) return false
        return try {
            val response = httpClient.get("$baseUrl/v1beta/models?key=$apiKey")
            response.status.isSuccess()
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun validateConfig(): List<String> {
        val issues = mutableListOf<String>()
        if (apiKey.isBlank()) {
            issues += "Gemini API key is not configured."
            return issues
        }
        if (!checkAvailability()) {
            issues += "Could not reach Gemini API. Check your API key and network."
        }
        return issues
    }

    override suspend fun complete(prompt: String, model: String?, temperature: Double?): AICompletionResult {
        val effectiveModel = model ?: defaultModel
        val start = System.currentTimeMillis()

        val bodyMap = buildMap<String, Any> {
            put("contents", listOf(mapOf("parts" to listOf(mapOf("text" to prompt)))))
            if (temperature != null) put("generationConfig", mapOf("temperature" to temperature))
        }

        val response = try {
            httpClient.post("$baseUrl/v1beta/models/$effectiveModel:generateContent?key=$apiKey") {
                contentType(ContentType.Application.Json)
                setBody(bodyMap)
            }
        } catch (e: Exception) {
            throw AIProviderException.Unavailable("Gemini", e.message ?: "network error", retryable = true)
        }

        when (response.status.value) {
            401, 403 -> throw AIProviderException.AuthInvalid("Gemini")
            429 -> throw AIProviderException.Unavailable("Gemini", "rate limit exceeded", retryable = true)
            !in 200..299 -> throw AIProviderException.Unavailable(
                "Gemini",
                "HTTP ${response.status.value}",
                retryable = response.status.value >= 500,
            )
        }

        val result = response.body<GeminiGenerateResponse>()
        val text = result.candidates
            .firstOrNull()
            ?.content
            ?.parts
            ?.firstOrNull()
            ?.text
            ?: throw AIProviderException.ParseFailure("Gemini")

        return AICompletionResult(
            text = text,
            model = effectiveModel,
            promptTokens = result.usageMetadata?.promptTokenCount,
            completionTokens = result.usageMetadata?.candidatesTokenCount,
            totalTokens = result.usageMetadata?.totalTokenCount,
            latencyMs = System.currentTimeMillis() - start,
        )
    }

    @Serializable
    private data class GeminiGenerateResponse(
        val candidates: List<Candidate> = emptyList(),
        val usageMetadata: UsageMetadata? = null,
    )

    @Serializable
    private data class Candidate(val content: Content? = null)

    @Serializable
    private data class Content(val parts: List<Part> = emptyList())

    @Serializable
    private data class Part(val text: String? = null)

    @Serializable
    private data class UsageMetadata(
        val promptTokenCount: Int? = null,
        val candidatesTokenCount: Int? = null,
        val totalTokenCount: Int? = null,
    )
}

private fun defaultClient() = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}
