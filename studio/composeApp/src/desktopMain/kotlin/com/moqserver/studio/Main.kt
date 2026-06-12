package com.moqserver.studio

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.rememberWindowState
import com.moqserver.studio.data.AISettingsRepository
import com.moqserver.studio.data.ImportHistoryRepository
import com.moqserver.studio.data.RecentProjectsRepository
import com.moqserver.studio.data.ThemePreference
import com.moqserver.studio.domain.EndpointUpdateStatus
import com.moqserver.studio.domain.ImportConverter
import com.moqserver.studio.domain.ImportMode
import com.moqserver.studio.domain.ImportSourceType
import com.moqserver.studio.domain.StudioRootViewModel
import com.moqserver.studio.export.ExportCatalogBuilder
import com.moqserver.studio.export.ExportOptions
import com.moqserver.studio.export.ExportRegistry
import com.moqserver.studio.imports.HARImportParser
import com.moqserver.studio.imports.OpenAPIImportParser
import com.moqserver.studio.imports.OpenAPIURLFetcher
import com.moqserver.studio.imports.URLFetchException
import com.moqserver.studio.imports.URLImportAuth
import com.moqserver.studio.logging.loggerFor
import com.moqserver.studio.projectformat.ProjectRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JOptionPane

private val logger = loggerFor<Any>()

private enum class SpecFileImportMode {
	OPENAPI,
	SWAGGER,
}

private fun ThemePreference.toStudioThemeMode(): StudioThemeMode = when (this) {
    ThemePreference.SYSTEM -> StudioThemeMode.SYSTEM
    ThemePreference.LIGHT -> StudioThemeMode.LIGHT
    ThemePreference.DARK -> StudioThemeMode.DARK
}

private fun StudioThemeMode.toThemePreference(): ThemePreference = when (this) {
    StudioThemeMode.SYSTEM -> ThemePreference.SYSTEM
    StudioThemeMode.LIGHT -> ThemePreference.LIGHT
    StudioThemeMode.DARK -> ThemePreference.DARK
}

private fun isUndoShortcut(event: androidx.compose.ui.input.key.KeyEvent, isMac: Boolean): Boolean {
    if (event.type != KeyEventType.KeyDown || event.key != Key.Z || event.isShiftPressed) return false
    return if (isMac) event.isMetaPressed else event.isCtrlPressed
}

private fun isRedoShortcut(event: androidx.compose.ui.input.key.KeyEvent, isMac: Boolean): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return if (isMac) {
        event.isMetaPressed && event.isShiftPressed && event.key == Key.Z
    } else {
        event.isCtrlPressed && event.key == Key.Y
    }
}

private fun isPreferencesShortcut(event: androidx.compose.ui.input.key.KeyEvent, isMac: Boolean): Boolean {
    if (!isMac || event.type != KeyEventType.KeyDown) return false
    return event.isMetaPressed && event.key == Key.Comma
}

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

internal fun buildURLImportAuth(state: ImportFromURLState): URLImportAuth? = when (state.authType) {
	URLAuthType.BEARER -> URLImportAuth.Bearer(state.bearerToken)
	URLAuthType.BASIC -> URLImportAuth.Basic(state.basicUsername, state.basicPassword)
	URLAuthType.NONE -> null
}

private fun applyURLImportResult(
	state: ImportFromURLState,
	executionPlan: URLImportExecutionPlan,
	currentProject: com.moqserver.studio.projectformat.MoqProject?,
	fetched: com.moqserver.studio.imports.FetchedSpec,
	spec: com.moqserver.studio.domain.ParsedSpec,
	appViewModel: StudioRootViewModel,
	previouslyDeselectedIds: Set<String>,
): Boolean {
	if (executionPlan.requiresProject) {
		if (currentProject == null) {
			return false
		}
		appViewModel.startUpdateFromSpec(
			spec = spec,
			source = ImportSourceType.OPENAPI,
			fileName = fetched.sourceName,
			previouslyDeselectedIds = previouslyDeselectedIds,
		)
	} else {
		appViewModel.startImport(spec, ImportSourceType.OPENAPI, fetched.sourceName)
	}
	logger.info(
		"URL {} started: {} endpoints from {}",
		if (state.action == URLImportAction.UPDATE) "update" else "import",
		spec.endpoints.size,
		fetched.resolvedUrl,
	)
	return true
}

