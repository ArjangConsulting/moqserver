package com.moqserver.studio

import com.moqserver.studio.export.ExportCatalogBuilder
import com.moqserver.studio.export.ExportOptions
import com.moqserver.studio.export.ExportRegistry
import com.moqserver.studio.logging.loggerFor
import com.moqserver.studio.projectformat.MoqProject
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.awt.Window as AwtWindow

/** Project lifecycle commands from the menu bar, landing screen and OS file-open events. */
internal class ProjectActions(
	private val services: StudioServices,
	private val appState: StudioAppState,
	private val scope: CoroutineScope,
	private val handler: CoroutineExceptionHandler,
	private val window: AwtWindow,
) {
	private val logger = loggerFor<ProjectActions>()
	private val viewModel get() = services.appViewModel

	/** Asks about unsaved changes; false when the user keeps the current project open. */
	fun guardProjectTransition(): Boolean = confirmProjectTransition(owner = window, state = viewModel.state.value)

	/** Opens the path from the command line or an OS file-open event. */
	suspend fun openPendingProject() {
		val path = appState.pendingProjectOpenPath ?: return
		if (!guardProjectTransition()) {
			logger.debug("OS file-open event cancelled while resolving current project state")
			return
		}
		openProjectAt(path)
	}

	fun requestOpenProject() {
		scope.launch(handler) {
			logger.debug("User requested open project")
			if (!guardProjectTransition()) {
				logger.debug("Open project cancelled while resolving current project state")
				return@launch
			}
			val path = chooseOpenProjectPath(window, "Open .moqproj", appState.lastFileDirectory.value)
			if (path == null) {
				logger.debug("Open project dialog cancelled")
				return@launch
			}
			openProjectAt(path)
		}
	}

	fun requestOpenRecentProject(path: String) {
		scope.launch(handler) {
			logger.debug("User requested open recent project: {}", path)
			if (!guardProjectTransition()) {
				logger.debug("Open recent project cancelled while resolving current project state")
				return@launch
			}
			openProjectAt(path)
		}
	}

	fun removeRecentProject(path: String) {
		scope.launch(handler) {
			logger.debug("Removing recent project: {}", path)
			viewModel.removeRecentProject(path)
			runOnIo(services.ioDispatcher) {
				services.recentProjectsRepo.save(viewModel.state.value.recentProjects)
			}
		}
	}

	fun requestCloseProject() {
		scope.launch(handler) {
			logger.debug("User requested close project")
			if (viewModel.state.value.project == null) return@launch
			if (!guardProjectTransition()) {
				logger.debug("Close project cancelled while resolving unsaved changes")
				return@launch
			}
			viewModel.projectClosed()
			logger.info("Project closed")
		}
	}

	fun requestReloadProject() {
		val project = viewModel.state.value.project ?: return
		if (!guardProjectTransition()) return
		scope.launch(handler) { openProjectAt(project.projectPath) }
	}

	/** Saves in place, or asks for a location when the project has never been saved. */
	fun requestSave() {
		val project = viewModel.state.value.project ?: return
		scope.launchReporting(handler, "Failed to save project", viewModel::setError) {
			if (project.projectPath.isBlank()) {
				saveAs(project)
			} else {
				logger.info("Saving project '{}' to: {}", project.manifest.name, project.projectPath)
				persist(project, project.projectPath)
			}
		}
	}

	fun requestSaveAs() {
		val project = viewModel.state.value.project ?: return
		scope.launchReporting(handler, "Failed to save project", viewModel::setError) { saveAs(project) }
	}

	/** Writes the client-code references chosen in the export window, then closes it. */
	fun requestExport() {
		val project = viewModel.state.value.project ?: return
		val request = appState.exportState
		val destination = request.destinationFolder ?: return
		scope.launchReporting(handler, "Failed to export references", viewModel::setError) {
			val options = ExportOptions(
				languages = request.selectedLanguages,
				includeApiDescriptions = request.includeApiDescriptions,
				includeVariantDescriptions = request.includeVariantDescriptions,
				kotlinPackage = request.kotlinPackage.ifBlank { null },
				javaPackage = request.javaPackage.ifBlank { null },
			)
			val files = ExportRegistry.generate(ExportCatalogBuilder.build(project), options)
			runOnIo(services.ioDispatcher) {
				val directory = File(destination)
				directory.mkdirs()
				files.forEach { generated -> File(directory, generated.fileName).writeText(generated.content) }
			}
			logger.info("Exported {} file(s) to {}", files.size, destination)
			appState.showExport = false
		}
	}

	fun requestExportDestination() {
		val folder = chooseDirectory(window, "Export Destination", appState.lastFileDirectory.value) ?: return
		appState.exportState = appState.exportState.copy(destinationFolder = folder)
		appState.lastFileDirectory.value = folder
	}

	suspend fun persist(project: MoqProject, path: String) {
		persistProject(
			project,
			path,
			services.repo,
			viewModel,
			services.recentProjectsRepo,
			services.ioDispatcher,
		)
	}

	private suspend fun saveAs(project: MoqProject) {
		logger.debug("User requested Save As for project '{}'", project.manifest.name)
		val path = chooseProjectDirectory(
			parent = window,
			title = "Save Project As",
			initialDirectory = appState.lastFileDirectory.value,
			projectName = project.manifest.name,
		)
		if (path == null) {
			logger.debug("Save As dialog cancelled")
			return
		}
		logger.info("Saving project '{}' as: {}", project.manifest.name, path)
		persist(project, path)
		appState.lastFileDirectory.value = File(path).parentFile?.canonicalPath ?: path
	}

	private suspend fun openProjectAt(path: String) {
		openProject(
			rawPath = path,
			repo = services.repo,
			appViewModel = viewModel,
			lastFileDirectory = appState.lastFileDirectory,
			recentProjectsRepo = services.recentProjectsRepo,
			ioDispatcher = services.ioDispatcher,
		)
	}
}
