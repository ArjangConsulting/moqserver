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
        if (apiKey.isBlank() || hostedBaseUrlIssue(baseUrl) != null) return false
        return try {
            // The key goes in a header, never in the URL: URLs leak into logs and proxies.
            val response = httpClient.get("$baseUrl/v1beta/models") {
                header(API_KEY_HEADER, apiKey)
            }
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
        hostedBaseUrlIssue(baseUrl)?.let {
            issues += it
            return issues
        }
        if (!checkAvailability()) {
            issues += "Could not reach $DISPLAY_NAME API. Check your API key and network."
        }
        return issues
    }

    override suspend fun complete(prompt: String, model: String?, temperature: Double?): AICompletionResult {
        hostedBaseUrlIssue(baseUrl)?.let { throw AIProviderException.Unavailable(DISPLAY_NAME, it, retryable = false) }
        val effectiveModel = model ?: defaultModel
        val start = System.currentTimeMillis()
        logger.info("Sending completion request to Gemini model {}", effectiveModel)

        val requestBody = GeminiGenerateRequest(
            contents = listOf(GeminiRequestContent(parts = listOf(GeminiRequestPart(text = prompt)))),
            generationConfig = if (temperature != null) GeminiGenerationConfig(temperature = temperature) else null,
        )

        val response = try {
            httpClient.post("$baseUrl/v1beta/models/$effectiveModel:generateContent") {
                contentType(ContentType.Application.Json)
                header(API_KEY_HEADER, apiKey)
                setBody(requestBody)
            }
        } catch (e: Exception) {
            logger.error("Gemini request failed: {}", e.message, e)
            throw AIProviderException.Unavailable(DISPLAY_NAME, e.message ?: "network error", retryable = true)
        }

        checkResponseStatus(response.status.value)

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
            latency,
            result.usageMetadata?.promptTokenCount,
            result.usageMetadata?.candidatesTokenCount,
            result.usageMetadata?.totalTokenCount,
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
    private data class GeminiGenerateRequest(
        val contents: List<GeminiRequestContent>,
        val generationConfig: GeminiGenerationConfig? = null,
    )

    @Serializable
    private data class GeminiRequestContent(
        val parts: List<GeminiRequestPart>,
    )

    @Serializable
    private data class GeminiRequestPart(
        val text: String,
    )

    @Serializable
    private data class GeminiGenerationConfig(
        val temperature: Double? = null,
    )

	private fun checkResponseStatus(statusCode: Int) {
		responseStatusException(statusCode)?.let { throw it }
	}

	private fun responseStatusException(statusCode: Int): AIProviderException? {
		return when (statusCode) {
			401, 403 -> {
				logger.error("Gemini authentication failed ({})", statusCode)
				AIProviderException.AuthInvalid(DISPLAY_NAME)
			}
			429 -> {
				logger.warn("Gemini rate limit exceeded (429)")
				AIProviderException.Unavailable(DISPLAY_NAME, "rate limit exceeded", retryable = true)
			}
			in 200..299 -> null
			else -> {
				logger.error("Gemini returned HTTP {}", statusCode)
				AIProviderException.Unavailable(
					DISPLAY_NAME,
					"HTTP $statusCode",
					retryable = statusCode >= 500,
				)
			}
		}
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
        const val DEFAULT_MODEL = "gemini-3.5-flash"
        const val API_KEY_HEADER = "x-goog-api-key"
    }
}

private fun defaultClient() = HttpClient(CIO) {
    followRedirects = false
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}
