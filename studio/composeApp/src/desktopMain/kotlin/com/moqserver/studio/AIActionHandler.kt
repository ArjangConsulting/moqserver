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
import com.moqserver.studio.domain.ImportState
import com.moqserver.studio.domain.IntentContext
import com.moqserver.studio.domain.ParsedEndpoint
import com.moqserver.studio.domain.ParsedResponse
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
		e.rethrowIfCancellation()
		logger.warn("Failed to check AI provider availability: {}", e.message)
		viewModel.aiProvidersLoadFailed(e.message ?: "Unknown error")
	}
}

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
		e.rethrowIfCancellation()
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
	viewModel.aiActionFailed("No AI provider selected. Open AI Settings to configure one.")
}

private fun failProviderNotFound(
	action: AIAction,
	providerId: String,
	viewModel: StudioRootViewModel,
) {
	logger.warn("AI action {} requested but provider '{}' not found in registry", action, providerId)
	viewModel.aiActionFailed("Provider '$providerId' not found. Open AI Settings to reconfigure.")
}

private suspend fun providerInfo(provider: AIProvider): AIProviderInfo {
	return AIProviderInfo(
		id = provider.id,
		displayName = provider.displayName,
		kind = if (provider.kind == AIProviderKind.LOCAL) ProviderKind.LOCAL else ProviderKind.HOSTED,
		available = provider.checkAvailability(),
		capabilities = provider.capabilities.map { it.name }.toSet(),
		defaultModel = providerDefaultModel(provider),
		baseUrl = providerBaseUrl(provider),
	)
}

private fun providerDefaultModel(provider: AIProvider): String? = when (provider) {
	is OllamaAIProvider -> provider.defaultModel
	is OpenAIAIProvider -> provider.defaultModel
	is AnthropicAIProvider -> provider.defaultModel
	is GeminiAIProvider -> provider.defaultModel
	else -> null
}

private fun providerBaseUrl(provider: AIProvider): String? = when (provider) {
	is OllamaAIProvider -> provider.baseUrl
	is OpenAIAIProvider -> provider.baseUrl
	is AnthropicAIProvider -> provider.baseUrl
	is GeminiAIProvider -> provider.baseUrl
	else -> null
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
	val normalizedResult = selectedEndpoint?.let { endpoint ->
		result.withResolvedEndpointFallback("${endpoint.method} ${endpoint.path}")
	} ?: result
	logger.info("AI generate-variants succeeded (provider={})", providerId)
	viewModel.generateVariantsCompleted(normalizedResult)
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
		viewModel.aiActionFailed("Enter an AI prompt before generating content.")
		return
	}

	viewModel.aiActionStarted(AIAction.GENERATE_BODY)
	val context = resolveBodyGenerationContext(
		endpointId = endpointId,
		variantReferenceName = variantReferenceName,
		registry = registry,
		viewModel = viewModel,
	) ?: return

	logger.info(
		"Generating AI body for endpoint={} {}, variant={}, provider={}",
		context.endpoint.method,
		context.endpoint.path,
		context.variant.referenceName,
		context.providerId,
	)

	requestGeneratedBody(context, prompt, registry, viewModel, ioDispatcher)
}

private suspend fun requestGeneratedBody(
	context: BodyGenerationContext,
	prompt: String,
	registry: AIProviderRegistry,
	viewModel: StudioRootViewModel,
	ioDispatcher: CoroutineDispatcher,
) {
	try {
		logger.info(
			"AI body generation request starting provider={} model={} endpoint={} {} variant={}",
			context.provider.id,
			effectiveModelFor(context.provider),
			context.endpoint.method,
			context.endpoint.path,
			context.variant.referenceName,
		)

		val request = buildBodyGenerationRequest(context, prompt)
		logger.info(
			"=== AI BODY GENERATION INTENT ===\n{}\n=== END INTENT ===",
			request.intent?.description,
		)

		val result = runOnIo(ioDispatcher) { registry.generateVariants(context.provider, request) }
		val normalizedResult = result.withResolvedEndpointFallback(context.endpointKey)
		val generated = normalizedResult.result.variants.firstOrNull()
		if (generated == null) {
			viewModel.aiActionFailed(
				"AI did not return any response content for ${context.endpoint.method} ${context.endpoint.path}.",
			)
			return
		}

		applyGeneratedBodyResult(context, generated, prompt, viewModel)
	} catch (e: Exception) {
		e.rethrowIfCancellation()
		reportRecoverable(
			context = "AI body generation failed",
			throwable = e,
			onUserMessage = viewModel::aiActionFailed,
		)
	}
}

