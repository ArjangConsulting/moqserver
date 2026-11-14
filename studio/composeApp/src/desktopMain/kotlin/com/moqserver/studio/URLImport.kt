package com.moqserver.studio

import com.moqserver.studio.data.ImportHistoryRepository
import com.moqserver.studio.domain.ImportSourceType
import com.moqserver.studio.domain.ParsedSpec
import com.moqserver.studio.domain.StudioRootViewModel
import com.moqserver.studio.domain.toParsedSpec
import com.moqserver.studio.logging.loggerFor
import com.moqserver.studio.projectformat.MoqProject
import com.moqserver.studio.projectformat.format.FormatServiceException
import com.moqserver.studio.projectformat.format.ImportAuthInput
import kotlinx.coroutines.CoroutineDispatcher

private val logger = loggerFor<URLImportExecutionPlan>()

internal data class URLImportExecutionPlan(
	val operationLabel: String,
	val modeLabel: String,
	val requiresProject: Boolean,
)

internal fun planURLImportExecution(state: ImportFromURLState): URLImportExecutionPlan = URLImportExecutionPlan(
	operationLabel = if (state.action == URLImportAction.UPDATE) "Updating from" else "Importing",
	modeLabel = state.mode.name.lowercase(),
	requiresProject = state.action == URLImportAction.UPDATE,
)

internal fun shouldPersistImportedProjectImmediately(isUpdateMode: Boolean): Boolean = !isUpdateMode

internal fun buildURLImportAuth(state: ImportFromURLState): ImportAuthInput? = when (state.authType) {
	URLAuthType.BEARER -> ImportAuthInput(bearer = state.bearerToken)
	URLAuthType.BASIC ->
		ImportAuthInput(basic = ImportAuthInput.BasicAuthInput(state.basicUsername, state.basicPassword))
	URLAuthType.NONE -> null
}

/** Mirrors the resolved-host+path display name the old OpenAPIURLFetcher derived locally. */
internal fun sourceNameFromUrl(url: String): String = try {
	val uri = java.net.URI(url)
	val host = uri.host ?: url
	val path = uri.path?.trimEnd('/') ?: ""
	if (path.isNotEmpty()) "$host$path" else host
} catch (_: Exception) {
	url
}

/**
 * The message the Import from URL dialog shows for a failed import. moq-format's parseOpenapi
 * collapses fetch and parse failures into one call; its error code keeps the two messages distinct.
 */
internal fun urlImportErrorMessage(error: Exception): String? {
	val parseFailure = "Successfully fetched content from the URL, but it couldn't be parsed " +
		"as a valid API specification: ${error.message ?: "Unknown error"}"
	return when {
		error !is FormatServiceException -> parseFailure
		error.code == "E_IMPORT_PARSE_FAILED" -> parseFailure
		else -> error.message
	}
}

/** The fetched-and-parsed half of a URL import. */
private data class URLImportFetchResult(
	val sourceName: String,
	val resolvedUrl: String,
	val spec: ParsedSpec,
)

private fun applyURLImportResult(
	state: ImportFromURLState,
	executionPlan: URLImportExecutionPlan,
	currentProject: MoqProject?,
	fetched: URLImportFetchResult,
	appViewModel: StudioRootViewModel,
	previouslyDeselectedIds: Set<String>,
): Boolean {
	val (sourceName, resolvedUrl, spec) = fetched
	if (executionPlan.requiresProject) {
		if (currentProject == null) {
			return false
		}
		appViewModel.startUpdateFromSpec(
			spec = spec,
			source = ImportSourceType.OPENAPI,
			fileName = sourceName,
			previouslyDeselectedIds = previouslyDeselectedIds,
		)
	} else {
		appViewModel.startImport(spec, ImportSourceType.OPENAPI, sourceName)
	}
	logger.info(
		"URL {} started: {} endpoints from {}",
		if (state.action == URLImportAction.UPDATE) "update" else "import",
		spec.endpoints.size,
		resolvedUrl,
	)
	return true
}

/** Fetches and parses the spec, then starts the import or update; false when an update has no project. */
internal suspend fun performURLImport(
	state: ImportFromURLState,
	currentProject: MoqProject?,
	services: StudioServices,
	ioDispatcher: CoroutineDispatcher,
): Boolean {
	val executionPlan = planURLImportExecution(state)
	logger.info("{} {} spec from URL: {}", executionPlan.operationLabel, executionPlan.modeLabel, state.url)
	val result = services.formatClient.parseOpenapi(state.url, buildURLImportAuth(state))
	logger.info("Fetched and parsed spec from {}", result.resolvedSource)
	val previouslyDeselectedIds = if (executionPlan.requiresProject && currentProject != null) {
		loadDeselected(services.importHistoryRepo, currentProject, ioDispatcher)
	} else {
		emptySet()
	}
	return applyURLImportResult(
		state = state,
		executionPlan = executionPlan,
		currentProject = currentProject,
		fetched = URLImportFetchResult(
			sourceName = sourceNameFromUrl(result.resolvedSource),
			resolvedUrl = result.resolvedSource,
			spec = result.spec.toParsedSpec(),
		),
		appViewModel = services.appViewModel,
		previouslyDeselectedIds = previouslyDeselectedIds,
	)
}

internal suspend fun loadDeselected(
	importHistoryRepo: ImportHistoryRepository,
	project: MoqProject,
	ioDispatcher: CoroutineDispatcher,
): Set<String> = runOnIo(ioDispatcher) { importHistoryRepo.loadDeselected(project.projectPath) }