private suspend fun performURLImport(
	state: ImportFromURLState,
	currentProject: com.moqserver.studio.projectformat.MoqProject?,
	urlFetcher: OpenAPIURLFetcher,
	openApiParser: OpenAPIImportParser,
	importHistoryRepo: ImportHistoryRepository,
	appViewModel: StudioRootViewModel,
): Boolean {
	val executionPlan = planURLImportExecution(state)
	logger.info(
		"{} {} spec from URL: {}",
		executionPlan.operationLabel,
		executionPlan.modeLabel,
		state.url,
	)
	val auth = buildURLImportAuth(state)
	val fetched = withContext(Dispatchers.IO) {
		urlFetcher.fetchSpec(state.url, auth)
	}
	logger.info(
		"Fetched spec from {} ({} chars), parsing...",
		fetched.resolvedUrl,
		fetched.content.length,
	)
	val spec = withContext(Dispatchers.IO) { openApiParser.parse(fetched.content) }
	val previouslyDeselectedIds = if (executionPlan.requiresProject && currentProject != null) {
		withContext(Dispatchers.IO) {
			importHistoryRepo.loadDeselected(currentProject.projectPath)
		}
	} else {
		emptySet()
	}
	return applyURLImportResult(
		state = state,
		executionPlan = executionPlan,
		currentProject = currentProject,
		fetched = fetched,
		spec = spec,
		appViewModel = appViewModel,
		previouslyDeselectedIds = previouslyDeselectedIds,
	)
}