private fun buildBodyGenerationRequest(
	context: BodyGenerationContext,
	prompt: String,
): CompanionRequest {
	return CompanionRequest(
		providerId = context.providerId,
		projectContext = context.state.project?.let(::buildProjectContext),
		selection = SelectionContext(
			endpointKeys = listOf(context.endpointKey),
			variantNames = listOf(context.variant.name),
		),
		intent = IntentContext(
			type = "body-generation",
			description = buildBodyGenerationIntent(context.endpoint, context.variant, prompt),
		),
		options = RequestOptions(maxVariants = 1),
	)
}

private fun applyGeneratedBodyResult(
	context: BodyGenerationContext,
	generated: com.moqserver.studio.domain.GeneratedVariant,
	prompt: String,
	viewModel: StudioRootViewModel,
) {
	logger.info(
		"=== AI GENERATED BODY for variant={}: contentType={}, body preview={} ===",
		context.variant.referenceName,
		generated.contentType,
		generated.body.take(500),
	)

	val latestContext = resolveLatestBodyGenerationContext(context, viewModel)
	if (latestContext == null) {
		viewModel.aiActionFailed("The selected variant changed before the AI response was applied.")
		return
	}

	viewModel.updateEndpoint(
		applyGeneratedBody(
			latestContext.endpoint,
			latestContext.variant,
			generated.body,
			prompt,
			generated.contentType,
		),
	)
	viewModel.dismissAIAction()
	viewModel.setStatus(
		"AI generated body for ${latestContext.variant.name} (${latestContext.endpoint.method} ${latestContext.endpoint.path})",
	)
}

private fun resolveBodyGenerationContext(
	endpointId: String,
	variantReferenceName: String,
	registry: AIProviderRegistry,
	viewModel: StudioRootViewModel,
): BodyGenerationContext? {
	val state = viewModel.state.value
	val providerId = state.ai.selectedProviderId
	if (providerId == null) {
		viewModel.aiActionFailed("No AI provider selected. Open AI Settings to configure one.")
		return null
	}
	val provider = registry.find(providerId)
	if (provider == null) {
		viewModel.aiActionFailed("Provider '$providerId' not found. Open AI Settings to reconfigure.")
		return null
	}
	val endpoint = state.project?.endpoints?.find { it.id == endpointId }
	val variant = endpoint?.findVariant(variantReferenceName)
	if (endpoint == null || variant == null) {
		viewModel.aiActionFailed("The selected variant is no longer available.")
		return null
	}
	return BodyGenerationContext(
		state = state,
		providerId = providerId,
		provider = provider,
		endpointId = endpointId,
		variantReferenceName = variantReferenceName,
		endpoint = endpoint,
		variant = variant,
	)
}

private fun resolveLatestBodyGenerationContext(
	context: BodyGenerationContext,
	viewModel: StudioRootViewModel,
): ResolvedEndpointVariant? {
	val latestState = viewModel.state.value
	val latestEndpoint = latestState.project?.endpoints?.find { it.id == context.endpointId } ?: return null
	val latestVariant = latestEndpoint.findVariant(context.variantReferenceName) ?: return null
	return ResolvedEndpointVariant(latestEndpoint, latestVariant)
}

