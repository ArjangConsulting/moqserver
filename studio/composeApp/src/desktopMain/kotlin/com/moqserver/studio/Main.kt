package com.moqserver.studio

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.moqserver.studio.data.HARImportParser
import com.moqserver.studio.data.LocalCompanionClient
import com.moqserver.studio.data.OpenAPIImportParser
import com.moqserver.studio.domain.AIAction
import com.moqserver.studio.domain.CompanionRequest
import com.moqserver.studio.domain.EndpointSummary
import com.moqserver.studio.domain.ImportSourceType
import com.moqserver.studio.domain.ProjectContext
import com.moqserver.studio.domain.SelectionContext
import com.moqserver.studio.domain.StudioRootViewModel
import com.moqserver.studio.projectformat.MoqProject
import com.moqserver.studio.projectformat.ProjectRepository
import com.moqserver.studio.logging.loggerFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Image
import java.awt.Taskbar
import java.awt.Window as AwtWindow
import java.io.File
import java.io.FilenameFilter
import javax.imageio.ImageIO
import javax.swing.JOptionPane
import javax.swing.JFileChooser
import javax.swing.filechooser.FileFilter

private const val STUDIO_CRASH_LOG_PROPERTY = "studio.crash.log"
private const val STUDIO_FAIL_FAST_PROPERTY = "studio.debug.failFast"
private const val MOQ_PROJECT_EXTENSION = "moqproj"
private const val MAC_FILE_DIALOG_PACKAGES_PROPERTY = "apple.awt.use-file-dialog-packages"
private const val STUDIO_APP_DISPLAY_NAME = "Moq Studio"
private const val STUDIO_APP_VERSION = "1.0.0"
private const val STUDIO_APP_ICON_RESOURCE = "/icons/icon.png"

private val logger = loggerFor<Any>() // package-level logger keyed to this file

