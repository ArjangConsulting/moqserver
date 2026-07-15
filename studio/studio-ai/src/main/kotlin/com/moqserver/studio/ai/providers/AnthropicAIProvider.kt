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

class AnthropicAIProvider(
	val apiKey: String,
	val baseUrl: String = DEFAULT_BASE_URL,
	val defaultModel: String = DEFAULT_MODEL,
	private val httpClient: HttpClient = defaultClient(),
) : AIProvider {

	private val logger = loggerFor<AnthropicAIProvider>()

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
            val response = httpClient.get("$baseUrl$MODELS_PATH") {
                header(API_KEY_HEADER, apiKey)
                header(VERSION_HEADER, API_VERSION)
            }
            val available = response.status.isSuccess()
            logger.debug("Anthropic availability check: {}", available)
            available
        } catch (e: Exception) {
            logger.warn("Anthropic not reachable: {}", e.message)
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
        logger.info("Sending completion request to Anthropic model {}", effectiveModel)

        val requestBody = AnthropicMessagesRequest(
            model = effectiveModel,
            maxTokens = DEFAULT_MAX_TOKENS,
            messages = listOf(AnthropicMessage(role = "user", content = prompt)),
            temperature = temperature,
        )

        val response = try {
            httpClient.post("$baseUrl$MESSAGES_PATH") {
                contentType(ContentType.Application.Json)
                header(API_KEY_HEADER, apiKey)
                header(VERSION_HEADER, API_VERSION)
                setBody(requestBody)
            }
        } catch (e: Exception) {
            logger.error("Anthropic request failed: {}", e.message, e)
            throw AIProviderException.Unavailable(DISPLAY_NAME, e.message ?: "network error", retryable = true)
        }

        checkResponseStatus(response.status.value)

        val result = response.body<AnthropicMessagesResponse>()
        val text = result.content.firstOrNull { it.type == "text" }?.text
            ?: throw AIProviderException.ParseFailure(DISPLAY_NAME)

        val latency = System.currentTimeMillis() - start
        logger.debug(
            "Anthropic completion received in {}ms (prompt={}, completion={} tokens)",
            latency,
            result.usage?.inputTokens,
            result.usage?.outputTokens,
        )
        return AICompletionResult(
            text = text,
            model = result.model ?: effectiveModel,
            promptTokens = result.usage?.inputTokens,
            completionTokens = result.usage?.outputTokens,
            totalTokens = (result.usage?.inputTokens ?: 0) + (result.usage?.outputTokens ?: 0),
            latencyMs = latency,
        )
    }

    @Serializable
    private data class AnthropicMessagesRequest(
        val model: String,
        @SerialName("max_tokens") val maxTokens: Int,
        val messages: List<AnthropicMessage>,
        val temperature: Double? = null,
    )

    @Serializable
    private data class AnthropicMessage(
        val role: String,
        val content: String,
    )

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

	private fun checkResponseStatus(statusCode: Int) {
		responseStatusException(statusCode)?.let { throw it }
	}

	private fun responseStatusException(statusCode: Int): AIProviderException? {
		return when (statusCode) {
			401 -> {
				logger.error("Anthropic authentication failed (401)")
				AIProviderException.AuthInvalid(DISPLAY_NAME)
			}
			429 -> {
				logger.warn("Anthropic rate limit exceeded (429)")
				AIProviderException.Unavailable(DISPLAY_NAME, "rate limit exceeded", retryable = true)
			}
			in 200..299 -> null
			else -> {
				logger.error("Anthropic returned HTTP {}", statusCode)
				AIProviderException.Unavailable(
					DISPLAY_NAME,
					"HTTP $statusCode",
					retryable = statusCode >= 500,
				)
			}
		}
	}

    companion object {
        const val PROVIDER_ID = "anthropic"
        const val DISPLAY_NAME = "Anthropic"
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"
        const val DEFAULT_MODEL = "claude-sonnet-4-6"
        const val DEFAULT_MAX_TOKENS = 4096
        const val API_KEY_HEADER = "x-api-key"
        const val VERSION_HEADER = "anthropic-version"
        const val API_VERSION = "2023-06-01"
        private const val MODELS_PATH = "/v1/models"
        private const val MESSAGES_PATH = "/v1/messages"
    }
}

private fun defaultClient() = HttpClient(CIO) {
    followRedirects = false
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}
