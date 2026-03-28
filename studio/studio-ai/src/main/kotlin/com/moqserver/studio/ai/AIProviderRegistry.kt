package com.moqserver.studio.ai

import com.moqserver.studio.ai.prompts.PromptBuilder
import com.moqserver.studio.ai.prompts.ResponseParser
import com.moqserver.studio.domain.AnalyzeSpecResult
import com.moqserver.studio.domain.CompanionRequest
import com.moqserver.studio.domain.CompanionResponse
import com.moqserver.studio.domain.GenerateVariantsResult
import com.moqserver.studio.domain.ProviderUsage
import com.moqserver.studio.domain.RefineProjectResult
import com.moqserver.studio.domain.UsageInfo
import com.moqserver.studio.logging.loggerFor
import java.util.UUID

class AIProviderRegistry(private val providers: List<AIProvider>) {

    private val logger = loggerFor<AIProviderRegistry>()

    fun allProviders(): List<AIProvider> = providers

    suspend fun availableProviders(): List<AIProvider> = providers.filter { it.checkAvailability() }

    fun find(id: String): AIProvider? = providers.find { it.id == id }

    suspend fun analyzeSpec(
        provider: AIProvider,
        request: CompanionRequest,
    ): CompanionResponse<AnalyzeSpecResult> {
        val prompt = PromptBuilder.buildAnalyzePrompt(request)
        val completion = runCompletion(provider, prompt, request)
        val result = ResponseParser.parseAnalyzeResponse(completion.text)
        return wrapResponse(provider, completion, result)
    }

    suspend fun generateVariants(
        provider: AIProvider,
        request: CompanionRequest,
    ): CompanionResponse<GenerateVariantsResult> {
        val prompt = PromptBuilder.buildGenerateVariantsPrompt(request)
        val completion = runCompletion(provider, prompt, request)
        val result = ResponseParser.parseGenerateVariantsResponse(completion.text)
        return wrapResponse(provider, completion, result)
    }

    suspend fun refineProject(
        provider: AIProvider,
        request: CompanionRequest,
    ): CompanionResponse<RefineProjectResult> {
        val prompt = PromptBuilder.buildRefineProjectPrompt(request)
        val completion = runCompletion(provider, prompt, request)
        val result = ResponseParser.parseRefineProjectResponse(completion.text)
        return wrapResponse(provider, completion, result)
    }

    private suspend fun runCompletion(
        provider: AIProvider,
        prompt: String,
        request: CompanionRequest,
    ): AICompletionResult {
        logger.info("Running AI completion via ${provider.displayName}")
        return provider.complete(
            prompt = prompt,
            model = request.options?.model,
            temperature = request.options?.temperature,
        )
    }

    private fun <T> wrapResponse(
        provider: AIProvider,
        completion: AICompletionResult,
        result: T,
    ): CompanionResponse<T> = CompanionResponse(
        requestId = "req_${UUID.randomUUID().toString().replace("-", "").take(12)}",
        provider = ProviderUsage(id = provider.id, model = completion.model),
        result = result,
        usage = UsageInfo(
            promptTokens = completion.promptTokens,
            completionTokens = completion.completionTokens,
            totalTokens = completion.totalTokens,
            latencyMs = completion.latencyMs?.toInt(),
        ),
    )
}
