package com.moqserver.studio

import com.moqserver.studio.ai.AIProvider
import com.moqserver.studio.ai.AIProviderKind
import com.moqserver.studio.ai.AIProviderRegistry
import com.moqserver.studio.ai.providers.AnthropicAIProvider
import com.moqserver.studio.ai.providers.GeminiAIProvider
import com.moqserver.studio.ai.providers.OllamaAIProvider
import com.moqserver.studio.ai.providers.OpenAIAIProvider
import com.moqserver.studio.data.AISettings
import com.moqserver.studio.domain.AIAction
import com.moqserver.studio.domain.AIProviderInfo
import com.moqserver.studio.domain.CompanionRequest
import com.moqserver.studio.domain.EndpointSummary
import com.moqserver.studio.domain.ProjectContext
import com.moqserver.studio.domain.ProviderKind
import com.moqserver.studio.domain.SelectionContext
import com.moqserver.studio.domain.StudioRootViewModel
import com.moqserver.studio.logging.loggerFor
import com.moqserver.studio.projectformat.MoqProject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

private val logger = loggerFor<AIProviderRegistry>()

/**
 * Creates an [AIProviderRegistry] populated with providers derived from the given [settings].
 * Providers with blank API keys are silently skipped.
 */
internal fun buildAIRegistry(settings: AISettings): AIProviderRegistry {
    val providers = mutableListOf<AIProvider>()
    providers += OllamaAIProvider(baseUrl = settings.ollama.baseUrl, defaultModel = settings.ollama.defaultModel)
    if (settings.openai.apiKey.isNotBlank()) {
        providers += OpenAIAIProvider(
            apiKey = settings.openai.apiKey,
            baseUrl = settings.openai.baseUrl,
            defaultModel = settings.openai.defaultModel,
        )
    }
    if (settings.anthropic.apiKey.isNotBlank()) {
        providers += AnthropicAIProvider(
            apiKey = settings.anthropic.apiKey,
            baseUrl = settings.anthropic.baseUrl,
            defaultModel = settings.anthropic.defaultModel,
        )
    }
    if (settings.gemini.apiKey.isNotBlank()) {
        providers += GeminiAIProvider(
            apiKey = settings.gemini.apiKey,
            baseUrl = settings.gemini.baseUrl,
            defaultModel = settings.gemini.defaultModel,
        )
    }
    return AIProviderRegistry(providers)
}

/**
 * Checks availability of all registered AI providers and pushes the results into [viewModel].
 */
internal suspend fun refreshAIProviders(
    registry: AIProviderRegistry,
    viewModel: StudioRootViewModel,
    ioDispatcher: CoroutineDispatcher,
) {
    logger.debug("Checking AI provider availability")
    viewModel.aiProvidersLoading()
    try {
        val infos = runOnIo(ioDispatcher) {
            registry.allProviders().map { provider ->
                providerInfo(provider)
            }
        }
        val available = infos.count { it.available }
        logger.info("AI providers refreshed: {}/{} available", available, infos.size)
        viewModel.aiProvidersLoaded(infos)
    } catch (e: Exception) {
        logger.warn("Failed to check AI provider availability: {}", e.message)
        viewModel.aiProvidersLoadFailed(e.message ?: "Unknown error")
    }
}

/**
 * Dispatches a single [AIAction] to the selected provider, updating [viewModel] with the result.
 */
internal suspend fun executeAIAction(
    action: AIAction,
    registry: AIProviderRegistry,
    viewModel: StudioRootViewModel,
    ioDispatcher: CoroutineDispatcher,
) {
    viewModel.aiActionStarted(action)

    val state = viewModel.state.value
    val providerId = state.ai.selectedProviderId
    if (providerId == null) {
        failMissingProvider(action, viewModel)
        return
    }
    val provider = registry.find(providerId)
    if (provider == null) {
        failProviderNotFound(action, providerId, viewModel)
        return
    }

    logger.info("Executing AI action: {} with provider={}", action, providerId)

    try {
        when (action) {
            AIAction.ANALYZE_SPEC -> {
                executeAnalyzeSpec(state.project, providerId, provider, registry, viewModel, ioDispatcher)
            }
            AIAction.GENERATE_VARIANTS -> {
                executeGenerateVariants(state, providerId, provider, registry, viewModel, ioDispatcher)
            }
            AIAction.REFINE_PROJECT -> {
                executeRefineProject(state.project, providerId, provider, registry, viewModel, ioDispatcher)
            }
        }
    } catch (e: Exception) {
        reportRecoverable(
            context = "AI action failed",
            throwable = e,
            onUserMessage = viewModel::aiActionFailed,
        )
    }
}