private data class BodyGenerationContext(
	val state: com.moqserver.studio.domain.StudioState,
	val providerId: String,
	val provider: AIProvider,
	val endpointId: String,
	val variantReferenceName: String,
	val endpoint: EndpointDocument,
	val variant: ProjectVariant,
) {
	val endpointKey: String
		get() = "${endpoint.method} ${endpoint.path}"
}

private data class ResolvedEndpointVariant(
	val endpoint: EndpointDocument,
	val variant: ProjectVariant,
)

internal suspend fun generateImportMocksForEndpoint(
	index: Int,
	registry: AIProviderRegistry,
	viewModel: StudioRootViewModel,
	ioDispatcher: CoroutineDispatcher,
) {
	val state = viewModel.state.value
	val importState = state.importState ?: return
	val entry = importState.entries.getOrNull(index) ?: return
	val providerId = state.ai.selectedProviderId
	if (providerId == null) {
		viewModel.importAIGenerationFailed(index, "No AI provider selected. Open AI Settings to configure one.")
		return
	}

	val provider = registry.find(providerId)
	if (provider == null) {
		viewModel.importAIGenerationFailed(index, "Provider '$providerId' not found. Open AI Settings to reconfigure.")
		return
	}

	viewModel.importAIGenerationStarted(index)
	try {
		val request = CompanionRequest(
			providerId = providerId,
			projectContext = buildImportProjectContext(importState),
			selection = SelectionContext(
				endpointKeys = listOf(importEndpointKey(entry.endpoint)),
			),
			intent = IntentContext(
				type = "import-generation",
				description = buildImportGenerationIntent(entry.endpoint, entry.aiContextHint),
			),
			options = RequestOptions(maxVariants = IMPORT_GENERATION_MAX_VARIANTS),
		)
		val result = runOnIo(ioDispatcher) { registry.generateVariants(provider, request) }
		val normalizedResult = result.withResolvedEndpointFallback(importEndpointKey(entry.endpoint))
		val generatedResponses = normalizedResult.result.variants
			.filter { it.endpointKey == importEndpointKey(entry.endpoint) }
			.map(::generatedVariantToParsedResponse)
		viewModel.importAIGenerationCompleted(index, generatedResponses)
	} catch (e: Exception) {
		e.rethrowIfCancellation()
		reportRecoverable(
			context = "Import AI generation failed",
			throwable = e,
			onUserMessage = { message -> viewModel.importAIGenerationFailed(index, message) },
		)
	}
}

internal suspend fun generateImportMocksForAcceptedEndpoints(
	registry: AIProviderRegistry,
	viewModel: StudioRootViewModel,
	ioDispatcher: CoroutineDispatcher,
) {
	val importState = viewModel.state.value.importState ?: return
	val acceptedIndexes = importState.entries.mapIndexedNotNull { index, entry ->
		index.takeIf { entry.accepted }
	}
	if (acceptedIndexes.isEmpty()) {
		viewModel.setStatus("Select at least one endpoint before generating AI mocks.")
		return
	}

	viewModel.importAIBulkStarted(acceptedIndexes.size)
	acceptedIndexes.forEachIndexed { completedCount, index ->
		generateImportMocksForEndpoint(index, registry, viewModel, ioDispatcher)
		viewModel.importAIBulkProgress(completedCount + 1)
	}
	viewModel.importAIBulkFinished()
}

private fun effectiveModelFor(provider: AIProvider): String = when (provider) {
	is OllamaAIProvider -> provider.defaultModel
	is OpenAIAIProvider -> provider.defaultModel
	is AnthropicAIProvider -> provider.defaultModel
	is GeminiAIProvider -> provider.defaultModel
	else -> "<unknown>"
}

private fun buildImportProjectContext(importState: ImportState): ProjectContext {
	return ProjectContext(
		title = importState.parsedSpec.title,
		version = importState.parsedSpec.version,
		endpoints = importState.entries
			.filter { it.accepted }
			.map { entry ->
				EndpointSummary(
					method = entry.endpoint.method,
					path = entry.endpoint.path,
					variantCount = entry.endpoint.responses.size + entry.generatedResponses.size,
					hasAuth = entry.endpoint.authType != com.moqserver.studio.projectformat.AuthType.NONE,
				)
			},
	)
}

