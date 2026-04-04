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
import com.moqserver.studio.domain.IntentContext
import com.moqserver.studio.domain.ProjectContext
import com.moqserver.studio.domain.ProviderKind
import com.moqserver.studio.domain.RequestOptions
import com.moqserver.studio.domain.SelectionContext
import com.moqserver.studio.domain.StudioRootViewModel
import com.moqserver.studio.endpointdetail.parseJsonBodyText
import com.moqserver.studio.logging.loggerFor
import com.moqserver.studio.projectformat.EndpointDocument
import com.moqserver.studio.projectformat.MoqProject
import com.moqserver.studio.projectformat.ProjectVariant
import com.moqserver.studio.projectformat.YamlValue
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

internal fun buildAIProviderForTesting(
    settings: AISettings,
    providerId: String,
): AIProvider? = when (providerId) {
    OllamaAIProvider.PROVIDER_ID -> OllamaAIProvider(
        baseUrl = settings.ollama.baseUrl,
        defaultModel = settings.ollama.defaultModel,
    )

    OpenAIAIProvider.PROVIDER_ID -> OpenAIAIProvider(
        apiKey = settings.openai.apiKey,
        baseUrl = settings.openai.baseUrl,
        defaultModel = settings.openai.defaultModel,
    )

    AnthropicAIProvider.PROVIDER_ID -> AnthropicAIProvider(
        apiKey = settings.anthropic.apiKey,
        baseUrl = settings.anthropic.baseUrl,
        defaultModel = settings.anthropic.defaultModel,
    )

    GeminiAIProvider.PROVIDER_ID -> GeminiAIProvider(
        apiKey = settings.gemini.apiKey,
        baseUrl = settings.gemini.baseUrl,
        defaultModel = settings.gemini.defaultModel,
    )

    else -> null
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
            AIAction.GENERATE_BODY -> {
                logger.warn("executeAIAction does not support GENERATE_BODY directly")
                viewModel.aiActionFailed("Body generation must target a specific variant.")
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

internal suspend fun generateBodyForVariant(
    endpointId: String,
    variantReferenceName: String,
    prompt: String,
    registry: AIProviderRegistry,
    viewModel: StudioRootViewModel,
    ioDispatcher: CoroutineDispatcher,
) {
    if (prompt.isBlank()) {
        viewModel.setError("Enter an AI prompt before generating content.")
        return
    }

    viewModel.aiActionStarted(AIAction.GENERATE_BODY)

    val state = viewModel.state.value
    val providerId = state.ai.selectedProviderId
    if (providerId == null) {
        viewModel.dismissAIAction()
        viewModel.setError("No AI provider selected. Open AI Settings to configure one.")
        return
    }

    val provider = registry.find(providerId)
    if (provider == null) {
        viewModel.dismissAIAction()
        viewModel.setError("Provider '$providerId' not found. Open AI Settings to reconfigure.")
        return
    }

    val endpoint = state.project?.endpoints?.find { it.id == endpointId }
    val variant = endpoint?.findVariant(variantReferenceName)
    if (endpoint == null || variant == null) {
        viewModel.dismissAIAction()
        viewModel.setError("The selected variant is no longer available.")
        return
    }

    logger.info(
        "Generating AI body for endpoint={} {}, variant={}, provider={}",
        endpoint.method,
        endpoint.path,
        variant.referenceName,
        providerId,
    )

    try {
        val request = CompanionRequest(
            providerId = providerId,
            projectContext = state.project?.let(::buildProjectContext),
            selection = SelectionContext(
                endpointKeys = listOf("${endpoint.method} ${endpoint.path}"),
                variantNames = listOf(variant.name),
            ),
            intent = IntentContext(
                type = "body-generation",
                description = buildBodyGenerationIntent(endpoint, variant, prompt),
            ),
            options = RequestOptions(maxVariants = 1),
        )

        val result = runOnIo(ioDispatcher) { registry.generateVariants(provider, request) }
        val generated = result.result.variants.firstOrNull()
        if (generated == null) {
            viewModel.dismissAIAction()
            viewModel.setError("AI did not return any response content for ${endpoint.method} ${endpoint.path}.")
            return
        }

        val latestState = viewModel.state.value
        val latestEndpoint = latestState.project?.endpoints?.find { it.id == endpointId }
        val latestVariant = latestEndpoint?.findVariant(variantReferenceName)
        if (latestEndpoint == null || latestVariant == null) {
            viewModel.dismissAIAction()
            viewModel.setError("The selected variant changed before the AI response was applied.")
            return
        }

        viewModel.updateEndpoint(applyGeneratedBody(latestEndpoint, latestVariant, generated.body, generated.contentType))
        viewModel.dismissAIAction()
        viewModel.setStatus("AI generated body for ${latestVariant.name} (${latestEndpoint.method} ${latestEndpoint.path})")
    } catch (e: Exception) {
        viewModel.dismissAIAction()
        reportRecoverable(
            context = "AI body generation failed",
            throwable = e,
            onUserMessage = viewModel::setError,
        )
    }
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

private fun buildBodyGenerationIntent(
    endpoint: EndpointDocument,
    variant: ProjectVariant,
    prompt: String,
): String {
    val contentType = variant.contentType()
    return buildString {
        appendLine("Generate a single response body for the selected mock variant.")
        appendLine("Endpoint: ${endpoint.method} ${endpoint.path}")
        appendLine("Variant name: ${variant.name}")
        appendLine("HTTP status code: ${variant.status}")
        contentType?.let { appendLine("Content-Type: $it") }
        appendLine("Use the current variant name and status code in the returned object.")
        append("User prompt: $prompt")
    }
}

private fun applyGeneratedBody(
    endpoint: EndpointDocument,
    variant: ProjectVariant,
    body: String,
    contentType: String,
): EndpointDocument {
    val normalizedContentType = contentType.trim()
    val parsedBody = if (isJsonContentType(normalizedContentType)) {
        parseJsonBodyText(body).getOrElse { YamlValue.Str(body) }
    } else {
        parseJsonBodyText(body).getOrElse { YamlValue.Str(body) }
    }
    val updatedVariant = variant.copy(
        body = parsedBody,
        bodyFile = null,
        headers = updatedHeaders(variant.headers, normalizedContentType),
    )
    return endpoint.copy(
        variants = endpoint.variants.map { existing ->
            if (existing.referenceName == variant.referenceName) updatedVariant else existing
        },
    )
}

private fun EndpointDocument.findVariant(referenceName: String): ProjectVariant? {
    return variants.firstOrNull { it.referenceName == referenceName }
}

private fun ProjectVariant.contentType(): String? {
    return headers
        ?.entries
        ?.firstOrNull { it.key.equals(CONTENT_TYPE_HEADER, ignoreCase = true) }
        ?.value
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

private fun updatedHeaders(
    headers: Map<String, String>?,
    contentType: String,
): Map<String, String>? {
    if (contentType.isBlank()) return headers
    val updated = headers
        ?.filterKeys { !it.equals(CONTENT_TYPE_HEADER, ignoreCase = true) }
        ?.toMutableMap()
        ?: mutableMapOf()
    updated[CONTENT_TYPE_HEADER] = contentType
    return updated
}

private fun isJsonContentType(contentType: String): Boolean {
    val normalized = contentType.substringBefore(';').trim().lowercase()
    return normalized == "application/json" ||
        normalized == "application/graphql-response+json" ||
        normalized.endsWith("+json")
}

private const val CONTENT_TYPE_HEADER = "Content-Type"
