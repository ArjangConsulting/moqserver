package com.moqserver.studio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.moqserver.studio.data.AISettings
import com.moqserver.studio.data.AISettingsRepository
import com.moqserver.studio.data.ImportHistoryRepository
import com.moqserver.studio.data.RecentProjectsRepository
import com.moqserver.studio.domain.StudioRootViewModel
import com.moqserver.studio.domain.StudioState
import com.moqserver.studio.logging.loggerFor
import com.moqserver.studio.projectformat.ProjectRepository
import com.moqserver.studio.projectformat.format.FormatBinaryLocator
import com.moqserver.studio.projectformat.format.FormatClient
import com.moqserver.studio.projectformat.format.FormatProcess
import com.moqserver.studio.projectformat.format.RemoteProjectValidator
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

private val logger = loggerFor<StudioAppState>()

fun main(args: Array<String>) {
	System.setProperty("apple.awt.application.name", STUDIO_APP_DISPLAY_NAME)
	logger.info("moqserver studio starting (args={})", args.toList())
	installCrashHandlers()
	val settingsRepo = AISettingsRepository()
	// Only the settings file is read before the first frame; API keys come from the keychain
	// (which can be slow or prompt the user) once the window is up. See SettingsActions.
	val initialSettings = settingsRepo.loadWithoutCredentials()
	application {
		StudioApplication(args, settingsRepo, initialSettings)
	}
}

@Composable
private fun ApplicationScope.StudioApplication(
	args: Array<String>,
	settingsRepo: AISettingsRepository,
	initialSettings: AISettings,
) {
	// .moqproj reading, writing, validation, and HAR/OpenAPI import parsing all go through
	// moq-format, a supervised subprocess (see FormatProcess doc comment for why: a Swift
	// trap is process-fatal, so it runs out-of-process rather than risk taking Studio down
	// with unsaved work). One process, one FormatClient, for the app's lifetime; repo and
	// remoteValidator are both thin adapters over the same client — repo owns the load/save
	// session, the validator adapts wire diagnostics into the ValidationDiagnostic shape
	// ValidationPanel renders. allowNetworkImport: true because "Import from URL" is a
	// first-party desktop feature that worked unconditionally before this cutover — see
	// FormatProcess's allowNetworkImport doc for why this differs from moq-mcp's default.
	val services = remember {
		val formatProcess = FormatProcess(locateBinary = { FormatBinaryLocator.locate() }, allowNetworkImport = true)
			.apply { start() }
		val formatClient = FormatClient(formatProcess)
		StudioServices(
			formatProcess = formatProcess,
			formatClient = formatClient,
			repo = ProjectRepository(formatClient),
			settingsRepo = settingsRepo,
			recentProjectsRepo = RecentProjectsRepository(),
			importHistoryRepo = ImportHistoryRepository(),
			appViewModel = StudioRootViewModel(validate = RemoteProjectValidator(formatClient)::validate),
			ioDispatcher = Dispatchers.IO,
		)
	}
	DisposableEffect(services) {
		onDispose { services.formatProcess.stop() }
	}
	val appState = remember { StudioAppState(initialSettings, resolveInitialProjectPath(args)) }
	val viewModel = services.appViewModel
	val state by viewModel.state.collectAsState()

	LaunchedEffect(appState.aiSettings.selectedProviderId) {
		appState.aiSettings.selectedProviderId?.let(viewModel::selectProvider)
	}
	LaunchedEffect(Unit) {
		viewModel.setRecentProjects(services.recentProjectsRepo.load().filter { File(it).isDirectory })
	}

	val windowState = rememberWindowState(
		width = StudioDimens.mainWindowWidth,
		height = StudioDimens.mainWindowHeight,
		position = WindowPosition.Aligned(Alignment.Center),
	)
	val shortcuts = remember { StudioShortcuts(isMacOs()) }

	Window(
		onCloseRequest = { handleWindowClose(state, viewModel, ::exitApplication) },
		title = STUDIO_APP_DISPLAY_NAME,
		state = windowState,
		onKeyEvent = { event ->
			val action: (() -> Unit)? = when {
				shortcuts.isUndo(event) && state.canUndo -> { { viewModel.undo() } }
				shortcuts.isRedo(event) && state.canRedo -> { { viewModel.redo() } }
				shortcuts.isPreferences(event) -> { { appState.showSettings = true } }
				else -> null
			}
			action?.invoke()
			action != null
		},
	) {
		StudioMainWindow(services, appState, state, windowState, shortcuts)
	}
}