private fun importEndpointKey(endpoint: ParsedEndpoint): String = "${endpoint.method.uppercase()} ${endpoint.path}"

private fun buildImportGenerationIntent(endpoint: ParsedEndpoint, aiContextHint: String = ""): String {
	return buildString {
		appendLine("Generate extra mock response variants for an imported API endpoint.")
		appendLine("Endpoint: ${endpoint.method.uppercase()} ${endpoint.path}")
		endpoint.description?.takeIf { it.isNotBlank() }?.let { appendLine("Description: $it") }
		if (endpoint.responses.isNotEmpty()) {
			appendLine()
			appendLine("Existing imported response variants:")
			endpoint.responses.forEach { response ->
				appendLine("- ${response.statusCode} ${response.name}")
				response.headers[CONTENT_TYPE_HEADER]?.let { appendLine("  Content-Type: $it") }
				response.body?.takeIf { it.isNotBlank() }?.let { body ->
					appendLine("  Body example:")
					appendLine("  ```")
					appendLine(body)
					appendLine("  ```")
				}
			}
		}
		if (endpoint.requiredQueryParameters.isNotEmpty()) {
			appendLine()
			appendLine("Required query parameters: ${endpoint.requiredQueryParameters.joinToString()}")
		}
		if (endpoint.requiredHeaders.isNotEmpty()) {
			appendLine("Required headers: ${endpoint.requiredHeaders.joinToString()}")
		}
		if (endpoint.acceptedContentTypes.isNotEmpty()) {
			appendLine("Accepted request content types: ${endpoint.acceptedContentTypes.joinToString()}")
		}
		if (aiContextHint.isNotBlank()) {
			appendLine()
			appendLine("Additional context from the user:")
			appendLine(aiContextHint.trim())
		}
		appendLine()
		appendLine("Generate realistic additional variants that complement these imported responses.")
		appendLine("Preserve the endpoint's schema style and avoid duplicating existing variants.")
		appendLine("Prefer useful happy-path alternatives and common error cases.")
	}
}

private fun generatedVariantToParsedResponse(variant: com.moqserver.studio.domain.GeneratedVariant): ParsedResponse {
	return ParsedResponse(
		name = variant.name,
		statusCode = variant.statusCode,
		headers = mapOf(CONTENT_TYPE_HEADER to variant.contentType),
		body = variant.body,
		description = variant.description,
	)
}

private fun com.moqserver.studio.domain.CompanionResponse<com.moqserver.studio.domain.GenerateVariantsResult>.withResolvedEndpointFallback(
	endpointKey: String,
): com.moqserver.studio.domain.CompanionResponse<com.moqserver.studio.domain.GenerateVariantsResult> {
	return copy(
		result = result.copy(
			variants = result.variants.map { variant ->
				if (variant.endpointKey == "UNMAPPED") {
					variant.copy(endpointKey = endpointKey)
				} else {
					variant
				}
			},
		),
	)
}

private suspend fun <T> runOnIo(
	ioDispatcher: CoroutineDispatcher,
	block: suspend () -> T,
): T {
	return withContext(ioDispatcher) {
		block()
	}
}

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
	val currentBody = variant.body?.let(::serializeBodyForPrompt)
	return buildString {
		appendLine("Generate a response body for the selected mock variant.")
		appendLine("Endpoint: ${endpoint.method} ${endpoint.path}")
		appendLine("Variant name: ${variant.name}")
		appendLine("HTTP status code: ${variant.status}")
		contentType?.let { appendLine("Content-Type: $it") }
		if (currentBody != null) {
			appendLine()
			appendLine("Current body (this is the existing response body for this variant — use its exact schema):")
			appendLine("```")
			appendLine(currentBody)
			appendLine("```")
			appendLine()
			appendLine(
				"IMPORTANT: Modify the existing body according to the user prompt below. " +
					"Preserve all existing items and fields unless the user explicitly asks to remove or replace them. " +
					"If the user asks to add items, append them to the existing collection while keeping every existing entry intact. " +
					"The returned body MUST use the exact same JSON schema/structure as the current body shown above.",
			)
		}
		appendLine("Use the current variant name and status code in the returned object.")
		append("User prompt: $prompt")
	}
}

