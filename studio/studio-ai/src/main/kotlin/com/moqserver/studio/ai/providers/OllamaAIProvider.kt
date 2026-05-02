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
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class OllamaAIProvider(
	val baseUrl: String = DEFAULT_BASE_URL,
	val defaultModel: String = DEFAULT_MODEL,
	private val httpClient: HttpClient = defaultClient(),
) : AIProvider {

	private val logger = loggerFor<OllamaAIProvider>()

    override val id = PROVIDER_ID
    override val displayName = DISPLAY_NAME
    override val kind = AIProviderKind.LOCAL
    override val capabilities = setOf(
        AIProviderCapability.ANALYZE_SPEC,
        AIProviderCapability.GENERATE_VARIANTS,
        AIProviderCapability.REFINE_PROJECT,
    )

    override suspend fun checkAvailability(): Boolean {
        return try {
            val response = httpClient.get("$baseUrl$TAGS_PATH")
            val available = response.status.isSuccess()
            logger.debug("Ollama availability check at {}: {}", baseUrl, available)
            available
        } catch (e: Exception) {
            logger.warn("Ollama not reachable at {}: {}", baseUrl, e.message)
            false
        }
    }

    override suspend fun validateConfig(): List<String> {
        val issues = mutableListOf<String>()
        if (baseUrl.isBlank()) {
            issues += "$DISPLAY_NAME base URL is not configured."
            return issues
        }
        if (!checkAvailability()) {
            issues += "$DISPLAY_NAME is not reachable at $baseUrl. Make sure it is running."
        }
        return issues
    }

    override suspend fun complete(prompt: String, model: String?, temperature: Double?): AICompletionResult {
        val effectiveModel = model ?: defaultModel
        val start = System.currentTimeMillis()
        logger.info("Sending completion request to Ollama model {}", effectiveModel)

		val requestBody = OllamaGenerateRequest(
			model = effectiveModel,
			system = systemPromptFor(effectiveModel),
			prompt = prompt,
			stream = false,
			think = thinkFor(effectiveModel),
			options = buildOptionsFor(
				model = effectiveModel,
				temperature = temperature,
			),
		)

        val response = try {
            httpClient.post("$baseUrl$GENERATE_PATH") {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }
        } catch (e: Exception) {
            logger.error("Ollama request failed: {}", e.message, e)
            throw AIProviderException.Unavailable(DISPLAY_NAME, e.message ?: "network error", retryable = true)
        }

        if (!response.status.isSuccess()) {
            val errorDetail = runCatching { parseErrorDetail(response.bodyAsText()) }.getOrNull()
            logger.error("Ollama returned HTTP {}{}", response.status.value, errorDetail?.let { ": $it" }.orEmpty())
            throw AIProviderException.Unavailable(
                DISPLAY_NAME,
                errorDetail ?: "HTTP ${response.status.value}",
                retryable = response.status.value >= 500,
            )
        }

        val result = response.body<OllamaGenerateResponse>()
        val latency = System.currentTimeMillis() - start
        logger.debug("Ollama completion received in {}ms ({} chars)", latency, result.response.length)
        return AICompletionResult(
            text = result.response,
            model = effectiveModel,
            latencyMs = latency,
        )
    }

    @Serializable
    private data class OllamaGenerateResponse(
        val response: String,
        val model: String? = null,
    )

    @Serializable
    private data class OllamaGenerateRequest(
		val model: String,
		val system: String? = null,
		val prompt: String,
		val stream: Boolean,
		val think: Boolean? = null,
		val options: OllamaGenerateOptions? = null,
	)

	@Serializable
	private data class OllamaGenerateOptions(
		val temperature: Double,
		@SerialName("top_p") val topP: Double? = null,
		@SerialName("top_k") val topK: Int? = null,
		@SerialName("presence_penalty") val presencePenalty: Double? = null,
		@SerialName("repeat_penalty") val repeatPenalty: Double? = null,
	)

	private fun buildOptionsFor(
		model: String,
		temperature: Double?,
	): OllamaGenerateOptions {
		return if (isQwen35Model(model)) {
			OllamaGenerateOptions(
				temperature = temperature ?: QWEN35_TEMPERATURE,
				topP = QWEN35_TOP_P,
				topK = QWEN35_TOP_K,
				presencePenalty = QWEN35_PRESENCE_PENALTY,
				repeatPenalty = QWEN35_REPEAT_PENALTY,
			)
		} else {
			OllamaGenerateOptions(temperature = temperature ?: DEFAULT_TEMPERATURE)
		}
	}

	private fun systemPromptFor(model: String): String? {
		return if (isQwen35Model(model)) {
			QWEN35_SYSTEM_PROMPT
		} else {
			null
		}
	}

	private fun thinkFor(model: String): Boolean? {
		return if (isQwen35Model(model)) {
			false
		} else {
			null
		}
	}

	private fun isQwen35Model(model: String): Boolean {
		val lower = model.lowercase()
		return lower.contains("qwen3.5") || lower.contains("qwen3.6")
	}

    private fun parseErrorDetail(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null

        return runCatching {
            val element = Json.parseToJsonElement(trimmed)
            (element as? JsonObject)?.get("error")?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: trimmed
    }

    companion object {
        const val PROVIDER_ID = "ollama"
		const val DISPLAY_NAME = "Ollama"
		const val DEFAULT_BASE_URL = "http://localhost:11434"
		const val DEFAULT_MODEL = "qwen3.6:latest"
		const val DEFAULT_TEMPERATURE = 0.7
		const val QWEN35_TEMPERATURE = 0.7
		const val QWEN35_TOP_P = 0.8
		const val QWEN35_TOP_K = 20
		const val QWEN35_PRESENCE_PENALTY = 1.5
		const val QWEN35_REPEAT_PENALTY = 1.0
		const val REQUEST_TIMEOUT_MS = 600_000L
		private const val TAGS_PATH = "/api/tags"
		private const val GENERATE_PATH = "/api/generate"
		private const val QWEN35_SYSTEM_PROMPT =
			"Return the final answer directly. Do not include reasoning or <think> blocks. Follow the requested output format exactly."
	}
}

private fun defaultClient() = HttpClient(CIO) {
	install(HttpTimeout) {
		requestTimeoutMillis = OllamaAIProvider.REQUEST_TIMEOUT_MS
	}
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}
