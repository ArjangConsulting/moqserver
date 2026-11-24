package com.moqserver.studio

import com.moqserver.studio.data.AISettings
import com.moqserver.studio.data.HARImportSettings
import com.moqserver.studio.domain.VariantReferenceSyncPreference
import com.moqserver.studio.logging.loggerFor
import io.github.maniramezan.kmpcomponents.CheckStatus
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.swing.JOptionPane
import java.awt.Window as AwtWindow

/** Loads and saves preferences, and refreshes AI providers when their settings change. */
internal class SettingsActions(
	private val services: StudioServices,
	private val appState: StudioAppState,
	private val scope: CoroutineScope,
	private val handler: CoroutineExceptionHandler,
	private val window: AwtWindow,
) {
	private val logger = loggerFor<SettingsActions>()

	/**
	 * Reads API keys from the keychain off the main thread. The first frame renders from the
	 * settings file alone, so a slow or prompting keychain no longer delays the window.
	 */
	suspend fun loadCredentials() {
		val loaded = runOnIo(services.ioDispatcher) { services.settingsRepo.loadCredentials() }
		appState.aiSettings = loaded
		appState.credentialsLoaded = true
		logger.info("Loaded saved AI credentials")
	}

	fun saveThemeMode(themeMode: StudioThemeMode) {
		scope.launch(handler) {
			val settings = appState.aiSettings.copy(themeMode = themeMode.toThemePreference())
			if (!persist(settings)) return@launch
			logger.info("Theme preference saved")
			appState.themeMode = themeMode
			refreshProviders(settings)
		}
	}

	fun saveHARImportSettings(harImport: HARImportSettings) {
		scope.launch(handler) {
			if (persist(appState.aiSettings.copy(harImport = harImport))) logger.info("HAR import preferences saved")
		}
	}

	fun saveAISettings(updated: AISettings) {
		scope.launch(handler) {
			val settings = updated.copy(themeMode = appState.themeMode.toThemePreference())
			if (!persist(settings)) return@launch
			logger.info("Preferences saved")
			appState.showSettings = false
			refreshProviders(settings)
		}
	}

	fun saveVariantReferenceSyncPreference(projectPath: String, preference: VariantReferenceSyncPreference?) {
		scope.launch(handler) {
			val preferences = appState.aiSettings.variantReferenceSyncByProject.toMutableMap()
			if (preference == null) preferences.remove(projectPath) else preferences[projectPath] = preference
			persist(appState.aiSettings.copy(variantReferenceSyncByProject = preferences))
		}
	}

	fun testAIProvider(providerId: String, settings: AISettings, onStatus: (CheckStatus) -> Unit) {
		scope.launch(handler) {
			onStatus(CheckStatus.Checking)
			val provider = buildAIProviderForTesting(settings, providerId)
			if (provider == null) {
				onStatus(CheckStatus.Failure("Unknown AI provider."))
				return@launch
			}
			val issues = runOnIo(services.ioDispatcher) { provider.validateConfig() }
			onStatus(issues.firstOrNull()?.let { CheckStatus.Failure(it) } ?: CheckStatus.Success("Connection successful"))
		}
	}

	/** Writes [settings] and makes them current; false (after telling the user) when that failed. */
	private suspend fun persist(settings: AISettings): Boolean {
		if (!appState.credentialsLoaded) {
			showError("Saved API keys are still loading. Try again in a moment.")
			return false
		}
		try {
			runOnIo(services.ioDispatcher) { services.settingsRepo.save(settings) }
		} catch (e: Exception) {
			e.rethrowIfCancellation()
			logger.error("Failed to save preferences: {}", e.message)
			showError(e.message ?: "Failed to save preferences.")
			return false
		}
		appState.aiSettings = settings
		return true
	}

	private suspend fun refreshProviders(settings: AISettings) {
		refreshAIProviders(buildAIRegistry(settings), services.appViewModel, services.ioDispatcher)
	}

	private fun showError(message: String) {
		JOptionPane.showMessageDialog(window, message, "Preferences", JOptionPane.ERROR_MESSAGE)
	}
}