private fun handleWindowClose(state: StudioState, viewModel: StudioRootViewModel, exitApplication: () -> Unit) {
	when (resolveWindowCloseAction(state)) {
		WindowCloseAction.CLOSE_PROJECT -> {
			logger.debug("Window close requested: closing current project (isDirty={})", state.isDirty)
			if (confirmProjectTransition(owner = null, state = state)) {
				viewModel.projectClosed()
				logger.info("Project closed from window close request")
			}
		}

		WindowCloseAction.EXIT_APPLICATION -> {
			logger.info("Window close requested from landing screen: exiting application")
			exitApplication()
		}
	}
}

@Composable
private fun FrameWindowScope.StudioMainWindow(
	services: StudioServices,
	appState: StudioAppState,
	state: StudioState,
	windowState: WindowState,
	shortcuts: StudioShortcuts,
) {
	val viewModel = services.appViewModel
	val scope = rememberCoroutineScope()
	val exceptionHandler = remember {
		CoroutineExceptionHandler { _, throwable ->
			if (isFailFastEnabled()) {
				throw propagateFailure("Unhandled coroutine exception", throwable)
			}
			reportFatal("Unhandled coroutine exception", throwable)
		}
	}
	val projectActions = remember(window) { ProjectActions(services, appState, scope, exceptionHandler, window) }
	val importActions = remember(window) {
		ImportActions(services, appState, projectActions, scope, exceptionHandler, window)
	}
	val settingsActions = remember(window) { SettingsActions(services, appState, scope, exceptionHandler, window) }
	val aiRegistry = remember(appState.aiSettings) { buildAIRegistry(appState.aiSettings) }
	val aiActions = remember(aiRegistry) { AIActions(services, aiRegistry, scope, exceptionHandler) }

	window.title = state.windowTitle

	LaunchedEffect(window) {
		installAppIcon(window)
		installAboutHandler { showAboutDialog(window) }
		installPreferencesHandler { appState.showSettings = true }
		installProjectOpenHandler { incomingPath ->
			logger.info("OS file-open event: {}", incomingPath)
			appState.requestProjectOpen(incomingPath)
		}
	}
	LaunchedEffect(settingsActions) { settingsActions.loadCredentials() }
	LaunchedEffect(aiRegistry) { aiActions.refreshProviders() }
	LaunchedEffect(state.project) {
		if (state.project != null) windowState.placement = WindowPlacement.Maximized
	}
	LaunchedEffect(appState.pendingProjectOpenToken) { projectActions.openPendingProject() }

	StudioMenuBar(
		state = state,
		shortcuts = shortcuts,
		commands = StudioMenuCommands(
			project = projectActions,
			import = importActions,
			appState = appState,
			onUndo = viewModel::undo,
			onRedo = viewModel::redo,
			onRetryFormatService = { scope.launch(exceptionHandler) { services.formatProcess.restart() } },
			onShowAbout = { showAboutDialog(window) },
		),
	)

	if (appState.showRuntimeInspector) {
		RuntimeInspectionWindow(
			state = state.runtime,
			onUpdate = viewModel::runtimeUpdated,
			onAction = { action, name ->
				scope.launch(exceptionHandler) { inspectRuntime(viewModel, action, name, services.ioDispatcher) }
			},
			onClose = { appState.showRuntimeInspector = false },
			themeMode = appState.themeMode,
		)
	}

	StudioTheme(themeMode = appState.themeMode) {
		App(
			appViewModel = viewModel,
			onOpenProject = projectActions::requestOpenProject,
			onImportOpenAPI = { importActions.requestImportFile(ImportFileKind.OPENAPI) },
			onImportSwagger = { importActions.requestImportFile(ImportFileKind.SWAGGER) },
			onImportOpenAPIURL = { importActions.requestImportFromURL(URLImportMode.OPENAPI, URLImportAction.IMPORT) },
			onImportSwaggerURL = { importActions.requestImportFromURL(URLImportMode.SWAGGER, URLImportAction.IMPORT) },
			onImportHAR = { importActions.requestImportFile(ImportFileKind.HAR) },
			onOpenRecentProject = projectActions::requestOpenRecentProject,
			onRemoveRecentProject = projectActions::removeRecentProject,
			onAIAction = aiActions::execute,
			onGenerateBody = aiActions::generateBody,
			readFixture = services.repo::readFixture,
			variantReferenceSyncPreference = state.project?.projectPath?.let {
				appState.aiSettings.variantReferenceSyncByProject[it]
			},
			onVariantReferenceSyncPreferenceChange = settingsActions::saveVariantReferenceSyncPreference,
		)

		if (appState.showImportURLDialog) {
			ImportFromURLHost(appState, importActions)
		}

		state.importState?.let { importState ->
			ImportReviewWindow(importState, state, appState.themeMode, viewModel, importActions, aiActions)
		}
	}

	// Preferences edits API keys, so it opens only once the stored keys have loaded.
	if (appState.showSettings && appState.credentialsLoaded) {
		PreferencesWindow(appState, settingsActions)
	}
	if (appState.showExport) {
		ExportWindow(appState, projectActions)
	}
}
