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
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class OpenAIAIProvider(
	val apiKey: String,
	val baseUrl: String = DEFAULT_BASE_URL,
	val defaultModel: String = DEFAULT_MODEL,
	private val httpClient: HttpClient = defaultClient(),
) : AIProvider {

	private val logger = loggerFor<OpenAIAIProvider>()

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
            val response = httpClient.get("$baseUrl$MODELS_PATH") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            val available = response.status.isSuccess()
            logger.debug("OpenAI availability check: {}", available)
            available
        } catch (e: Exception) {
            logger.warn("OpenAI not reachable: {}", e.message)
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
        logger.info("Sending completion request to OpenAI model {}", effectiveModel)

        val requestBody = OpenAIChatRequest(
            model = effectiveModel,
            messages = listOf(OpenAIChatMessage(role = "user", content = prompt)),
            temperature = temperature,
        )

        val response = try {
            httpClient.post("$baseUrl$CHAT_COMPLETIONS_PATH") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                setBody(requestBody)
            }
        } catch (e: Exception) {
            logger.error("OpenAI request failed: {}", e.message, e)
            throw AIProviderException.Unavailable(DISPLAY_NAME, e.message ?: "network error", retryable = true)
        }

        checkResponseStatus(response.status.value)

        val result = response.body<OpenAIChatResponse>()
        val text = result.choices.firstOrNull()?.message?.content
            ?: throw AIProviderException.ParseFailure(DISPLAY_NAME)

        val latency = System.currentTimeMillis() - start
        logger.debug(
            "OpenAI completion received in {}ms (prompt={}, completion={}, total={} tokens)",
            latency,
            result.usage?.promptTokens,
            result.usage?.completionTokens,
            result.usage?.totalTokens,
        )
        return AICompletionResult(
            text = text,
            model = result.model ?: effectiveModel,
            promptTokens = result.usage?.promptTokens,
            completionTokens = result.usage?.completionTokens,
            totalTokens = result.usage?.totalTokens,
            latencyMs = latency,
        )
    }

    @Serializable
    private data class OpenAIChatRequest(
        val model: String,
        val messages: List<OpenAIChatMessage>,
        val temperature: Double? = null,
    )

    @Serializable
    private data class OpenAIChatMessage(
        val role: String,
        val content: String,
    )

    @Serializable
    private data class OpenAIChatResponse(
        val model: String? = null,
        val choices: List<Choice> = emptyList(),
        val usage: Usage? = null,
    )

    @Serializable
    private data class Choice(val message: Message)

    @Serializable
    private data class Message(val content: String)

    @Serializable
    private data class Usage(
        @SerialName("prompt_tokens") val promptTokens: Int? = null,
        @SerialName("completion_tokens") val completionTokens: Int? = null,
        @SerialName("total_tokens") val totalTokens: Int? = null,
    )

	private fun checkResponseStatus(statusCode: Int) {
		responseStatusException(statusCode)?.let { throw it }
	}

	private fun responseStatusException(statusCode: Int): AIProviderException? {
		return when (statusCode) {
			401 -> {
				logger.error("OpenAI authentication failed (401)")
				AIProviderException.AuthInvalid(DISPLAY_NAME)
			}
			429 -> {
				logger.warn("OpenAI rate limit exceeded (429)")
				AIProviderException.Unavailable(DISPLAY_NAME, "rate limit exceeded", retryable = true)
			}
			in 200..299 -> null
			else -> {
				logger.error("OpenAI returned HTTP {}", statusCode)
				AIProviderException.Unavailable(
					DISPLAY_NAME,
					"HTTP $statusCode",
					retryable = statusCode >= 500,
				)
			}
		}
	}

    companion object {
        const val PROVIDER_ID = "openai"
        const val DISPLAY_NAME = "OpenAI"
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o"
        private const val MODELS_PATH = "/models"
        private const val CHAT_COMPLETIONS_PATH = "/chat/completions"
    }
}

private fun defaultClient() = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}