private fun failMissingProvider(
    action: AIAction,
    viewModel: StudioRootViewModel,
) {
    logger.warn("AI action {} requested but no provider selected", action)
    viewModel.aiActionFailed("No AI provider selected. Open Settings to configure one.")
}

private fun failProviderNotFound(
    action: AIAction,
    providerId: String,
    viewModel: StudioRootViewModel,
) {
    logger.warn("AI action {} requested but provider '{}' not found in registry", action, providerId)
    viewModel.aiActionFailed("Provider '$providerId' not found. Open Settings to reconfigure.")
}

private suspend fun providerInfo(provider: AIProvider): AIProviderInfo {
    return AIProviderInfo(
        id = provider.id,
        displayName = provider.displayName,
        kind = if (provider.kind == AIProviderKind.LOCAL) ProviderKind.LOCAL else ProviderKind.HOSTED,
        available = provider.checkAvailability(),
        capabilities = provider.capabilities.map { it.name }.toSet(),
    )
}

private suspend fun executeAnalyzeSpec(
    project: MoqProject?,
    providerId: String,
    provider: AIProvider,
    registry: AIProviderRegistry,
    viewModel: StudioRootViewModel,
    ioDispatcher: CoroutineDispatcher,
) {
    val request = CompanionRequest(
        providerId = providerId,
        projectContext = project?.let(::buildProjectContext),
    )
    val result = runOnIo(ioDispatcher) { registry.analyzeSpec(provider, request) }
    logger.info("AI analyze-spec succeeded (provider={})", providerId)
    viewModel.analyzeSpecCompleted(result)
}

private suspend fun executeGenerateVariants(
    state: com.moqserver.studio.domain.StudioState,
    providerId: String,
    provider: AIProvider,
    registry: AIProviderRegistry,
    viewModel: StudioRootViewModel,
    ioDispatcher: CoroutineDispatcher,
) {
    val selectedEndpoint = state.selectedEndpoint
    logger.debug(
        "generate-variants for endpoint: {} {}",
        selectedEndpoint?.method,
        selectedEndpoint?.path,
    )
    val request = CompanionRequest(
        providerId = providerId,
        projectContext = state.project?.let(::buildProjectContext),
        selection = selectedEndpoint?.let {
            SelectionContext(endpointKeys = listOf("${it.method} ${it.path}"))
        },
    )
    val result = runOnIo(ioDispatcher) { registry.generateVariants(provider, request) }
    logger.info("AI generate-variants succeeded (provider={})", providerId)
    viewModel.generateVariantsCompleted(result)
}

private suspend fun executeRefineProject(
    project: MoqProject?,
    providerId: String,
    provider: AIProvider,
    registry: AIProviderRegistry,
    viewModel: StudioRootViewModel,
    ioDispatcher: CoroutineDispatcher,
) {
    val request = CompanionRequest(
        providerId = providerId,
        projectContext = project?.let(::buildProjectContext),
    )
    val result = runOnIo(ioDispatcher) { registry.refineProject(provider, request) }
    logger.info("AI refine-project succeeded (provider={})", providerId)
    viewModel.refineProjectCompleted(result)
}

private suspend fun <T> runOnIo(
    ioDispatcher: CoroutineDispatcher,
    block: suspend () -> T,
): T {
    return withContext(ioDispatcher) {
        block()
    }
}

/**
 * Builds a [ProjectContext] snapshot from the given [project] for AI provider requests.
 */
internal fun buildProjectContext(project: MoqProject): ProjectContext {
    return ProjectContext(
        title = project.manifest.name,
        version = project.manifest.version,
        endpoints = project.endpoints.map { ep ->
            EndpointSummary(
                method = ep.method,
                path = ep.path,
                variantCount = ep.variants.size,
                hasAuth = ep.auth != null,
                tags = ep.tags,
            )
        },
    )
}
