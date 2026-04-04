package com.moqserver.studio

import java.io.File
import javax.swing.JOptionPane

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.moqserver.studio.data.AISettingsRepository
import com.moqserver.studio.data.HARImportParser
import com.moqserver.studio.data.OpenAPIImportParser
import com.moqserver.studio.data.RecentProjectsRepository
import com.moqserver.studio.data.ThemePreference
import com.moqserver.studio.domain.ImportSourceType
import com.moqserver.studio.domain.StudioRootViewModel
import com.moqserver.studio.logging.loggerFor
import com.moqserver.studio.projectformat.ProjectRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val logger = loggerFor<Any>()

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
        val aiSettings = remember { mutableStateOf(settingsRepo.load()) }
        val aiRegistry = remember(aiSettings.value) { buildAIRegistry(aiSettings.value) }
        val showSettings = remember { mutableStateOf(false) }
        val appViewModel = remember { StudioRootViewModel() }
        val scope = rememberCoroutineScope()
        val themeMode = remember { mutableStateOf(aiSettings.value.themeMode.toStudioThemeMode()) }
        val lastFileDirectory = remember { mutableStateOf<String?>(null) }
        val pendingProjectOpenPath = remember { mutableStateOf(resolveInitialProjectPath(args)) }
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
                                repo = repo,
                                appViewModel = appViewModel,
                                lastFileDirectory = lastFileDirectory,
                                recentProjectsRepo = recentProjectsRepo,
                                ioDispatcher = Dispatchers.IO,
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
                    repo = repo,
                    appViewModel = appViewModel,
                    lastFileDirectory = lastFileDirectory,
                    recentProjectsRepo = recentProjectsRepo,
                    ioDispatcher = Dispatchers.IO,
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

            fun requestImportOpenAPI() {
                scope.launch(exceptionHandler) {
                    logger.debug("User requested import OpenAPI spec")
                    if (!guardProjectTransition()) {
                        logger.debug("Import OpenAPI cancelled while resolving current project state")
                        return@launch
                    }
                    val file = chooseFile(
                        window,
                        "Import OpenAPI Spec",
                        setOf("yaml", "yml", "json"),
                        lastFileDirectory.value,
                    ) ?: run { logger.debug("Import OpenAPI dialog cancelled"); return@launch }
                    logger.info("Importing OpenAPI spec from: {}", file.absolutePath)
                    try {
                        val content = withContext(Dispatchers.IO) { file.readText() }
                        val spec = withContext(Dispatchers.IO) { openApiParser.parse(content) }
                        appViewModel.startImport(spec, ImportSourceType.OPENAPI, file.name)
                        lastFileDirectory.value = file.parentFile?.canonicalPath ?: lastFileDirectory.value
                    } catch (e: Exception) {
                        reportRecoverable(
                            context = "Failed to parse OpenAPI spec",
                            throwable = e,
                            onUserMessage = appViewModel::setError,
                        )
                    }
                }
            }

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

            suspend fun saveProject(project: com.moqserver.studio.projectformat.MoqProject) {
                logger.info("Saving project '{}' to: {}", project.manifest.name, project.projectPath)
                withContext(Dispatchers.IO) { repo.save(project, project.projectPath) }
                appViewModel.projectSaved(project.projectPath)
                appViewModel.addRecentProject(project.projectPath)
                withContext(Dispatchers.IO) { recentProjectsRepo.save(appViewModel.state.value.recentProjects) }
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
                withContext(Dispatchers.IO) { repo.save(project, path) }
                appViewModel.projectSaved(path)
                appViewModel.addRecentProject(path)
                withContext(Dispatchers.IO) { recentProjectsRepo.save(appViewModel.state.value.recentProjects) }
                lastFileDirectory.value = File(path).parentFile?.canonicalPath ?: path
            }

            val isMac = isMacOs()
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
                    Item("Import OpenAPI", onClick = ::requestImportOpenAPI)
                    Item("Import HAR", onClick = ::requestImportHAR)
                }
                if (showPreferencesInMenuBar) {
                    Menu("Edit") {
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
                    themeMode = themeMode.value,
                    onOpenProject = ::requestOpenProject,
                    onImportOpenAPI = ::requestImportOpenAPI,
                    onImportHAR = ::requestImportHAR,
                    onOpenRecentProject = ::requestOpenRecentProject,
                    onRemoveRecentProject = ::removeRecentProject,
                    onConfirmImport = {
                        scope.launch(exceptionHandler) {
                            val importState = appViewModel.state.value.importState ?: return@launch
                            logger.debug(
                                "User confirming import: source={}, accepted={}/{}",
                                importState.sourceFileName,
                                importState.entries.count { it.accepted },
                                importState.entries.size,
                            )
                            val path = chooseProjectDirectory(
                                parent = window,
                                title = "Import Project",
                                initialDirectory = lastFileDirectory.value,
                                projectName = importState.projectName,
                            )
                                ?: run { logger.debug("Import save dialog cancelled"); return@launch }
                            logger.info("Saving imported project '{}' to: {}", importState.projectName, path)
                            try {
                                val project = appViewModel.confirmImport(path) ?: return@launch
                                withContext(Dispatchers.IO) { repo.save(project, path) }
                                appViewModel.projectSaved(path)
                                appViewModel.addRecentProject(path)
                                withContext(Dispatchers.IO) { recentProjectsRepo.save(appViewModel.state.value.recentProjects) }
                                lastFileDirectory.value = File(path).parentFile?.canonicalPath ?: path
                                logger.info("Import complete: {} endpoint(s) saved to {}", project.endpoints.size, path)
                            } catch (e: Exception) {
                                reportRecoverable(
                                    context = "Failed to save imported project",
                                    throwable = e,
                                    onUserMessage = appViewModel::setError,
                                )
                            }
                        }
                    },
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
                )
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
    }
}
