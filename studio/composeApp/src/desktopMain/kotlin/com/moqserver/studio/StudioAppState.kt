package com.moqserver.studio

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.moqserver.studio.data.AISettings
import com.moqserver.studio.data.AISettingsRepository
import com.moqserver.studio.data.ImportHistoryRepository
import com.moqserver.studio.data.RecentProjectsRepository
import com.moqserver.studio.data.ThemePreference
import com.moqserver.studio.domain.StudioRootViewModel
import com.moqserver.studio.projectformat.ProjectRepository
import com.moqserver.studio.projectformat.format.FormatClient
import com.moqserver.studio.projectformat.format.FormatProcess
import kotlinx.coroutines.CoroutineDispatcher
import java.io.File

/** Long-lived collaborators the desktop shell wires together once per application. */
internal class StudioServices(
	val formatProcess: FormatProcess,
	val formatClient: FormatClient,
	val repo: ProjectRepository,
	val settingsRepo: AISettingsRepository,
	val recentProjectsRepo: RecentProjectsRepository,
	val importHistoryRepo: ImportHistoryRepository,
	val appViewModel: StudioRootViewModel,
	val ioDispatcher: CoroutineDispatcher,
)

/**
 * Desktop-shell UI state: which auxiliary windows are open, dialog drafts, and settings. Project
 * and workflow state stays in [StudioRootViewModel].
 */
internal class StudioAppState(initialSettings: AISettings, initialProjectPath: String?) {
	var aiSettings by mutableStateOf(initialSettings)

	/**
	 * False until API keys have been read from the keychain. Until then [aiSettings] holds blank
	 * keys, and saving it would erase the stored ones, so settings writes wait for this.
	 */
	var credentialsLoaded by mutableStateOf(false)
	var themeMode by mutableStateOf(initialSettings.themeMode.toStudioThemeMode())
	var showSettings by mutableStateOf(false)
	var showRuntimeInspector by mutableStateOf(false)
	var showProjectSettings by mutableStateOf(false)
	var showExport by mutableStateOf(false)
	var exportState by mutableStateOf(ExportReferencesState())
	var showImportURLDialog by mutableStateOf(false)
	var importURLState by mutableStateOf(ImportFromURLState())
	val lastFileDirectory: MutableState<String?> = mutableStateOf(null)

	var pendingProjectOpenPath by mutableStateOf(initialProjectPath)
		private set

	/**
	 * Keys the pending-open effect instead of the path itself. Keying on the path and clearing it on
	 * completion made the effect cancel itself: a write to its own key relaunches it on the next
	 * recomposition, and moq-format's IPC round trip was wide enough for that relaunch to land before
	 * openProject finished, silently abandoning the open.
	 */
	var pendingProjectOpenToken by mutableIntStateOf(0)
		private set

	fun requestProjectOpen(path: String) {
		pendingProjectOpenPath = path
		pendingProjectOpenToken++
	}

	fun rememberDirectoryOf(file: File) {
		lastFileDirectory.value = file.parentFile?.canonicalPath ?: lastFileDirectory.value
	}

	fun dismissImportURLDialog() {
		showImportURLDialog = false
		importURLState = ImportFromURLState()
	}
}

/** Commands the menu bar dispatches; grouped so the composable takes one parameter per concern. */
internal class StudioMenuCommands(
	val project: ProjectActions,
	val import: ImportActions,
	val appState: StudioAppState,
	val onUndo: () -> Unit,
	val onRedo: () -> Unit,
	val onRetryFormatService: () -> Unit,
	val onShowAbout: () -> Unit,
)

internal fun ThemePreference.toStudioThemeMode(): StudioThemeMode = when (this) {
	ThemePreference.SYSTEM -> StudioThemeMode.SYSTEM
	ThemePreference.LIGHT -> StudioThemeMode.LIGHT
	ThemePreference.DARK -> StudioThemeMode.DARK
}

internal fun StudioThemeMode.toThemePreference(): ThemePreference = when (this) {
	StudioThemeMode.SYSTEM -> ThemePreference.SYSTEM
	StudioThemeMode.LIGHT -> ThemePreference.LIGHT
	StudioThemeMode.DARK -> ThemePreference.DARK
}
