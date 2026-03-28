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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class OllamaAIProvider(
    val baseUrl: String = DEFAULT_BASE_URL,
    val defaultModel: String = DEFAULT_MODEL,
    private val httpClient: HttpClient = defaultClient(),
) : AIProvider {

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
            response.status.isSuccess()
        } catch (_: Exception) {
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

        val bodyMap = buildMap<String, Any> {
            put("model", effectiveModel)
            put("prompt", prompt)
            put("stream", false)
            if (temperature != null) put("options", mapOf("temperature" to temperature))
        }

        val response = try {
            httpClient.post("$baseUrl$GENERATE_PATH") {
                contentType(ContentType.Application.Json)
                setBody(bodyMap)
            }
        } catch (e: Exception) {
            throw AIProviderException.Unavailable(DISPLAY_NAME, e.message ?: "network error", retryable = true)
        }

        if (!response.status.isSuccess()) {
            throw AIProviderException.Unavailable(
                DISPLAY_NAME,
                "HTTP ${response.status.value}",
                retryable = response.status.value >= 500,
            )
        }

        val result = response.body<OllamaGenerateResponse>()
        return AICompletionResult(
            text = result.response,
            model = effectiveModel,
            latencyMs = System.currentTimeMillis() - start,
        )
    }

    @Serializable
    private data class OllamaGenerateResponse(
        val response: String,
        val model: String? = null,
    )

    companion object {
        const val PROVIDER_ID = "ollama"
        const val DISPLAY_NAME = "Ollama"
        const val DEFAULT_BASE_URL = "http://localhost:11434"
        const val DEFAULT_MODEL = "llama3.1"
        private const val TAGS_PATH = "/api/tags"
        private const val GENERATE_PATH = "/api/generate"
    }
}

private fun defaultClient() = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}
