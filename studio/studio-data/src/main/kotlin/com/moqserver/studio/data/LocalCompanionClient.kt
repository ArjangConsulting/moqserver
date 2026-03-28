package com.moqserver.studio.data

import com.moqserver.studio.domain.AnalyzeSpecResult
import com.moqserver.studio.domain.CompanionErrorResponse
import com.moqserver.studio.domain.CompanionRequest
import com.moqserver.studio.domain.CompanionResponse
import com.moqserver.studio.domain.GenerateVariantsResult
import com.moqserver.studio.domain.HealthResponse
import com.moqserver.studio.domain.ProvidersResponse
import com.moqserver.studio.domain.RefineProjectResult
import com.moqserver.studio.domain.ValidateConfigRequest
import com.moqserver.studio.domain.ValidateConfigResponse
import com.moqserver.studio.logging.loggerFor
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
import kotlinx.serialization.json.Json

class LocalCompanionClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val client: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    prettyPrint = true
                },
            )
        }
    },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val logger = loggerFor<LocalCompanionClient>()
    val endpoint: String get() = baseUrl

    suspend fun health(): HealthResponse {
        logger.debug("GET {}{}", baseUrl, HEALTH_PATH)
        val start = System.currentTimeMillis()
        return client.get("$baseUrl$HEALTH_PATH").body<HealthResponse>().also {
            logger.debug("health: status={}, {}ms", it.status, System.currentTimeMillis() - start)
        }
    }

    suspend fun providers(): ProvidersResponse {
        logger.debug("GET {}{}", baseUrl, PROVIDERS_PATH)
        val start = System.currentTimeMillis()
        return client.get("$baseUrl$PROVIDERS_PATH").body<ProvidersResponse>().also { response ->
            val available = response.providers.count { it.available }
            logger.info(
                "providers: {} total, {} available, {}ms",
                response.providers.size,
                available,
                System.currentTimeMillis() - start,
            )
        }
    }

    suspend fun validateConfig(request: ValidateConfigRequest): ValidateConfigResponse {
        logger.debug("POST {}{} provider={}", baseUrl, VALIDATE_CONFIG_PATH, request.providerId)
        val start = System.currentTimeMillis()
        return client.post("$baseUrl$VALIDATE_CONFIG_PATH") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body<ValidateConfigResponse>().also { response ->
            logger.info(
                "validate-config: provider={}, valid={}, {}ms",
                request.providerId,
                response.valid,
                System.currentTimeMillis() - start,
            )
        }
    }

    suspend fun analyzeSpec(request: CompanionRequest): CompanionResponse<AnalyzeSpecResult> {
        val endpointCount = request.projectContext?.endpoints?.size ?: 0
        logger.info("POST {} provider={}, endpoints={}", ANALYZE_SPEC_PATH, request.providerId, endpointCount)
        val start = System.currentTimeMillis()
        val response = client.post("$baseUrl$ANALYZE_SPEC_PATH") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (!response.status.isSuccess()) {
            val elapsed = System.currentTimeMillis() - start
            logger.error("analyze-spec failed: HTTP {} after {}ms", response.status.value, elapsed)
            throw CompanionApiException.fromResponse(response.status.value, response.bodyAsText(), json)
        }
        return response.body<CompanionResponse<AnalyzeSpecResult>>().also {
            logger.info("analyze-spec completed in {}ms", System.currentTimeMillis() - start)
        }
    }

    suspend fun generateVariants(request: CompanionRequest): CompanionResponse<GenerateVariantsResult> {
        val selection = request.selection?.endpointKeys?.joinToString() ?: "(none)"
        logger.info("POST {} provider={}, selection={}", GENERATE_VARIANTS_PATH, request.providerId, selection)
        val start = System.currentTimeMillis()
        val response = client.post("$baseUrl$GENERATE_VARIANTS_PATH") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (!response.status.isSuccess()) {
            val elapsed = System.currentTimeMillis() - start
            logger.error("generate-variants failed: HTTP {} after {}ms", response.status.value, elapsed)
            throw CompanionApiException.fromResponse(response.status.value, response.bodyAsText(), json)
        }
        return response.body<CompanionResponse<GenerateVariantsResult>>().also {
            logger.info("generate-variants completed in {}ms", System.currentTimeMillis() - start)
        }
    }

    suspend fun refineProject(request: CompanionRequest): CompanionResponse<RefineProjectResult> {
        val endpointCount = request.projectContext?.endpoints?.size ?: 0
        logger.info("POST {} provider={}, endpoints={}", REFINE_PROJECT_PATH, request.providerId, endpointCount)
        val start = System.currentTimeMillis()
        val response = client.post("$baseUrl$REFINE_PROJECT_PATH") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (!response.status.isSuccess()) {
            val elapsed = System.currentTimeMillis() - start
            logger.error("refine-project failed: HTTP {} after {}ms", response.status.value, elapsed)
            throw CompanionApiException.fromResponse(response.status.value, response.bodyAsText(), json)
        }
        return response.body<CompanionResponse<RefineProjectResult>>().also {
            logger.info("refine-project completed in {}ms", System.currentTimeMillis() - start)
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "http://127.0.0.1:8081"
        private const val HEALTH_PATH = "/health"
        private const val PROVIDERS_PATH = "/ai/providers"
        private const val VALIDATE_CONFIG_PATH = "/ai/validate-config"
        private const val ANALYZE_SPEC_PATH = "/ai/analyze-spec"
        private const val GENERATE_VARIANTS_PATH = "/ai/generate-variants"
        private const val REFINE_PROJECT_PATH = "/ai/refine-project"
    }
}

class CompanionApiException(
    val code: String,
    override val message: String,
    val retryable: Boolean,
) : Exception(message) {
    companion object {
        private const val ERROR_CODE_PREFIX = "http_"
        private val RETRYABLE_STATUS_RANGE = 500..599

        fun fromResponse(statusCode: Int, body: String, json: Json): CompanionApiException {
            return try {
                val error = json.decodeFromString<CompanionErrorResponse>(body)
                CompanionApiException(error.error.code, error.error.message, error.error.retryable)
            } catch (_: Exception) {
                CompanionApiException(
                    code = "$ERROR_CODE_PREFIX$statusCode",
                    message = "Companion returned HTTP $statusCode",
                    retryable = statusCode in RETRYABLE_STATUS_RANGE,
                )
            }
        }
    }
}
