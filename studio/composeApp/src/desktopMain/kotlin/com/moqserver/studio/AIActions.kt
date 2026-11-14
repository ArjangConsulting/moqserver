package com.moqserver.studio

import com.moqserver.studio.ai.AIProviderRegistry
import com.moqserver.studio.domain.AIAction
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** AI commands bound to the provider registry built from the current settings. */
internal class AIActions(
	private val services: StudioServices,
	private val registry: AIProviderRegistry,
	private val scope: CoroutineScope,
	private val handler: CoroutineExceptionHandler,
) {
	private val viewModel get() = services.appViewModel

	suspend fun refreshProviders() {
		refreshAIProviders(registry, viewModel, services.ioDispatcher)
	}

	fun requestRefreshProviders() {
		scope.launch(handler) { refreshProviders() }
	}

	fun execute(action: AIAction) {
		scope.launch(handler) { executeAIAction(action, registry, viewModel, services.ioDispatcher) }
	}

	fun generateBody(endpointId: String, variantReferenceName: String, prompt: String) {
		scope.launch(handler) {
			generateBodyForVariant(
				endpointId = endpointId,
				variantReferenceName = variantReferenceName,
				prompt = prompt,
				registry = registry,
				viewModel = viewModel,
				ioDispatcher = services.ioDispatcher,
			)
		}
	}

	fun generateImportMocks(index: Int) {
		scope.launch(handler) {
			generateImportMocksForEndpoint(index, registry, viewModel, services.ioDispatcher)
		}
	}

	fun generateAllImportMocks() {
		scope.launch(handler) {
			generateImportMocksForAcceptedEndpoints(registry, viewModel, services.ioDispatcher)
		}
	}
}