private fun serializeBodyForPrompt(body: YamlValue): String? {
	return when (body) {
		is YamlValue.Null -> null
		is YamlValue.Str -> body.value.takeIf { it.isNotBlank() }
		else -> com.moqserver.studio.endpointdetail.yamlValueToJsonString(body)
	}
}

private fun applyGeneratedBody(
	endpoint: EndpointDocument,
	variant: ProjectVariant,
	body: String,
	userPrompt: String,
	contentType: String,
): EndpointDocument {
	val normalizedContentType = contentType.trim()
	val parsedBody = parseJsonBodyText(body).getOrElse { YamlValue.Str(body) }
	val resolvedBody = mergeGeneratedBodyIfNeeded(
		existingBody = variant.body,
		generatedBody = parsedBody,
		userPrompt = userPrompt,
	)
	val updatedVariant = variant.copy(
		body = resolvedBody,
		bodyFile = null,
		headers = updatedHeaders(variant.headers, normalizedContentType),
	)
	return endpoint.copy(
		variants = endpoint.variants.map { existing ->
			if (existing.referenceName == variant.referenceName) updatedVariant else existing
		},
	)
}

internal fun mergeGeneratedBodyIfNeeded(
	existingBody: YamlValue?,
	generatedBody: YamlValue,
	userPrompt: String,
): YamlValue {
	if (!shouldAttemptAdditiveMerge(userPrompt)) return generatedBody

	return when {
		existingBody is YamlValue.Array && generatedBody is YamlValue.Array -> {
			mergeArrays(existingBody, generatedBody)
		}
		existingBody is YamlValue.Array && generatedBody !is YamlValue.Array -> {
			if (existingBody.value.contains(generatedBody)) existingBody else YamlValue.Array(existingBody.value + generatedBody)
		}
		existingBody is YamlValue.Obj && generatedBody is YamlValue.Obj -> {
			mergeObjectWrappedArrays(existingBody, generatedBody) ?: generatedBody
		}
		else -> generatedBody
	}
}

private fun shouldAttemptAdditiveMerge(userPrompt: String): Boolean {
	val normalized = userPrompt.lowercase()
	return listOf("add", "append", "include", "insert", "more", "additional", "generate up to").any { phrase ->
		normalized.contains(phrase)
	}
}

private fun mergeArrays(
	existingBody: YamlValue.Array,
	generatedBody: YamlValue.Array,
): YamlValue.Array {
	return if (generatedBody.value.take(existingBody.value.size) == existingBody.value) {
		generatedBody
	} else {
		YamlValue.Array(existingBody.value + generatedBody.value.filterNot(existingBody.value::contains))
	}
}

private fun mergeObjectWrappedArrays(
	existingBody: YamlValue.Obj,
	generatedBody: YamlValue.Obj,
): YamlValue? {
	val sharedArrayKeys = existingBody.value.keys.intersect(generatedBody.value.keys).filter { key ->
		existingBody.value[key] is YamlValue.Array && generatedBody.value[key] is YamlValue.Array
	}
	if (sharedArrayKeys.size != 1) return null

	val arrayKey = sharedArrayKeys.single()
	val existingArray = existingBody.value.getValue(arrayKey) as YamlValue.Array
	val generatedArray = generatedBody.value.getValue(arrayKey) as YamlValue.Array
	return YamlValue.Obj(
		existingBody.value + mapOf(
			arrayKey to mergeArrays(existingArray, generatedArray),
		),
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

private const val CONTENT_TYPE_HEADER = "Content-Type"
private const val IMPORT_GENERATION_MAX_VARIANTS = 3
