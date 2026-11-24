package com.moqserver.studio

import com.moqserver.studio.data.HARImportSettings
import com.moqserver.studio.domain.EndpointUpdateStatus
import com.moqserver.studio.domain.ImportConverter
import com.moqserver.studio.domain.ImportMode
import com.moqserver.studio.domain.ImportSourceType
import com.moqserver.studio.domain.ImportState
import com.moqserver.studio.domain.ParsedSpec
import com.moqserver.studio.domain.toParsedSpec
import com.moqserver.studio.logging.loggerFor
import com.moqserver.studio.projectformat.format.FormatClient
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.awt.Window as AwtWindow

/** A spec file Studio can import from or update a project with. */
internal enum class ImportFileKind(
	val label: String,
	val extensions: Set<String>,
	val sourceType: ImportSourceType,
	val failureContext: String,
) {
	OPENAPI("OpenAPI Spec", setOf("yaml", "yml", "json"), ImportSourceType.OPENAPI, "Failed to parse OpenAPI spec"),
	SWAGGER("Swagger Spec", setOf("yaml", "yml", "json"), ImportSourceType.OPENAPI, "Failed to parse Swagger spec"),
	HAR("HAR File", setOf("har", "json"), ImportSourceType.HAR, "Failed to parse HAR file"),
	;

	suspend fun parse(client: FormatClient, path: String, har: HARImportSettings): ParsedSpec = when (this) {
		OPENAPI, SWAGGER -> client.parseOpenapi(path).spec.toParsedSpec()
		HAR -> client.parseHar(path, har.redactHeaders, har.inferHeaderRules).toParsedSpec()
	}
}

/** Import and update-from-spec commands, and confirming the Import Review dialog. */
internal class ImportActions(
	private val services: StudioServices,
	private val appState: StudioAppState,
	private val projectActions: ProjectActions,
	private val scope: CoroutineScope,
	private val handler: CoroutineExceptionHandler,
	private val window: AwtWindow,
) {
	private val logger = loggerFor<ImportActions>()
	private val viewModel get() = services.appViewModel

	/** Starts a new project from a spec file, after resolving unsaved changes. */
	fun requestImportFile(kind: ImportFileKind) {
		scope.launch(handler) {
			logger.debug("User requested import {}", kind.label)
			if (!projectActions.guardProjectTransition()) {
				logger.debug("Import {} cancelled while resolving current project state", kind.label)
				return@launch
			}
			val file = chooseImportFile(kind, "Import ${kind.label}") ?: return@launch
			logger.info("Importing {} from: {}", kind.label, file.absolutePath)
			runReporting(kind.failureContext, viewModel::setError) {
				val spec = kind.parse(services.formatClient, file.absolutePath, appState.aiSettings.harImport)
				viewModel.startImport(spec, kind.sourceType, file.name)
				appState.rememberDirectoryOf(file)
			}
		}
	}

	/** Diffs the open project against a spec file. */
	fun requestUpdateFromFile(kind: ImportFileKind) {
		val currentProject = viewModel.state.value.project ?: return
		scope.launch(handler) {
			logger.debug("User requested update from {}", kind.label)
			val file = chooseImportFile(kind, "Update from ${kind.label}") ?: return@launch
			logger.info("Updating project '{}' from {}: {}", currentProject.manifest.name, kind.label, file.absolutePath)
			runReporting(kind.failureContext, viewModel::setError) {
				val spec = kind.parse(services.formatClient, file.absolutePath, appState.aiSettings.harImport)
				val previouslyDeselected = loadDeselected(
					services.importHistoryRepo,
					currentProject,
					services.ioDispatcher,
				)
				viewModel.startUpdateFromSpec(
					spec = spec,
					source = kind.sourceType,
					fileName = file.name,
					previouslyDeselectedIds = previouslyDeselected,
				)
				appState.rememberDirectoryOf(file)
			}
		}
	}

	fun requestImportFromURL(mode: URLImportMode, action: URLImportAction) {
		if (action == URLImportAction.IMPORT && !projectActions.guardProjectTransition()) {
			logger.debug("Import from URL cancelled while resolving current project state")
			return
		}
		if (action == URLImportAction.UPDATE && viewModel.state.value.project == null) return
		appState.importURLState = ImportFromURLState(mode = mode, action = action)
		appState.showImportURLDialog = true
	}

	fun executeURLImport() {
		val request = appState.importURLState
		if (request.url.isBlank() || request.loading) return
		appState.importURLState = request.copy(loading = true, error = null)
		scope.launch(handler) {
			val error = try {
				val applied = performURLImport(
					state = request,
					currentProject = viewModel.state.value.project,
					services = services,
					ioDispatcher = services.ioDispatcher,
				)
				if (applied) null else "Open a project before updating from URL."
			} catch (e: Exception) {
				e.rethrowIfCancellation()
				logger.error("URL import failed: {}", e.message)
				urlImportErrorMessage(e)
			}
			if (error == null) {
				appState.dismissImportURLDialog()
			} else {
				appState.importURLState = appState.importURLState.copy(loading = false, error = error)
			}
		}
	}

	/** Applies the accepted entries of the Import Review dialog. */
	fun confirmImport() {
		scope.launch(handler) {
			val importState = viewModel.state.value.importState ?: return@launch
			logger.debug(
				"User confirming import: source={}, accepted={}/{}",
				importState.sourceFileName,
				importState.entries.count { it.accepted },
				importState.entries.size,
			)
			if (importState.isUpdateMode) confirmUpdate(importState) else confirmNewProject(importState)
		}
	}

	private suspend fun confirmUpdate(importState: ImportState) {
		val existingProjectPath = (importState.mode as? ImportMode.UpdateExisting)?.existingProject?.projectPath
		val deselectedIds = importState.entries
			.filter { !it.accepted && it.updateStatus != EndpointUpdateStatus.UNCHANGED }
			.map { ImportConverter.endpointId(it.endpoint.method, it.endpoint.path) }
			.toSet()
		val project = viewModel.confirmImport(existingProjectPath ?: importState.parsedSpec.title) ?: return
		runOnIo(services.ioDispatcher) {
			services.importHistoryRepo.saveDeselected(project.projectPath, deselectedIds)
		}
		if (shouldPersistImportedProjectImmediately(isUpdateMode = true)) {
			projectActions.persist(project, project.projectPath)
		}
		logger.info("Update import applied: {} endpoint(s) staged in {}", project.endpoints.size, project.projectPath)
	}

	private suspend fun confirmNewProject(importState: ImportState) {
		val path = chooseProjectDirectory(
			parent = window,
			title = "Import Project",
			initialDirectory = appState.lastFileDirectory.value,
			projectName = importState.projectName,
		)
		if (path == null) {
			logger.debug("Import save dialog cancelled")
			return
		}
		logger.info("Saving imported project '{}' to: {}", importState.projectName, path)
		runReporting("Failed to save imported project", viewModel::setError) {
			val project = viewModel.confirmImport(path) ?: return@runReporting
			if (shouldPersistImportedProjectImmediately(isUpdateMode = false)) {
				projectActions.persist(project, path)
			}
			appState.lastFileDirectory.value = File(path).parentFile?.canonicalPath ?: path
			logger.info("Import complete: {} endpoint(s) saved to {}", project.endpoints.size, path)
		}
	}

	private fun chooseImportFile(kind: ImportFileKind, title: String): File? {
		val file = chooseFile(window, title, kind.extensions, appState.lastFileDirectory.value)
		if (file == null) logger.debug("{} dialog cancelled", title)
		return file
	}
}