fun main(args: Array<String>) {
    System.setProperty("apple.awt.application.name", STUDIO_APP_DISPLAY_NAME)
    logger.info("moqserver studio starting (args={})", args.toList())
    installCrashHandlers()
    application {
        val repo = remember { ProjectRepository() }
        val openApiParser = remember { OpenAPIImportParser() }
        val harParser = remember { HARImportParser() }
        val settingsRepo = remember { AISettingsRepository() }
        val recentProjectsRepo = remember { RecentProjectsRepository() }
        val importHistoryRepo = remember { ImportHistoryRepository() }
        val aiSettings = remember { mutableStateOf(settingsRepo.load()) }
        val aiRegistry = remember(aiSettings.value) { buildAIRegistry(aiSettings.value) }
		val showSettings = remember { mutableStateOf(false) }
		val showExport = remember { mutableStateOf(false) }
		val exportState = remember { mutableStateOf(ExportReferencesState()) }
		val showImportURLDialog = remember { mutableStateOf(false) }
		val importURLState = remember { mutableStateOf(ImportFromURLState()) }
		val urlFetcher = remember { OpenAPIURLFetcher() }
        val appViewModel = remember { StudioRootViewModel() }
        val scope = rememberCoroutineScope()
        val themeMode = remember { mutableStateOf(aiSettings.value.themeMode.toStudioThemeMode()) }
        val lastFileDirectory = remember { mutableStateOf<String?>(null) }
        val pendingProjectOpenPath = remember { mutableStateOf(resolveInitialProjectPath(args)) }
        val isMac = isMacOs()
        val exceptionHandler = remember {
            CoroutineExceptionHandler { _, throwable ->
                if (isFailFastEnabled()) {
                    throw propagateFailure("Unhandled coroutine exception", throwable)
                }
                reportFatal("Unhandled coroutine exception", throwable)
            }
        }
        val state by appViewModel.state.collectAsState()

        LaunchedEffect(aiSettings.value.selectedProviderId) {
            aiSettings.value.selectedProviderId?.let(appViewModel::selectProvider)
        }

        LaunchedEffect(Unit) {
            appViewModel.setRecentProjects(recentProjectsRepo.load())
        }

        LaunchedEffect(aiRegistry) {
            refreshAIProviders(aiRegistry, appViewModel, Dispatchers.IO)
        }

        val windowState = rememberWindowState(
            width = 1400.dp,
            height = 900.dp,
            position = WindowPosition.Aligned(Alignment.Center),
        )

        Window(
            onCloseRequest = {
                when (resolveWindowCloseAction(state)) {
                    WindowCloseAction.CLOSE_PROJECT -> {
                        logger.debug("Window close requested: closing current project (isDirty={})", state.isDirty)
                        if (
                            confirmProjectTransition(
                                owner = null,
                                state = state,
                            )
                        ) {
                            appViewModel.projectClosed()
                            logger.info("Project closed from window close request")
                        }
                    }

                    WindowCloseAction.EXIT_APPLICATION -> {
                        logger.info("Window close requested from landing screen: exiting application")
                        exitApplication()
                    }
                }
            },
            title = STUDIO_APP_DISPLAY_NAME,
            state = windowState,
            onPreviewKeyEvent = { event ->
                when {
                    isUndoShortcut(event, isMac) && state.canUndo -> {
                        appViewModel.undo()
                        true
                    }

                    isRedoShortcut(event, isMac) && state.canRedo -> {
                        appViewModel.redo()
                        true
                    }

                    isPreferencesShortcut(event, isMac) -> {
                        showSettings.value = true
                        true
                    }

                    else -> false
                }
            },
        ) {
            window.title = state.windowTitle

            LaunchedEffect(window) {
                installAppIcon(window)
                installAboutHandler { showAboutDialog(window) }
                installPreferencesHandler { showSettings.value = true }
                installProjectOpenHandler { incomingPath ->
                    logger.info("OS file-open event: {}", incomingPath)
                    pendingProjectOpenPath.value = incomingPath
                }
            }

            LaunchedEffect(state.project) {
                if (state.project != null) {
                    windowState.placement = WindowPlacement.Maximized
                }
            }

            fun guardProjectTransition(): Boolean =
                confirmProjectTransition(
                    owner = window,
                    state = appViewModel.state.value,
                )

            LaunchedEffect(pendingProjectOpenPath.value) {
                val path = pendingProjectOpenPath.value ?: return@LaunchedEffect
                pendingProjectOpenPath.value = null
                if (!guardProjectTransition()) {
                    logger.debug("OS file-open event cancelled while resolving current project state")
                    return@LaunchedEffect
                }
                openProject(
                    rawPath = path,
                    repo = repo,
                    appViewModel = appViewModel,
                    lastFileDirectory = lastFileDirectory,
                    recentProjectsRepo = recentProjectsRepo,
                    ioDispatcher = Dispatchers.IO,
                )
            }

            fun requestOpenProject() {
                scope.launch(exceptionHandler) {
                    logger.debug("User requested open project")
                    if (!guardProjectTransition()) {
                        logger.debug("Open project cancelled while resolving current project state")
                        return@launch
                    }
                    val path = chooseOpenProjectPath(window, "Open .moqproj", lastFileDirectory.value)
                        ?: run { logger.debug("Open project dialog cancelled"); return@launch }
                    openProject(
                        path,
                        repo,
                        appViewModel,
                        lastFileDirectory,
                        recentProjectsRepo,
                        Dispatchers.IO,
                    )
                }
            }

            fun requestOpenRecentProject(path: String) {
                scope.launch(exceptionHandler) {
                    logger.debug("User requested open recent project: {}", path)
                    if (!guardProjectTransition()) {
                        logger.debug("Open recent project cancelled while resolving current project state")
                        return@launch
                    }
                    openProject(
                        rawPath = path,
                        repo = repo,
                        appViewModel = appViewModel,
                        lastFileDirectory = lastFileDirectory,
                        recentProjectsRepo = recentProjectsRepo,
                        ioDispatcher = Dispatchers.IO,
                    )
                }
            }

            fun removeRecentProject(path: String) {
                scope.launch(exceptionHandler) {
                    logger.debug("Removing recent project: {}", path)
                    appViewModel.removeRecentProject(path)
                    withContext(Dispatchers.IO) {
                        recentProjectsRepo.save(appViewModel.state.value.recentProjects)
                    }
                }
            }

            fun requestCloseProject() {
                scope.launch(exceptionHandler) {
                    logger.debug("User requested close project")
                    val currentState = appViewModel.state.value
                    if (currentState.project == null) return@launch
                    if (!guardProjectTransition()) {
                        logger.debug("Close project cancelled while resolving unsaved changes")
                        return@launch
                    }
                    appViewModel.projectClosed()
                    logger.info("Project closed")
                }
            }

			fun requestImportSpecFile(mode: SpecFileImportMode) {
				scope.launch(exceptionHandler) {
					logger.debug("User requested import {} spec", mode.name.lowercase())
					if (!guardProjectTransition()) {
						logger.debug("Import {} cancelled while resolving current project state", mode.name.lowercase())
						return@launch
					}
					val file = chooseFile(
						window,
						if (mode == SpecFileImportMode.SWAGGER) "Import Swagger Spec" else "Import OpenAPI Spec",
						setOf("yaml", "yml", "json"),
						lastFileDirectory.value,
					) ?: run { logger.debug("Import OpenAPI dialog cancelled"); return@launch }
					logger.info("Importing {} spec from: {}", mode.name.lowercase(), file.absolutePath)
					try {
						val content = withContext(Dispatchers.IO) { file.readText() }
						val spec = withContext(Dispatchers.IO) { openApiParser.parse(content) }
						appViewModel.startImport(spec, ImportSourceType.OPENAPI, file.name)
						lastFileDirectory.value = file.parentFile?.canonicalPath ?: lastFileDirectory.value
					} catch (e: Exception) {
						reportRecoverable(
							context = if (mode == SpecFileImportMode.SWAGGER) {
								"Failed to parse Swagger spec"
							} else {
								"Failed to parse OpenAPI spec"
							},
							throwable = e,
							onUserMessage = appViewModel::setError,
						)
					}
				}
			}

			fun requestImportOpenAPI() = requestImportSpecFile(SpecFileImportMode.OPENAPI)

			fun requestImportSwagger() = requestImportSpecFile(SpecFileImportMode.SWAGGER)

            fun requestImportHAR() {
                scope.launch(exceptionHandler) {
                    logger.debug("User requested import HAR file")
                    if (!guardProjectTransition()) {
                        logger.debug("Import HAR cancelled while resolving current project state")
                        return@launch
                    }
                    val file = chooseFile(
                        window,
                        "Import HAR File",
                        setOf("har", "json"),
                        lastFileDirectory.value,
                    ) ?: run { logger.debug("Import HAR dialog cancelled"); return@launch }
                    logger.info("Importing HAR file from: {}", file.absolutePath)
                    try {
                        val content = withContext(Dispatchers.IO) { file.readText() }
                        val spec = withContext(Dispatchers.IO) { harParser.parse(content) }
                        appViewModel.startImport(spec, ImportSourceType.HAR, file.name)
                        lastFileDirectory.value = file.parentFile?.canonicalPath ?: lastFileDirectory.value
                    } catch (e: Exception) {
                        reportRecoverable(
                            context = "Failed to parse HAR file",
                            throwable = e,
                            onUserMessage = appViewModel::setError,
                        )
                    }
                }
            }

			fun requestImportFromURL(mode: URLImportMode, action: URLImportAction) {
				if (action == URLImportAction.IMPORT && !guardProjectTransition()) {
					logger.debug("Import from URL cancelled while resolving current project state")
					return
				}
				if (action == URLImportAction.UPDATE && appViewModel.state.value.project == null) return
				importURLState.value = ImportFromURLState(mode = mode, action = action)
				showImportURLDialog.value = true
			}

			fun requestUpdateFromSpecFile(mode: SpecFileImportMode) {
				val currentProject = appViewModel.state.value.project ?: return
				scope.launch(exceptionHandler) {
					logger.debug("User requested update from {} spec", mode.name.lowercase())
					val file = chooseFile(
						window,
						if (mode == SpecFileImportMode.SWAGGER) "Update from Swagger Spec" else "Update from OpenAPI Spec",
						setOf("yaml", "yml", "json"),
						lastFileDirectory.value,
					) ?: run { logger.debug("Update from spec dialog cancelled"); return@launch }
					logger.info(
						"Updating project '{}' from {} spec: {}",
						currentProject.manifest.name,
						mode.name.lowercase(),
						file.absolutePath,
					)
					try {
						val content = withContext(Dispatchers.IO) { file.readText() }
						val spec = withContext(Dispatchers.IO) { openApiParser.parse(content) }
						val previouslyDeselected = withContext(Dispatchers.IO) {
							importHistoryRepo.loadDeselected(currentProject.projectPath)
						}
						appViewModel.startUpdateFromSpec(
							spec = spec,
							source = ImportSourceType.OPENAPI,
							fileName = file.name,
							previouslyDeselectedIds = previouslyDeselected,
						)
						lastFileDirectory.value = file.parentFile?.canonicalPath ?: lastFileDirectory.value
					} catch (e: Exception) {
						reportRecoverable(
							context = if (mode == SpecFileImportMode.SWAGGER) {
								"Failed to parse Swagger spec"
							} else {
								"Failed to parse OpenAPI spec"
							},
							throwable = e,
							onUserMessage = appViewModel::setError,
						)
					}
				}
			}

			fun requestUpdateFromHAR() {
				val currentProject = appViewModel.state.value.project ?: return
				scope.launch(exceptionHandler) {
					logger.debug("User requested update from HAR file")
					val file = chooseFile(
						window,
						"Update from HAR File",
						setOf("har", "json"),
						lastFileDirectory.value,
					) ?: run { logger.debug("Update from HAR dialog cancelled"); return@launch }
					logger.info("Updating project '{}' from HAR: {}", currentProject.manifest.name, file.absolutePath)
					try {
						val content = withContext(Dispatchers.IO) { file.readText() }
						val spec = withContext(Dispatchers.IO) { harParser.parse(content) }
						val previouslyDeselected = withContext(Dispatchers.IO) {
							importHistoryRepo.loadDeselected(currentProject.projectPath)
						}
						appViewModel.startUpdateFromSpec(
							spec = spec,
							source = ImportSourceType.HAR,
							fileName = file.name,
							previouslyDeselectedIds = previouslyDeselected,
						)
						lastFileDirectory.value = file.parentFile?.canonicalPath ?: lastFileDirectory.value
					} catch (e: Exception) {
						reportRecoverable(
							context = "Failed to parse HAR file",
							throwable = e,
							onUserMessage = appViewModel::setError,
						)
					}
				}
			}

			fun requestImportOpenAPIURL() = requestImportFromURL(URLImportMode.OPENAPI, URLImportAction.IMPORT)

			fun requestImportSwaggerURL() = requestImportFromURL(URLImportMode.SWAGGER, URLImportAction.IMPORT)

			fun requestUpdateOpenAPIURL() = requestImportFromURL(URLImportMode.OPENAPI, URLImportAction.UPDATE)

			fun requestUpdateSwaggerURL() = requestImportFromURL(URLImportMode.SWAGGER, URLImportAction.UPDATE)

            fun executeURLImport() {
                val currentState = importURLState.value
                if (currentState.url.isBlank() || currentState.loading) return
                importURLState.value = currentState.copy(loading = true, error = null)
                scope.launch(exceptionHandler) {
					try {
						val currentProject = appViewModel.state.value.project
						val applied = performURLImport(
							state = currentState,
							currentProject = currentProject,
							urlFetcher = urlFetcher,
							openApiParser = openApiParser,
							importHistoryRepo = importHistoryRepo,
							appViewModel = appViewModel,
						)
						if (!applied) {
							importURLState.value = importURLState.value.copy(
								loading = false,
								error = "Open a project before updating from URL.",
							)
							return@launch
						}
                        showImportURLDialog.value = false
                        importURLState.value = ImportFromURLState()
                    } catch (e: URLFetchException) {
                        logger.error("URL fetch failed: {}", e.message)
                        importURLState.value = importURLState.value.copy(
                            loading = false,
                            error = e.message,
                        )
                    } catch (e: Exception) {
                        logger.error("Failed to import from URL: {}", e.message)
                        importURLState.value = importURLState.value.copy(
                            loading = false,
								error = "Successfully fetched content from the URL, but it couldn't be parsed " +
									"as a valid API specification: ${e.message ?: "Unknown error"}",
							)
						}
					}
				}

            suspend fun saveProject(project: com.moqserver.studio.projectformat.MoqProject) {
                logger.info("Saving project '{}' to: {}", project.manifest.name, project.projectPath)
                persistProject(project, project.projectPath, repo, appViewModel, recentProjectsRepo, Dispatchers.IO)
            }

            suspend fun saveProjectAs(project: com.moqserver.studio.projectformat.MoqProject) {
                logger.debug("User requested Save As for project '{}'", project.manifest.name)
                val path = chooseProjectDirectory(
                    parent = window,
                    title = "Save Project As",
                    initialDirectory = lastFileDirectory.value,
                    projectName = project.manifest.name,
                ) ?: run {
                    logger.debug("Save As dialog cancelled")
                    return
                }
                logger.info("Saving project '{}' as: {}", project.manifest.name, path)
                persistProject(project, path, repo, appViewModel, recentProjectsRepo, Dispatchers.IO)
                lastFileDirectory.value = File(path).parentFile?.canonicalPath ?: path
            }

            val saveShortcut = if (isMac) {
                KeyShortcut(key = Key.S, meta = true)
            } else {
                KeyShortcut(key = Key.S, ctrl = true)
            }
            val saveAsShortcut = if (isMac) {
                KeyShortcut(key = Key.S, meta = true, alt = true)
            } else {
                KeyShortcut(key = Key.S, ctrl = true, alt = true)
            }
            val preferencesShortcut = if (isMac) {
                KeyShortcut(key = Key.Comma, meta = true)
            } else {
                null
            }
            val showPreferencesInMenuBar = !isMac
			val undoShortcut = if (isMac) {
				KeyShortcut(key = Key.Z, meta = true)
			} else {
				KeyShortcut(key = Key.Z, ctrl = true)
			}
			val redoShortcut = if (isMac) {
				KeyShortcut(key = Key.Z, meta = true, shift = true)
			} else {
				KeyShortcut(key = Key.Y, ctrl = true)
			}

            MenuBar {
                Menu("File") {
                    Item("Open Project", onClick = ::requestOpenProject)
                    Item(
                        "Save",
                        enabled = state.project != null && state.isDirty && !state.hasErrors,
                        shortcut = saveShortcut,
                        onClick = {
                            val project = state.project ?: return@Item
                            scope.launch(exceptionHandler) {
                                try {
                                    if (project.projectPath.isBlank()) {
                                        saveProjectAs(project)
                                    } else {
                                        saveProject(project)
                                    }
                                } catch (e: Exception) {
                                    reportRecoverable(
                                        context = "Failed to save project",
                                        throwable = e,
                                        onUserMessage = appViewModel::setError,
                                    )
                                }
                            }
                        },
                    )
                    Item(
                        "Save As",
                        enabled = state.project != null,
                        shortcut = saveAsShortcut,
                        onClick = {
                            val project = state.project ?: return@Item
                            scope.launch(exceptionHandler) {
                                try {
                                    saveProjectAs(project)
                                } catch (e: Exception) {
                                    reportRecoverable(
                                        context = "Failed to save project",
                                        throwable = e,
                                        onUserMessage = appViewModel::setError,
                                    )
                                }
                            }
                        },
                    )
                    Separator()
                    Item("Close Project", enabled = state.project != null, onClick = ::requestCloseProject)
                    if (state.recentProjects.isNotEmpty()) {
                        Separator()
                        Menu("Recent Projects") {
                            state.recentProjects.forEach { path ->
                                Item(recentProjectLabel(path), onClick = { requestOpenRecentProject(path) })
                            }
                        }
                    }
					Separator()
					Menu("Update", enabled = state.project != null) {
						Menu("From File") {
							Item(
								"OpenAPI",
								enabled = state.project != null,
								onClick = { requestUpdateFromSpecFile(SpecFileImportMode.OPENAPI) },
							)
							Item(
								"Swagger",
								enabled = state.project != null,
								onClick = { requestUpdateFromSpecFile(SpecFileImportMode.SWAGGER) },
							)
							Item(
								"HAR",
								enabled = state.project != null,
								onClick = ::requestUpdateFromHAR,
							)
						}
						Menu("From URL") {
							Item(
								"OpenAPI",
								enabled = state.project != null,
								onClick = ::requestUpdateOpenAPIURL,
							)
							Item(
								"Swagger",
								enabled = state.project != null,
								onClick = ::requestUpdateSwaggerURL,
							)
						}
					}
					Separator()
					Item(
                        "Generate Client Code...",
                        enabled = state.project != null,
                        onClick = {
                            exportState.value = ExportReferencesState()
                            showExport.value = true
                        },
                    )
                }
                Menu("Edit") {
                    Item(
                        "Undo",
                        enabled = state.canUndo,
                        shortcut = undoShortcut,
                        onClick = { appViewModel.undo() },
                    )
                    Item(
                        "Redo",
                        enabled = state.canRedo,
                        shortcut = redoShortcut,
                        onClick = { appViewModel.redo() },
                    )
                    if (showPreferencesInMenuBar) {
                        Separator()
                        Item(
                            "Preferences...",
                            shortcut = preferencesShortcut,
                            onClick = { showSettings.value = true },
                        )
                    }
                }
                Menu("Help") {
                    Item("About $STUDIO_APP_DISPLAY_NAME", onClick = { showAboutDialog(window) })
                }
            }

            StudioTheme(themeMode = themeMode.value) {
					App(
						appViewModel = appViewModel,
						onOpenProject = { requestOpenProject() },
						onImportOpenAPI = { requestImportOpenAPI() },
						onImportSwagger = { requestImportSwagger() },
						onImportOpenAPIURL = { requestImportOpenAPIURL() },
						onImportSwaggerURL = { requestImportSwaggerURL() },
						onImportHAR = { requestImportHAR() },
						onOpenRecentProject = { path -> requestOpenRecentProject(path) },
						onRemoveRecentProject = { path -> removeRecentProject(path) },
						onAIAction = { action ->
                        scope.launch(exceptionHandler) {
                            executeAIAction(action, aiRegistry, appViewModel, Dispatchers.IO)
                        }
                    },
                    onGenerateBody = { endpointId, variantReferenceName, prompt ->
                        scope.launch(exceptionHandler) {
                            generateBodyForVariant(
                                endpointId = endpointId,
                                variantReferenceName = variantReferenceName,
                                prompt = prompt,
                                registry = aiRegistry,
                                viewModel = appViewModel,
                                ioDispatcher = Dispatchers.IO,
                            )
                        }
                    },
					variantReferenceSyncPreference = state.project?.projectPath?.let {
						aiSettings.value.variantReferenceSyncByProject[it]
					},
                    onVariantReferenceSyncPreferenceChange = { projectPath, preference ->
                        scope.launch(exceptionHandler) {
                            val updatedPreferences = aiSettings.value.variantReferenceSyncByProject.toMutableMap().also { preferences ->
                                if (preference == null) {
                                    preferences.remove(projectPath)
                                } else {
                                    preferences[projectPath] = preference
                                }
                            }
                            val settingsToSave = aiSettings.value.copy(
                                variantReferenceSyncByProject = updatedPreferences,
                            )
                            try {
                                withContext(Dispatchers.IO) { settingsRepo.save(settingsToSave) }
                            } catch (e: Exception) {
                                logger.error("Failed to save variant reference preference: {}", e.message)
                                JOptionPane.showMessageDialog(
                                    window,
                                    e.message ?: "Failed to save preferences.",
                                    "Preferences",
                                    JOptionPane.ERROR_MESSAGE,
                                )
                                return@launch
                            }
                            aiSettings.value = settingsToSave
                        }
                    },
                )

            if (showImportURLDialog.value) {
                ImportFromURLDialog(
                        state = importURLState.value,
                        onUrlChange = { importURLState.value = importURLState.value.copy(url = it, error = null) },
                        onAuthTypeChange = { importURLState.value = importURLState.value.copy(authType = it) },
                        onBearerTokenChange = { importURLState.value = importURLState.value.copy(bearerToken = it) },
                        onBasicUsernameChange = {
                            importURLState.value = importURLState.value.copy(basicUsername = it)
                        },
                        onBasicPasswordChange = {
                            importURLState.value = importURLState.value.copy(basicPassword = it)
                        },
                        onConfirm = ::executeURLImport,
                        onDismiss = {
                            showImportURLDialog.value = false
                            importURLState.value = ImportFromURLState()
                        },
                    )
                }

				val importState = state.importState
				if (importState != null) {
					DialogWindow(
						onCloseRequest = { appViewModel.cancelImport() },
						title = if (importState.isUpdateMode) "Update Review" else "Import Review",
						state = rememberDialogState(width = 1120.dp, height = 820.dp),
						resizable = true,
					) {
						window.isModal = true
						StudioTheme(themeMode = themeMode.value) {
							Surface(modifier = Modifier.fillMaxSize()) {
								ImportReviewScreen(
									state = importState,
									aiProviders = state.ai.providers,
									aiProvidersLoading = state.ai.loading,
									canGenerateWithAi = state.ai.selectedProvider
										?.takeIf { it.available }
										?.capabilities
										?.contains("GENERATE_VARIANTS")
										== true,
									selectedAIProviderId = state.ai.selectedProviderId,
									aiProviderLabel = state.ai.selectedProvider?.displayName,
									onRefreshAIProviders = {
										scope.launch(exceptionHandler) {
											refreshAIProviders(aiRegistry, appViewModel, Dispatchers.IO)
										}
									},
									onSelectAIProvider = { providerId -> appViewModel.selectProvider(providerId) },
									onToggleEndpoint = { index -> appViewModel.toggleImportEndpoint(index) },
									onSetGroupAccepted = { indices, accepted ->
										val entries = importState.entries
										indices.forEach { index ->
											if (entries[index].accepted != accepted) {
												appViewModel.toggleImportEndpoint(index)
											}
										}
									},
									onSelectAll = { appViewModel.setAllImportEndpoints(true) },
									onDeselectAll = { appViewModel.setAllImportEndpoints(false) },
									onUpdateProjectName = { appViewModel.updateImportProjectName(it) },
									onUpdateSelection = { appViewModel.updateImportSelection(it) },
								onUpdateResponseName = { entryIndex, responseIndex, name ->
									appViewModel.updateImportResponseName(entryIndex, responseIndex, name)
								},
								onUpdateGeneratedResponseName = { entryIndex, responseIndex, name ->
									appViewModel.updateGeneratedImportResponseName(entryIndex, responseIndex, name)
								},
								onToggleGeneratedResponse = { entryIndex, responseIndex ->
									appViewModel.toggleGeneratedImportResponse(entryIndex, responseIndex)
								},
								onUpdateEndpointAIContextHint = { index, hint ->
									appViewModel.updateImportAIContextHint(index, hint)
								},
									onGenerateEndpointMocks = { index ->
										scope.launch(exceptionHandler) {
											generateImportMocksForEndpoint(
												index = index,
												registry = aiRegistry,
												viewModel = appViewModel,
												ioDispatcher = Dispatchers.IO,
											)
										}
									},
									onGenerateAllMocks = {
										scope.launch(exceptionHandler) {
											generateImportMocksForAcceptedEndpoints(
												registry = aiRegistry,
												viewModel = appViewModel,
												ioDispatcher = Dispatchers.IO,
											)
										}
									},
									onConfirm = {
									scope.launch(exceptionHandler) {
										val currentImportState = appViewModel.state.value.importState ?: return@launch
										logger.debug(
											"User confirming import: source={}, accepted={}/{}",
											currentImportState.sourceFileName,
											currentImportState.entries.count { it.accepted },
											currentImportState.entries.size,
										)

										val isUpdateMode = currentImportState.isUpdateMode
										val existingProjectPath = (currentImportState.mode as? ImportMode.UpdateExisting)
											?.existingProject?.projectPath

									val persistImportedProject = shouldPersistImportedProjectImmediately(isUpdateMode)

									if (isUpdateMode) {
										val deselectedIds = currentImportState.entries
											.filter {
												!it.accepted &&
													it.updateStatus != EndpointUpdateStatus.UNCHANGED
											}
												.map {
													ImportConverter.endpointId(it.endpoint.method, it.endpoint.path)
												}
												.toSet()
										val project = appViewModel.confirmImport(
											existingProjectPath ?: currentImportState.parsedSpec.title,
										) ?: return@launch
										val projectPath = project.projectPath
										withContext(Dispatchers.IO) {
											importHistoryRepo.saveDeselected(projectPath, deselectedIds)
										}
										if (persistImportedProject) {
											persistProject(project, projectPath, repo, appViewModel, recentProjectsRepo, Dispatchers.IO)
										}
										logger.info(
											"Update import applied: {} endpoint(s) staged in {}",
											project.endpoints.size,
											projectPath,
										)
									} else {
											val path = chooseProjectDirectory(
												parent = window,
												title = "Import Project",
												initialDirectory = lastFileDirectory.value,
												projectName = currentImportState.projectName,
											)
												?: run { logger.debug("Import save dialog cancelled"); return@launch }
											logger.info("Saving imported project '{}' to: {}", currentImportState.projectName, path)
											try {
												val project = appViewModel.confirmImport(path) ?: return@launch
												if (persistImportedProject) {
													persistProject(project, path, repo, appViewModel, recentProjectsRepo, Dispatchers.IO)
												}
												lastFileDirectory.value = File(path).parentFile?.canonicalPath ?: path
												logger.info(
													"Import complete: {} endpoint(s) saved to {}",
													project.endpoints.size,
													path,
												)
											} catch (e: Exception) {
												reportRecoverable(
													context = "Failed to save imported project",
													throwable = e,
													onUserMessage = appViewModel::setError,
												)
											}
										}
									}
									},
									onCancel = { appViewModel.cancelImport() },
									modifier = Modifier.fillMaxSize(),
								)
							}
						}
					}
				}
            }
        }

        if (showSettings.value) {
            Window(
                onCloseRequest = { showSettings.value = false },
                title = "Preferences",
                state = rememberWindowState(width = 900.dp, height = 680.dp),
            ) {
                StudioTheme(themeMode = themeMode.value) {
                    PreferencesScreen(
                        state = PreferencesState(
                            themeMode = themeMode.value,
                            aiSettings = aiSettings.value,
                        ),
                        onThemeModeChange = { updatedThemeMode ->
                            scope.launch(exceptionHandler) {
                                val settingsToSave = aiSettings.value.copy(
                                    themeMode = updatedThemeMode.toThemePreference(),
                                )
                                try {
                                    withContext(Dispatchers.IO) { settingsRepo.save(settingsToSave) }
                                } catch (e: Exception) {
                                    logger.error("Failed to save preferences: {}", e.message)
                                    JOptionPane.showMessageDialog(
                                        window,
                                        e.message ?: "Failed to save preferences.",
                                        "Preferences",
                                        JOptionPane.ERROR_MESSAGE,
                                    )
                                    return@launch
                                }
                                logger.info("Theme preference saved")
                                aiSettings.value = settingsToSave
                                themeMode.value = updatedThemeMode
                                refreshAIProviders(buildAIRegistry(settingsToSave), appViewModel, Dispatchers.IO)
                            }
                        },
                        onTestAIProvider = { providerId, testSettings, onStatus ->
                            scope.launch(exceptionHandler) {
                                onStatus(ProviderConnectionStatus.Checking)
                                val provider = buildAIProviderForTesting(testSettings, providerId)
                                if (provider == null) {
                                    onStatus(ProviderConnectionStatus.Failure("Unknown AI provider."))
                                    return@launch
                                }

                                val issues = withContext(Dispatchers.IO) { provider.validateConfig() }
                                if (issues.isNotEmpty()) {
                                    onStatus(ProviderConnectionStatus.Failure(issues.first()))
                                    return@launch
                                }

                                onStatus(ProviderConnectionStatus.Success("Connection successful"))
                            }
                        },
                        onSaveAISettings = { updatedAISettings ->
                            scope.launch(exceptionHandler) {
                                val settingsToSave = updatedAISettings.copy(
                                    themeMode = themeMode.value.toThemePreference(),
                                )
                                val updatedRegistry = buildAIRegistry(settingsToSave)
                                try {
                                    withContext(Dispatchers.IO) { settingsRepo.save(settingsToSave) }
                                } catch (e: Exception) {
                                    logger.error("Failed to save preferences: {}", e.message)
                                    JOptionPane.showMessageDialog(
                                        window,
                                        e.message ?: "Failed to save preferences.",
                                        "Preferences",
                                        JOptionPane.ERROR_MESSAGE,
                                    )
                                    return@launch
                                }
                                logger.info("Preferences saved")
                                aiSettings.value = settingsToSave
                                showSettings.value = false
                                refreshAIProviders(updatedRegistry, appViewModel, Dispatchers.IO)
                            }
                        },
                    )
                }
            }
        }

        if (showExport.value) {
            Window(
                onCloseRequest = { showExport.value = false },
                title = "Generate Client Code",
                state = rememberWindowState(width = 640.dp, height = 720.dp),
            ) {
                StudioTheme(themeMode = themeMode.value) {
                    ExportReferencesScreen(
                        state = exportState.value,
                        onStateChange = { exportState.value = it },
                        onChooseFolder = {
                            val folder = chooseDirectory(window, "Export Destination", lastFileDirectory.value)
                            if (folder != null) {
                                exportState.value = exportState.value.copy(destinationFolder = folder)
                                lastFileDirectory.value = folder
                            }
                        },
                        onExport = {
                            val project = state.project ?: return@ExportReferencesScreen
                            val currentExportState = exportState.value
                            val destFolder = currentExportState.destinationFolder ?: return@ExportReferencesScreen
                            scope.launch(exceptionHandler) {
                                try {
                                    val catalog = ExportCatalogBuilder.build(project)
                                    val options = ExportOptions(
                                        languages = currentExportState.selectedLanguages,
                                        includeApiDescriptions = currentExportState.includeApiDescriptions,
                                        includeVariantDescriptions = currentExportState.includeVariantDescriptions,
                                        kotlinPackage = currentExportState.kotlinPackage
                                            .ifBlank { null },
                                        javaPackage = currentExportState.javaPackage
                                            .ifBlank { null },
                                    )
                                    val files = ExportRegistry.generate(catalog, options)
                                    withContext(Dispatchers.IO) {
                                        val destDir = File(destFolder)
                                        destDir.mkdirs()
                                        files.forEach { generated ->
                                            File(destDir, generated.fileName).writeText(generated.content)
                                        }
                                    }
                                    logger.info(
                                        "Exported {} file(s) to {}",
                                        files.size,
                                        destFolder,
                                    )
                                    showExport.value = false
                                } catch (e: Exception) {
                                    reportRecoverable(
                                        context = "Failed to export references",
                                        throwable = e,
                                        onUserMessage = appViewModel::setError,
                                    )
                                }
                            }
                        },
                        onCancel = { showExport.value = false },
                    )
                }
            }
        }
    }
}
