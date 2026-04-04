package com.moqserver.studio.ai.providers

import com.moqserver.studio.ai.AICompletionResult
import com.moqserver.studio.ai.AIProvider
import com.moqserver.studio.ai.AIProviderCapability
import com.moqserver.studio.ai.AIProviderException
import com.moqserver.studio.ai.AIProviderKind
import com.moqserver.studio.logging.loggerFor
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
	val baseUrl: String = DEFAULT_BASE_URL,
	val defaultModel: String = DEFAULT_MODEL,
	private val httpClient: HttpClient = defaultClient(),
) : AIProvider {

	private val logger = loggerFor<GeminiAIProvider>()

    override val id = PROVIDER_ID
    override val displayName = DISPLAY_NAME
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
            val available = response.status.isSuccess()
            logger.debug("Gemini availability check: {}", available)
            available
        } catch (e: Exception) {
            logger.warn("Gemini not reachable: {}", e.message)
            false
        }
    }

    override suspend fun validateConfig(): List<String> {
        val issues = mutableListOf<String>()
        if (apiKey.isBlank()) {
            issues += "$DISPLAY_NAME API key is not configured."
            return issues
        }
        if (!checkAvailability()) {
            issues += "Could not reach $DISPLAY_NAME API. Check your API key and network."
        }
        return issues
    }

    override suspend fun complete(prompt: String, model: String?, temperature: Double?): AICompletionResult {
        val effectiveModel = model ?: defaultModel
        val start = System.currentTimeMillis()
        logger.info("Sending completion request to Gemini model {}", effectiveModel)

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
            logger.error("Gemini request failed: {}", e.message, e)
            throw AIProviderException.Unavailable(DISPLAY_NAME, e.message ?: "network error", retryable = true)
        }

        when (response.status.value) {
            401, 403 -> {
                logger.error("Gemini authentication failed ({})", response.status.value)
                throw AIProviderException.AuthInvalid(DISPLAY_NAME)
            }
            429 -> {
                logger.warn("Gemini rate limit exceeded (429)")
                throw AIProviderException.Unavailable(DISPLAY_NAME, "rate limit exceeded", retryable = true)
            }
            !in 200..299 -> {
                logger.error("Gemini returned HTTP {}", response.status.value)
                throw AIProviderException.Unavailable(
                    DISPLAY_NAME,
                    "HTTP ${response.status.value}",
                    retryable = response.status.value >= 500,
                )
            }
        }

        val result = response.body<GeminiGenerateResponse>()
        val text = result.candidates
            .firstOrNull()
            ?.content
            ?.parts
            ?.firstOrNull()
            ?.text
            ?: throw AIProviderException.ParseFailure(DISPLAY_NAME)

        val latency = System.currentTimeMillis() - start
        logger.debug(
            "Gemini completion received in {}ms (prompt={}, completion={}, total={} tokens)",
            latency, result.usageMetadata?.promptTokenCount,
            result.usageMetadata?.candidatesTokenCount, result.usageMetadata?.totalTokenCount,
        )
        return AICompletionResult(
            text = text,
            model = effectiveModel,
            promptTokens = result.usageMetadata?.promptTokenCount,
            completionTokens = result.usageMetadata?.candidatesTokenCount,
            totalTokens = result.usageMetadata?.totalTokenCount,
            latencyMs = latency,
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

    companion object {
        const val PROVIDER_ID = "gemini"
        const val DISPLAY_NAME = "Google Gemini"
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"
        const val DEFAULT_MODEL = "gemini-1.5-flash"
    }
}

private fun defaultClient() = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}