fun main(args: Array<String>) {
    logger.info("moqserver studio starting (args={})", args.toList())
    installCrashHandlers()
    application {
        val repo = remember { ProjectRepository() }
        val openApiParser = remember { OpenAPIImportParser() }
        val harParser = remember { HARImportParser() }
        val companionClient = remember { LocalCompanionClient() }
        val appViewModel = remember { StudioRootViewModel() }
        val scope = rememberCoroutineScope()
        val themeMode = rememberSaveable { mutableStateOf(StudioThemeMode.SYSTEM) }
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

        val windowState = rememberWindowState(
            width = 1400.dp,
            height = 900.dp,
            position = WindowPosition.Aligned(Alignment.Center),
        )

        Window(
            onCloseRequest = {
                logger.debug("Window close requested: isDirty={}", state.isDirty)
                if (confirmExit(null, state, repo, appViewModel, lastFileDirectory)) {
                    logger.info("Application exiting")
                    exitApplication()
                }
            },
            title = STUDIO_APP_DISPLAY_NAME,
            state = windowState,
        ) {
            // Update window title dynamically
            window.title = state.windowTitle

            LaunchedEffect(window) {
                installAppIcon(window)
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

            LaunchedEffect(pendingProjectOpenPath.value) {
                val path = pendingProjectOpenPath.value ?: return@LaunchedEffect
                pendingProjectOpenPath.value = null
                if (!confirmProjectTransition(window, state, repo, appViewModel, lastFileDirectory)) {
                    logger.debug("OS file-open event cancelled while resolving current project state")
                    return@LaunchedEffect
                }
                openProject(
                    rawPath = path,
                    repo = repo,
                    appViewModel = appViewModel,
                    lastFileDirectory = lastFileDirectory,
                )
            }

            fun requestOpenProject() {
                scope.launch(exceptionHandler) {
                    logger.debug("User requested open project")
                    if (!confirmProjectTransition(window, appViewModel.state.value, repo, appViewModel, lastFileDirectory)) {
                        logger.debug("Open project cancelled while resolving current project state")
                        return@launch
                    }
                    val path = chooseOpenProjectPath(window, "Open .moqproj", lastFileDirectory.value)
                        ?: run { logger.debug("Open project dialog cancelled"); return@launch }
                    openProject(path, repo, appViewModel, lastFileDirectory)
                }
            }

            fun requestCloseProject() {
                scope.launch(exceptionHandler) {
                    logger.debug("User requested close project")
                    val currentState = appViewModel.state.value
                    if (currentState.project == null) return@launch
                    if (!confirmProjectTransition(window, currentState, repo, appViewModel, lastFileDirectory)) {
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

            MenuBar {
                Menu("File") {
                    Item("Open Project", onClick = ::requestOpenProject)
                    Item("Close Project", enabled = state.project != null, onClick = ::requestCloseProject)
                    Separator()
                    Item("Import OpenAPI", onClick = ::requestImportOpenAPI)
                    Item("Import HAR", onClick = ::requestImportHAR)
                }
                Menu("Help") {
                    Item("About $STUDIO_APP_DISPLAY_NAME", onClick = { showAboutDialog(window) })
                }
            }

            StudioTheme(themeMode = themeMode.value) {
                App(
                    appViewModel = appViewModel,
                    themeMode = themeMode.value,
                    onThemeModeChange = { newMode ->
                        logger.debug("Theme changed: {} → {}", themeMode.value, newMode)
                        themeMode.value = newMode
                    },
                    onToggleAiPanel = {
                        val nextVisible = !state.aiPanelVisible
                        appViewModel.setAiPanelVisible(nextVisible)
                        if (nextVisible && !state.companion.connected && !state.companion.hasChecked) {
                            scope.launch(exceptionHandler) {
                                refreshCompanion(companionClient, appViewModel)
                            }
                        }
                    },
                    onOpenProject = ::requestOpenProject,
                    onCloseProject = ::requestCloseProject,
                    onSaveProject = { project ->
                        scope.launch(exceptionHandler) {
                            logger.info("Saving project '{}' to: {}", project.manifest.name, project.projectPath)
                            try {
                                withContext(Dispatchers.IO) { repo.save(project, project.projectPath) }
                                appViewModel.projectSaved(project.projectPath)
                            } catch (e: Exception) {
                                reportRecoverable(
                                    context = "Failed to save project",
                                    throwable = e,
                                    onUserMessage = appViewModel::setError,
                                )
                            }
                        }
                    },
                    onSaveProjectAs = { project ->
                        scope.launch(exceptionHandler) {
                            logger.debug("User requested Save As for project '{}'", project.manifest.name)
                            val path = chooseProjectDirectory(
                                parent = window,
                                title = "Save Project As",
                                initialDirectory = lastFileDirectory.value,
                                projectName = project.manifest.name,
                            )
                                ?: run { logger.debug("Save As dialog cancelled"); return@launch }
                            logger.info("Saving project '{}' as: {}", project.manifest.name, path)
                            try {
                                withContext(Dispatchers.IO) { repo.save(project, path) }
                                appViewModel.projectSaved(path)
                                appViewModel.addRecentProject(path)
                                lastFileDirectory.value = File(path).parentFile?.canonicalPath ?: path
                            } catch (e: Exception) {
                                reportRecoverable(
                                    context = "Failed to save project",
                                    throwable = e,
                                    onUserMessage = appViewModel::setError,
                                )
                            }
                        }
                    },
                    onImportOpenAPI = ::requestImportOpenAPI,
                    onImportHAR = ::requestImportHAR,
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
                    onRefreshCompanion = {
                        scope.launch(exceptionHandler) { refreshCompanion(companionClient, appViewModel) }
                    },
                    onAIAction = { action ->
                        scope.launch(exceptionHandler) {
                            executeAIAction(action, companionClient, appViewModel)
                        }
                    },
                )
            }
        }
    }
}

private fun installAppIcon(window: AwtWindow) {
    val icon = loadAppIcon() ?: return
    window.iconImages = listOf(icon)

    runCatching {
        if (Taskbar.isTaskbarSupported()) {
            val taskbar = Taskbar.getTaskbar()
            if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                taskbar.iconImage = icon
            }
        }
    }.onFailure { throwable ->
        logger.debug("Failed to install taskbar icon", throwable)
    }
}

private fun loadAppIcon(): Image? = runCatching {
    Thread.currentThread().contextClassLoader
        .getResourceAsStream(STUDIO_APP_ICON_RESOURCE.removePrefix("/"))
        ?.use(ImageIO::read)
}.onFailure { throwable ->
    logger.warn("Failed to load app icon from {}", STUDIO_APP_ICON_RESOURCE, throwable)
}.getOrNull()

private suspend fun openProject(
    rawPath: String,
    repo: ProjectRepository,
    appViewModel: StudioRootViewModel,
    lastFileDirectory: androidx.compose.runtime.MutableState<String?>,
) {
    val path = resolveProjectPath(rawPath)
        ?: run {
            logger.warn("Path is not a .$MOQ_PROJECT_EXTENSION project: {}", rawPath)
            appViewModel.setError("Not a .$MOQ_PROJECT_EXTENSION project: $rawPath")
            return
        }

    logger.info("Opening project: {}", path)
    try {
        val project = withContext(Dispatchers.IO) { repo.load(path) }
        appViewModel.projectLoaded(project)
        appViewModel.addRecentProject(path)
        lastFileDirectory.value = File(path).parentFile?.canonicalPath ?: path
        logger.info("Project '{}' opened successfully", project.manifest.name)
    } catch (e: Exception) {
        reportRecoverable(
            context = "Failed to load project",
            throwable = e,
            onUserMessage = appViewModel::setError,
        )
    }
}

private suspend fun refreshCompanion(
    client: LocalCompanionClient,
    viewModel: StudioRootViewModel,
): Boolean {
    logger.debug("Refreshing companion connection: {}", client.endpoint)
    viewModel.companionChecking()
    try {
        withContext(Dispatchers.IO) { client.health() }
        val response = withContext(Dispatchers.IO) { client.providers() }
        val available = response.providers.count { it.available }
        logger.info("Companion connected: {} provider(s), {} available", response.providers.size, available)
        viewModel.companionConnected(response.providers)
        return true
    } catch (e: Exception) {
        logger.warn("AI companion not reachable at {}: {}", client.endpoint, e.message)
        logger.debug("AI companion connection failure", e)
        viewModel.companionDisconnected(
            buildCompanionUnavailableMessage(client, e),
        )
        return false
    }
}

private suspend fun executeAIAction(
    action: AIAction,
    client: LocalCompanionClient,
    viewModel: StudioRootViewModel,
) {
    viewModel.aiActionStarted(action)
    if (!refreshCompanionIfNeeded(client, viewModel, action)) return

    val state = viewModel.state.value
    val providerId = state.companion.selectedProviderId ?: run {
        logger.warn("AI action {} requested but no provider selected", action)
        viewModel.aiActionFailed(
            "The AI companion is connected, but no provider is available. Start or configure a provider in the Swift companion server.",
        )
        return
    }
    val project = state.project

    logger.info("Executing AI action: {} with provider={}", action, providerId)

    try {
        when (action) {
            AIAction.ANALYZE_SPEC -> {
                val request = CompanionRequest(
                    providerId = providerId,
                    projectContext = project?.let { buildProjectContext(it) },
                )
                val result = withContext(Dispatchers.IO) { client.analyzeSpec(request) }
                logger.info("AI analyze-spec succeeded (provider={})", providerId)
                viewModel.analyzeSpecCompleted(result)
            }
            AIAction.GENERATE_VARIANTS -> {
                val selectedEndpoint = state.selectedEndpoint
                logger.debug(
                    "generate-variants for endpoint: {} {}",
                    selectedEndpoint?.method,
                    selectedEndpoint?.path,
                )
                val request = CompanionRequest(
                    providerId = providerId,
                    projectContext = project?.let { buildProjectContext(it) },
                    selection = selectedEndpoint?.let {
                        SelectionContext(endpointKeys = listOf("${it.method} ${it.path}"))
                    },
                )
                val result = withContext(Dispatchers.IO) { client.generateVariants(request) }
                logger.info("AI generate-variants succeeded (provider={})", providerId)
                viewModel.generateVariantsCompleted(result)
            }
            AIAction.REFINE_PROJECT -> {
                val request = CompanionRequest(
                    providerId = providerId,
                    projectContext = project?.let { buildProjectContext(it) },
                )
                val result = withContext(Dispatchers.IO) { client.refineProject(request) }
                logger.info("AI refine-project succeeded (provider={})", providerId)
                viewModel.refineProjectCompleted(result)
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

private suspend fun refreshCompanionIfNeeded(
    client: LocalCompanionClient,
    viewModel: StudioRootViewModel,
    action: AIAction,
): Boolean {
    val companion = viewModel.state.value.companion
    if (companion.connected && companion.hasAvailableProvider) return true

    val connected = refreshCompanion(client, viewModel)
    if (!connected) {
        viewModel.aiActionFailed(
            "Cannot run ${action.displayName} because the optional AI companion is offline.\n\n${viewModel.state.value.companion.error.orEmpty()}",
        )
        return false
    }

    if (!viewModel.state.value.companion.hasAvailableProvider) {
        viewModel.aiActionFailed(
            "The AI companion is running, but it did not report any available providers.",
        )
        return false
    }

    return true
}

private fun buildCompanionUnavailableMessage(
    client: LocalCompanionClient,
    throwable: Throwable,
): String {
    val detail = throwable.message?.takeIf { it.isNotBlank() } ?: "Unknown error"
    return "Cannot reach the optional local AI companion at ${client.endpoint} ($detail). Start it with: swift run moqserver companion --port 8081"
}

private val AIAction.displayName: String
    get() = when (this) {
        AIAction.ANALYZE_SPEC -> "Analyze Spec"
        AIAction.GENERATE_VARIANTS -> "Generate Variants"
        AIAction.REFINE_PROJECT -> "Refine Project"
    }

private fun confirmExit(
    owner: AwtWindow?,
    state: com.moqserver.studio.domain.StudioState,
    repo: ProjectRepository,
    appViewModel: StudioRootViewModel,
    lastFileDirectory: androidx.compose.runtime.MutableState<String?>,
): Boolean {
    if (state.project == null || !state.isDirty) return true
    return confirmProjectTransition(owner, state, repo, appViewModel, lastFileDirectory)
}

private fun confirmProjectTransition(
    owner: AwtWindow?,
    state: com.moqserver.studio.domain.StudioState,
    repo: ProjectRepository,
    appViewModel: StudioRootViewModel,
    lastFileDirectory: androidx.compose.runtime.MutableState<String?>,
): Boolean {
    if (state.project == null || !state.isDirty) return true

    val result = JOptionPane.showConfirmDialog(
        owner,
        "You have unsaved changes. Save before continuing?",
        "Unsaved Changes",
        JOptionPane.YES_NO_CANCEL_OPTION,
    )

    return when (result) {
        JOptionPane.YES_OPTION -> {
            val project = state.project ?: return true
            val path = if (project.projectPath.isBlank()) {
                chooseProjectDirectory(
                    parent = owner,
                    title = "Save Project",
                    initialDirectory = null,
                    projectName = project.manifest.name,
                ) ?: return false
            } else {
                project.projectPath
            }

            logger.info("Saving project before continuing: {}", path)
            try {
                runBlocking {
                    withContext(Dispatchers.IO) {
                        repo.save(project, path)
                    }
                }
                appViewModel.projectSaved(path)
                appViewModel.addRecentProject(path)
                lastFileDirectory.value = File(path).parentFile?.canonicalPath ?: path
                true
            } catch (throwable: Throwable) {
                if (isFailFastEnabled()) {
                    throw propagateFailure("Failed to save before closing", throwable)
                }
                reportFatal("Failed to save before closing", throwable)
                false
            }
        }
        JOptionPane.NO_OPTION -> {
            logger.info("User chose to continue without saving")
            true
        }
        else -> {
            logger.debug("Project transition cancelled by user")
            false
        }
    }
}

private fun showAboutDialog(owner: AwtWindow?) {
    JOptionPane.showMessageDialog(
        owner,
        "$STUDIO_APP_DISPLAY_NAME\nVersion $STUDIO_APP_VERSION",
        "About $STUDIO_APP_DISPLAY_NAME",
        JOptionPane.INFORMATION_MESSAGE,
    )
}

private fun installCrashHandlers() {
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        reportFatal("Unhandled exception on ${thread.name}", throwable)
    }
    logger.debug("Crash handlers installed (crash log: {})", crashLogFile().absolutePath)
}

private fun reportFatal(context: String, throwable: Throwable) {
    val message = buildString {
        append(context)
        append(':')
        append(' ')
        append(throwable.message ?: throwable::class.java.name)
    }
    logger.error("{}", message, throwable)
    appendCrashLog(message, throwable)
    if (isFailFastEnabled()) {
        return
    }
    if (!java.awt.GraphicsEnvironment.isHeadless()) {
        runCatching {
            JOptionPane.showMessageDialog(
                null,
                "$message\n\n${throwable.stackTraceToString()}",
                "moqserver studio crashed",
                JOptionPane.ERROR_MESSAGE,
            )
        }
    }
}

private fun reportRecoverable(
    context: String,
    throwable: Throwable,
    onUserMessage: (String) -> Unit,
) {
    val message = throwable.message ?: context
    logger.error("{}: {}", context, message, throwable)
    appendCrashLog("$context: $message", throwable)
    if (isFailFastEnabled()) {
        throw propagateFailure("$context: $message", throwable)
    }
    onUserMessage(message)
}

private fun appendCrashLog(message: String, throwable: Throwable) {
    runCatching {
        val logFile = crashLogFile()
        logFile.parentFile?.mkdirs()
        logFile.appendText(
            buildString {
                appendLine("[${
                    java.time.Instant.now()
                }] $message")
                appendLine(throwable.stackTraceToString())
                appendLine()
            }
        )
    }
}

private fun crashLogFile(): File {
    val override = System.getProperty(STUDIO_CRASH_LOG_PROPERTY)
    if (!override.isNullOrBlank()) {
        return File(override)
    }
    val home = System.getProperty("user.home")
    val osName = System.getProperty("os.name").lowercase()
    return when {
        "mac" in osName -> File(home, "Library/Logs/moqserver-studio/studio-crash.log")
        "win" in osName -> File(System.getenv("LOCALAPPDATA") ?: home, "moqserver-studio/studio-crash.log")
        else -> File(home, ".local/state/moqserver-studio/studio-crash.log")
    }
}

private fun isFailFastEnabled(): Boolean =
    System.getProperty(STUDIO_FAIL_FAST_PROPERTY)?.toBooleanStrictOrNull() == true

private fun propagateFailure(context: String, throwable: Throwable): RuntimeException {
    return when (throwable) {
        is RuntimeException -> throwable
        else -> RuntimeException(context, throwable)
    }
}

private fun buildProjectContext(project: MoqProject): ProjectContext {
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

private fun chooseFile(
    parent: AwtWindow?,
    title: String,
    extensions: Set<String>,
    initialDirectory: String?,
    treatPackagesAsFiles: Boolean = false,
    allowDirectories: Boolean = false,
): File? {
    val isMac = System.getProperty("os.name").lowercase().contains("mac")
    // On non-Mac, FileDialog cannot select directories — use JFileChooser instead
    if (allowDirectories && !isMac) {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
            isAcceptAllFileFilterUsed = extensions.isEmpty()
            isFileHidingEnabled = false
            currentDirectory = initialDirectory?.let(::File)?.takeIf(File::isDirectory)
            if (extensions.isNotEmpty()) {
                fileFilter = object : FileFilter() {
                    override fun accept(file: File) =
                        file.isDirectory || extensions.any { ext -> file.name.endsWith(".$ext", ignoreCase = true) }
                    override fun getDescription() = extensions.joinToString(", ") { "*.$it" }
                }
            }
        }
        return if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }
    return withMacFileDialogPackages(treatPackagesAsFiles) {
        val dialog = createFileDialog(parent, title, FileDialog.LOAD).apply {
            isMultipleMode = false
            directory = initialDirectory?.takeIf { File(it).isDirectory }
            filenameFilter = FilenameFilter { _, name ->
                extensions.isEmpty() || extensions.any { ext ->
                    name.endsWith(".$ext", ignoreCase = true)
                }
            }
        }
        dialog.isVisible = true

        val directory = dialog.directory ?: return@withMacFileDialogPackages null
        val file = dialog.file ?: return@withMacFileDialogPackages null
        File(directory, file)
    }
}

private fun chooseDirectory(parent: AwtWindow?, title: String, initialDirectory: String?): String? {
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        val previous = System.getProperty("apple.awt.fileDialogForDirectories")
        return try {
            System.setProperty("apple.awt.fileDialogForDirectories", "true")
            val dialog = createFileDialog(parent, title, FileDialog.LOAD).apply {
                directory = initialDirectory?.takeIf { File(it).isDirectory }
            }
            dialog.isVisible = true
            resolveSelectedDirectory(dialog.directory, dialog.file)
        } finally {
            if (previous == null) {
                System.clearProperty("apple.awt.fileDialogForDirectories")
            } else {
                System.setProperty("apple.awt.fileDialogForDirectories", previous)
            }
        }
    }

    val chooser = JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
        currentDirectory = initialDirectory?.let(::File)?.takeIf(File::isDirectory)
    }
    if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
        return chooser.selectedFile.canonicalPath
    }
    return null
}

private fun chooseOpenProjectPath(parent: AwtWindow?, title: String, initialDirectory: String?): String? {
    return chooseFile(
        parent,
        title,
        extensions = setOf(MOQ_PROJECT_EXTENSION),
        initialDirectory = projectPickerInitialDirectory(initialDirectory),
        treatPackagesAsFiles = true,
        allowDirectories = true,
    )?.canonicalPath
}

internal fun <T> withMacFileDialogPackages(enabled: Boolean, block: () -> T): T {
    if (!System.getProperty("os.name").lowercase().contains("mac")) {
        return block()
    }

    val previous = System.getProperty(MAC_FILE_DIALOG_PACKAGES_PROPERTY)
    return try {
        System.setProperty(MAC_FILE_DIALOG_PACKAGES_PROPERTY, enabled.toString())
        block()
    } finally {
        if (previous == null) {
            System.clearProperty(MAC_FILE_DIALOG_PACKAGES_PROPERTY)
        } else {
            System.setProperty(MAC_FILE_DIALOG_PACKAGES_PROPERTY, previous)
        }
    }
}

internal fun resolveSelectedDirectory(directory: String?, file: String?): String? {
    val selectedDirectory = directory
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
        ?.takeIf(File::isDirectory)
    val selectedFile = file?.takeIf { it.isNotBlank() }

    val resolved = when {
        selectedDirectory != null && selectedFile != null -> {
            val candidate = File(selectedFile)
            if (candidate.isAbsolute) candidate else File(selectedDirectory, selectedFile)
        }
        selectedDirectory != null -> selectedDirectory
        selectedFile != null -> File(selectedFile)
        else -> return null
    }

    return resolved.takeIf(File::isDirectory)?.canonicalPath
}

private fun chooseProjectDirectory(
    parent: AwtWindow?,
    title: String,
    initialDirectory: String?,
    projectName: String,
): String? {
    val defaultFileName = buildProjectPackageName(projectName)
    val dialogDirectory = projectPickerInitialDirectory(initialDirectory)

    if (System.getProperty("os.name").lowercase().contains("mac")) {
        return withMacFileDialogPackages(true) {
            val dialog = createFileDialog(parent, title, FileDialog.SAVE).apply {
                directory = dialogDirectory
                file = defaultFileName
            }
            dialog.isVisible = true
            resolveSelectedProjectPath(dialog.directory, dialog.file, projectName)
        }
    }

    val chooser = JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.FILES_ONLY
        isAcceptAllFileFilterUsed = false
        currentDirectory = dialogDirectory?.let(::File)?.takeIf(File::isDirectory)
        selectedFile = currentDirectory?.let { File(it, defaultFileName) } ?: File(defaultFileName)
        fileFilter = object : FileFilter() {
            override fun accept(file: File) =
                file.isDirectory || file.name.endsWith(".$MOQ_PROJECT_EXTENSION", ignoreCase = true)

            override fun getDescription() = "*.$MOQ_PROJECT_EXTENSION"
        }
    }

    return if (chooser.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) {
        resolveSelectedProjectPath(
            directory = chooser.currentDirectory?.canonicalPath,
            file = chooser.selectedFile?.path,
            projectName = projectName,
        )
    } else {
        null
    }
}

private fun projectPickerInitialDirectory(initialDirectory: String?): String? {
    val directory = initialDirectory?.let(::File)?.takeIf(File::isDirectory) ?: return null
    return if (directory.name.endsWith(".moqproj", ignoreCase = true)) {
        directory.parentFile?.canonicalPath
    } else {
        directory.canonicalPath
    }
}

internal fun resolveSelectedProjectPath(
    directory: String?,
    file: String?,
    projectName: String,
): String? {
    val selectedDirectory = directory
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
        ?.takeIf(File::isDirectory)
    val selectedFile = file?.trim()?.takeIf { it.isNotBlank() }?.let(::File)

    val resolved = when {
        selectedFile?.isAbsolute == true -> selectedFile
        selectedDirectory != null && selectedFile != null -> File(selectedDirectory, selectedFile.path)
        selectedDirectory != null -> File(selectedDirectory, buildProjectPackageName(projectName))
        else -> return null
    }

    return normalizeProjectPackagePath(resolved).canonicalPath
}

private fun normalizeProjectPackagePath(path: File): File {
    if (path.name.endsWith(".$MOQ_PROJECT_EXTENSION", ignoreCase = true)) {
        return path
    }
    return File(path.parentFile ?: File("."), "${path.name}.$MOQ_PROJECT_EXTENSION")
}

private fun buildProjectPackageName(projectName: String): String {
    val cleanedName = projectName
        .replace(Regex("[\\\\/:*?\"<>|]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '.')
        .ifBlank { "Untitled Project" }

    return if (cleanedName.endsWith(".moqproj", ignoreCase = true)) {
        cleanedName
    } else {
        "$cleanedName.moqproj"
    }
}

private fun createFileDialog(parent: AwtWindow?, title: String, mode: Int): FileDialog {
    return when (parent) {
        is java.awt.Frame -> FileDialog(parent, title, mode)
        is java.awt.Dialog -> FileDialog(parent, title, mode)
        else -> FileDialog(null as java.awt.Frame?, title, mode)
    }
}

private fun resolveInitialProjectPath(args: Array<String>): String? {
    return args.firstNotNullOfOrNull(::resolveProjectPath)
}

private fun installProjectOpenHandler(onOpenProject: (String) -> Unit) {
    if (!Desktop.isDesktopSupported()) return
    val desktop = runCatching { Desktop.getDesktop() }.getOrNull() ?: return
    if (!desktop.isSupported(Desktop.Action.APP_OPEN_FILE)) return

    desktop.setOpenFileHandler { event ->
        val path = event.files.firstNotNullOfOrNull { file ->
            resolveProjectPath(file.canonicalPath)
        } ?: return@setOpenFileHandler
        onOpenProject(path)
    }
}

private fun resolveProjectPath(rawPath: String): String? {
    val candidate = runCatching { File(rawPath).canonicalFile }.getOrNull() ?: return null
    return generateSequence(candidate) { it.parentFile }
        .firstOrNull(::isProjectDirectory)
        ?.canonicalPath
}

private fun isProjectDirectory(file: File): Boolean {
    return file.isDirectory && (
        file.name.endsWith(".$MOQ_PROJECT_EXTENSION", ignoreCase = true) ||
            File(file, "project.yml").isFile
        )
}
