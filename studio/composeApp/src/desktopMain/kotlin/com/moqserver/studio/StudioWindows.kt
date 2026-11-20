package com.moqserver.studio

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.rememberWindowState
import com.moqserver.studio.domain.ImportState
import com.moqserver.studio.domain.StudioRootViewModel
import com.moqserver.studio.domain.StudioState
import com.moqserver.studio.projectformat.MoqProject
import com.moqserver.studio.projectsettings.ProjectSettingsScreen

/** Modal review of an import or update-from-spec before it touches the project. */
@Composable
internal fun ImportReviewWindow(
	importState: ImportState,
	state: StudioState,
	themeMode: StudioThemeMode,
	viewModel: StudioRootViewModel,
	importActions: ImportActions,
	aiActions: AIActions,
) {
	DialogWindow(
		onCloseRequest = viewModel::cancelImport,
		title = if (importState.isUpdateMode) "Update Review" else "Import Review",
		state = rememberDialogState(
			width = StudioDimens.importReviewWindowWidth,
			height = StudioDimens.importReviewWindowHeight,
		),
		resizable = true,
	) {
		window.isModal = true
		StudioTheme(themeMode = themeMode) {
			Surface(modifier = Modifier.fillMaxSize()) {
				ImportReviewScreen(
					state = importState,
					aiProviders = state.ai.providers,
					aiProvidersLoading = state.ai.loading,
					canGenerateWithAi = state.ai.selectedProvider
						?.takeIf { it.available }
						?.capabilities
						?.contains("GENERATE_VARIANTS") == true,
					selectedAIProviderId = state.ai.selectedProviderId,
					aiProviderLabel = state.ai.selectedProvider?.displayName,
					onRefreshAIProviders = aiActions::requestRefreshProviders,
					onSelectAIProvider = viewModel::selectProvider,
					onToggleEndpoint = viewModel::toggleImportEndpoint,
					onSetGroupAccepted = { indices, accepted ->
						indices.forEach { index ->
							if (importState.entries[index].accepted != accepted) viewModel.toggleImportEndpoint(index)
						}
					},
					onSelectAll = { viewModel.setAllImportEndpoints(true) },
					onDeselectAll = { viewModel.setAllImportEndpoints(false) },
					onUpdateProjectName = viewModel::updateImportProjectName,
					onUpdateSelection = viewModel::updateImportSelection,
					onUpdateResponseName = viewModel::updateImportResponseName,
					onUpdateGeneratedResponseName = viewModel::updateGeneratedImportResponseName,
					onToggleGeneratedResponse = viewModel::toggleGeneratedImportResponse,
					onUpdateEndpointAIContextHint = viewModel::updateImportAIContextHint,
					onGenerateEndpointMocks = aiActions::generateImportMocks,
					onGenerateAllMocks = aiActions::generateAllImportMocks,
					onConfirm = importActions::confirmImport,
					onCancel = viewModel::cancelImport,
					modifier = Modifier.fillMaxSize(),
				)
			}
		}
	}
}

/**
 * Non-modal editor for `project.yml`. Edits go through the view model like endpoint edits, so they
 * mark the project dirty and take part in undo and redo.
 */
@Composable
internal fun ProjectSettingsWindow(
	project: MoqProject,
	appState: StudioAppState,
	viewModel: StudioRootViewModel,
) {
	Window(
		onCloseRequest = { appState.showProjectSettings = false },
		title = "Project Settings — ${project.manifest.name}",
		state = rememberWindowState(
			width = StudioDimens.projectSettingsWindowWidth,
			height = StudioDimens.projectSettingsWindowHeight,
		),
	) {
		StudioTheme(themeMode = appState.themeMode) {
			Surface(modifier = Modifier.fillMaxSize()) {
				ProjectSettingsScreen(
					manifest = project.manifest,
					endpoints = project.endpoints,
					onUpdateManifest = viewModel::updateManifest,
				)
			}
		}
	}
}

@Composable
internal fun PreferencesWindow(appState: StudioAppState, settingsActions: SettingsActions) {
	Window(
		onCloseRequest = { appState.showSettings = false },
		title = "Preferences",
		state = rememberWindowState(
			width = StudioDimens.preferencesWindowWidth,
			height = StudioDimens.preferencesWindowHeight,
		),
	) {
		StudioTheme(themeMode = appState.themeMode) {
			PreferencesScreen(
				state = PreferencesState(themeMode = appState.themeMode, aiSettings = appState.aiSettings),
				onThemeModeChange = settingsActions::saveThemeMode,
				onTestAIProvider = settingsActions::testAIProvider,
				onSaveAISettings = settingsActions::saveAISettings,
			)
		}
	}
}

@Composable
internal fun ExportWindow(appState: StudioAppState, projectActions: ProjectActions) {
	Window(
		onCloseRequest = { appState.showExport = false },
		title = "Generate Client Code",
		state = rememberWindowState(
			width = StudioDimens.exportWindowWidth,
			height = StudioDimens.exportWindowHeight,
		),
	) {
		StudioTheme(themeMode = appState.themeMode) {
			ExportReferencesScreen(
				state = appState.exportState,
				onStateChange = { appState.exportState = it },
				onChooseFolder = projectActions::requestExportDestination,
				onExport = projectActions::requestExport,
				onCancel = { appState.showExport = false },
			)
		}
	}
}

@Composable
internal fun ImportFromURLHost(appState: StudioAppState, importActions: ImportActions) {
	val request = appState.importURLState
	ImportFromURLDialog(
		state = request,
		onUrlChange = { appState.importURLState = appState.importURLState.copy(url = it, error = null) },
		onAuthTypeChange = { appState.importURLState = appState.importURLState.copy(authType = it) },
		onBearerTokenChange = { appState.importURLState = appState.importURLState.copy(bearerToken = it) },
		onBasicUsernameChange = { appState.importURLState = appState.importURLState.copy(basicUsername = it) },
		onBasicPasswordChange = { appState.importURLState = appState.importURLState.copy(basicPassword = it) },
		onConfirm = importActions::executeURLImport,
		onDismiss = appState::dismissImportURLDialog,
	)
}
