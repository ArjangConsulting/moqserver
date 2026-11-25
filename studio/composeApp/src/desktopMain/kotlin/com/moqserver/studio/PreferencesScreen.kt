package com.moqserver.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.moqserver.studio.data.AISettings
import com.moqserver.studio.data.HARImportSettings
import com.moqserver.studio.designsystem.toSharedThemeMode
import io.github.maniramezan.kmpcomponents.AdaptiveContentContainer
import io.github.maniramezan.kmpcomponents.CheckStatus
import io.github.maniramezan.kmpcomponents.CheckStatusLabels
import io.github.maniramezan.kmpcomponents.CheckStatusRow
import io.github.maniramezan.kmpcomponents.FormTextField
import io.github.maniramezan.kmpcomponents.PrimaryButton
import io.github.maniramezan.kmpcomponents.ProviderConfigurationError
import io.github.maniramezan.kmpcomponents.ProviderConfigurationField
import io.github.maniramezan.kmpcomponents.ProviderConfigurationState
import io.github.maniramezan.kmpcomponents.SecondaryButton
import io.github.maniramezan.kmpcomponents.SecretTextField
import io.github.maniramezan.kmpcomponents.SectionCard
import io.github.maniramezan.kmpcomponents.SegmentedControl
import io.github.maniramezan.kmpcomponents.SidebarItem
import io.github.maniramezan.kmpcomponents.SidebarNavigation
import io.github.maniramezan.kmpcomponents.ThemeModeLabels
import io.github.maniramezan.kmpcomponents.ThemeModeSelector
import io.github.maniramezan.kmpcomponents.ThemeSwitch
import io.github.maniramezan.kmpcomponents.ThemeVerticalDivider
import io.github.maniramezan.kmpcomponents.isValid
import io.github.maniramezan.kmpcomponents.validate

private object PreferencesStrings {
	const val TITLE = "Preferences"
	const val SAVE_AI = "Save AI Settings"
	const val GENERAL = "General"
	const val AI = "AI"
	const val APPEARANCE = "Appearance"
	const val COLOR_SCHEME = "Color scheme"
	const val REQUIRED = "Required"
	const val INVALID_URL = "Enter a URL starting with http:// or https://"
	const val COLOR_SCHEME_HELP = "Choose how moq studio looks by default."
	const val SYSTEM = "System"
	const val LIGHT = "Light"
	const val DARK = "Dark"
	const val AI_TITLE = "AI Providers"
	const val AI_SUBTITLE = "Configure the provider used for AI-assisted variant, body, and error-case generation."
	const val OLLAMA_TITLE = "Ollama"
	const val OLLAMA_SUBTITLE = "Local - no API key required"
	const val OLLAMA_HINT = "Tip: use a model tag installed locally, such as llama3.2:latest."
	const val OPENAI_TITLE = "OpenAI"
	const val OPENAI_SUBTITLE = "Hosted - requires API key"
	const val OPENAI_HINT = "Recommended default: gpt-6.1-sol."
	const val ANTHROPIC_TITLE = "Anthropic (Claude)"
	const val ANTHROPIC_SUBTITLE = "Hosted - requires API key"
	const val ANTHROPIC_HINT = "Recommended default: claude-sonnet-5-5."
	const val GEMINI_TITLE = "Google Gemini"
	const val GEMINI_SUBTITLE = "Hosted - requires API key"
	const val GEMINI_HINT = "Recommended default: gemini-3.8-flash."
	const val BASE_URL = "Base URL"
	const val DEFAULT_MODEL = "Default Model"
	const val API_KEY = "API Key"
	const val TEST_CONNECTION = "Test Connection"
	const val TEST_CONNECTION_DISABLED = "Complete the required fields to test this provider."
	const val NOT_TESTED = "Not tested yet"
	const val TESTING = "Checking connection..."
	const val TEST_CONNECTION_HELP = "Tests the current unsaved settings for this provider."
	const val SHOW = "Show"
	const val HIDE = "Hide"
	const val HAR_IMPORT = "HAR import"
	const val HAR_IMPORT_HELP =
		"Well-known credential names and the project's redact list are always redacted on import. " +
			"Add names your API uses here."
	const val REDACT_HEADERS = "Also redact these headers (comma-separated)"
	const val REDACT_QUERY = "Also redact these query parameters and body fields (comma-separated)"
	const val INFER_RULES = "Require the headers every call sent"
	const val INFER_RULES_TOOLTIP =
		"Adds presence-only request rules for credential and X- headers that every successful call sent."
	const val SAVE_HAR = "Save HAR Import Settings"
}

private enum class PreferencesSection { GENERAL, AI }

enum class AIProviderTab(val id: String, val label: String) {
	OLLAMA("ollama", PreferencesStrings.OLLAMA_TITLE),
	OPENAI("openai", PreferencesStrings.OPENAI_TITLE),
	ANTHROPIC("anthropic", PreferencesStrings.ANTHROPIC_TITLE),
	GEMINI("gemini", PreferencesStrings.GEMINI_TITLE),
}

data class PreferencesState(val themeMode: StudioThemeMode, val aiSettings: AISettings)

@Composable
fun PreferencesScreen(
	state: PreferencesState,
	onThemeModeChange: (StudioThemeMode) -> Unit,
	onSaveAISettings: (AISettings) -> Unit,
	onTestAIProvider: (String, AISettings, (CheckStatus) -> Unit) -> Unit,
	modifier: Modifier = Modifier,
	onSaveHARImport: (HARImportSettings) -> Unit = {},
) {
	var selectedSection by remember { mutableStateOf(PreferencesSection.GENERAL) }
	var selectedProvider by remember(state.aiSettings.selectedProviderId) {
		mutableStateOf(
			AIProviderTab.entries.firstOrNull { it.id == state.aiSettings.selectedProviderId } ?: AIProviderTab.OLLAMA,
		)
	}
	var draft by remember(state.aiSettings) { mutableStateOf(state.aiSettings) }
	var connectionStatus by remember(draft, selectedProvider) { mutableStateOf<CheckStatus>(CheckStatus.Idle) }
	var connectionGeneration by remember { mutableIntStateOf(0) }

	Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
		Row(Modifier.fillMaxSize()) {
			SidebarNavigation(
				modifier = Modifier.width(StudioDimens.navigationSidebarWidth).fillMaxHeight(),
				header = { Text(PreferencesStrings.TITLE, style = MaterialTheme.typography.titleMedium) },
			) {
				PreferencesSection.entries.forEach { section ->
					SidebarItem(
						label = if (section == PreferencesSection.GENERAL) PreferencesStrings.GENERAL else PreferencesStrings.AI,
						selected = selectedSection == section,
						onClick = {
							selectedSection = section
							connectionGeneration++
							connectionStatus = CheckStatus.Idle
						},
					)
				}
			}
			ThemeVerticalDivider(Modifier.fillMaxHeight())
			Box(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(StudioDimens.xl)) {
				AdaptiveContentContainer {
					when (selectedSection) {
						PreferencesSection.GENERAL -> GeneralPreferencesPane(
							themeMode = state.themeMode,
							onThemeModeChange = onThemeModeChange,
							harImport = state.aiSettings.harImport,
							onSaveHARImport = onSaveHARImport,
						)
						PreferencesSection.AI -> AIPreferencesPane(
							selectedProvider = selectedProvider,
							onSelectProvider = {
								selectedProvider = it
								connectionGeneration++
							},
							draft = draft,
							onDraftChange = {
								draft = it
								connectionGeneration++
							},
							connectionStatus = connectionStatus,
							onTestConnection = {
								val generation = ++connectionGeneration
								onTestAIProvider(selectedProvider.id, draft) {
									if (generation == connectionGeneration) connectionStatus = it
								}
							},
							onSave = { onSaveAISettings(draft.copy(selectedProviderId = selectedProvider.id)) },
						)
					}
				}
			}
		}
	}
}

@Composable
private fun GeneralPreferencesPane(
	themeMode: StudioThemeMode,
	onThemeModeChange: (StudioThemeMode) -> Unit,
	harImport: HARImportSettings,
	onSaveHARImport: (HARImportSettings) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.l)) {
		SectionCard(title = PreferencesStrings.APPEARANCE, description = PreferencesStrings.COLOR_SCHEME_HELP) {
			Text(PreferencesStrings.COLOR_SCHEME, style = MaterialTheme.typography.labelLarge)
			ThemeModeSelector(
				selected = themeMode.toSharedThemeMode(),
				onSelect = { onThemeModeChange(StudioThemeMode.valueOf(it.name)) },
				labels = ThemeModeLabels(PreferencesStrings.SYSTEM, PreferencesStrings.LIGHT, PreferencesStrings.DARK),
			)
		}
		HARImportSection(harImport, onSaveHARImport)
	}
}

@Composable
private fun HARImportSection(saved: HARImportSettings, onSave: (HARImportSettings) -> Unit) {
	var redactText by remember(saved) { mutableStateOf(saved.redactHeaders.joinToString(", ")) }
	var redactQueryText by remember(saved) { mutableStateOf(saved.redactQueryParameters.joinToString(", ")) }
	var inferRules by remember(saved) { mutableStateOf(saved.inferHeaderRules) }
	val draft = HARImportSettings(
		redactHeaders = parseHeaderNames(redactText),
		inferHeaderRules = inferRules,
		redactQueryParameters = parseHeaderNames(redactQueryText),
	)
	SectionCard(title = PreferencesStrings.HAR_IMPORT, description = PreferencesStrings.HAR_IMPORT_HELP) {
		FormTextField(
			label = PreferencesStrings.REDACT_HEADERS,
			value = redactText,
			onValueChange = { redactText = it },
			singleLine = true,
			modifier = Modifier.fillMaxWidth(),
		)
		FormTextField(
			label = PreferencesStrings.REDACT_QUERY,
			value = redactQueryText,
			onValueChange = { redactQueryText = it },
			singleLine = true,
			modifier = Modifier.fillMaxWidth(),
		)
		ThemeSwitch(
			checked = inferRules,
			onCheckedChange = { inferRules = it },
			label = PreferencesStrings.INFER_RULES,
			tooltip = PreferencesStrings.INFER_RULES_TOOLTIP,
		)
		PrimaryButton(text = PreferencesStrings.SAVE_HAR, enabled = draft != saved, onClick = { onSave(draft) })
	}
}

/** Splits a comma- or whitespace-separated list of header names, dropping blanks and duplicates. */
internal fun parseHeaderNames(text: String): List<String> =
	text.split(',', ' ', '\n', '\t').map(String::trim).filter(String::isNotEmpty).distinctBy(String::lowercase)

@Composable
private fun AIPreferencesPane(
	selectedProvider: AIProviderTab,
	onSelectProvider: (AIProviderTab) -> Unit,
	draft: AISettings,
	onDraftChange: (AISettings) -> Unit,
	connectionStatus: CheckStatus,
	onTestConnection: () -> Unit,
	onSave: () -> Unit,
) {
	val configuration = selectedProvider.configuration(draft)
	val apiKeyRequired = selectedProvider != AIProviderTab.OLLAMA
	val canTest = configuration.isValid(apiKeyRequired)
	Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.xl)) {
		Text(PreferencesStrings.AI_TITLE, style = MaterialTheme.typography.headlineSmall)
		Text(PreferencesStrings.AI_SUBTITLE, style = MaterialTheme.typography.bodyMedium)
		SegmentedControl(
			options = AIProviderTab.entries.map { it.label },
			selectedIndex = selectedProvider.ordinal,
			onOptionSelected = { onSelectProvider(AIProviderTab.entries[it]) },
		)
		SectionCard(
			title = selectedProvider.label,
			description = if (apiKeyRequired) PreferencesStrings.OPENAI_SUBTITLE else PreferencesStrings.OLLAMA_SUBTITLE,
		) {
			key(selectedProvider) {
				ProviderFields(
					configuration = configuration,
					apiKeyRequired = apiKeyRequired,
					onChange = { onDraftChange(draft.withProviderConfiguration(selectedProvider, it)) },
				)
			}
			Text(providerHint(selectedProvider), style = MaterialTheme.typography.bodySmall)
			Text(PreferencesStrings.TEST_CONNECTION_HELP, style = MaterialTheme.typography.bodySmall)
			CheckStatusRow(
				status = connectionStatus,
				labels = CheckStatusLabels(PreferencesStrings.NOT_TESTED, PreferencesStrings.TESTING),
			)
			SecondaryButton(
				text = PreferencesStrings.TEST_CONNECTION,
				onClick = onTestConnection,
				enabled = canTest && connectionStatus != CheckStatus.Checking,
			)
			if (!canTest) Text(PreferencesStrings.TEST_CONNECTION_DISABLED, style = MaterialTheme.typography.bodySmall)
		}
		PrimaryButton(text = PreferencesStrings.SAVE_AI, onClick = onSave)
	}
}

@Composable
private fun ProviderFields(
	configuration: ProviderConfigurationState,
	apiKeyRequired: Boolean,
	onChange: (ProviderConfigurationState) -> Unit,
) {
	val errors = configuration.validate(apiKeyRequired)
	FormTextField(
		value = configuration.baseUrl,
		onValueChange = { onChange(configuration.copy(baseUrl = it)) },
		label = PreferencesStrings.BASE_URL,
		modifier = Modifier.fillMaxWidth(),
		errorText = errors[ProviderConfigurationField.BASE_URL]?.let(::providerErrorLabel),
	)
	FormTextField(
		value = configuration.model,
		onValueChange = { onChange(configuration.copy(model = it)) },
		label = PreferencesStrings.DEFAULT_MODEL,
		modifier = Modifier.fillMaxWidth(),
		errorText = errors[ProviderConfigurationField.MODEL]?.let(::providerErrorLabel),
	)
	if (apiKeyRequired) {
		SecretTextField(
			value = configuration.apiKey,
			onValueChange = { onChange(configuration.copy(apiKey = it)) },
			label = PreferencesStrings.API_KEY,
			modifier = Modifier.fillMaxWidth(),
			showLabel = PreferencesStrings.SHOW,
			hideLabel = PreferencesStrings.HIDE,
		)
	}
}

private fun providerErrorLabel(error: ProviderConfigurationError): String = when (error) {
	ProviderConfigurationError.REQUIRED -> PreferencesStrings.REQUIRED
	ProviderConfigurationError.INVALID_URL -> PreferencesStrings.INVALID_URL
}

private fun providerHint(provider: AIProviderTab): String = when (provider) {
	AIProviderTab.OLLAMA -> PreferencesStrings.OLLAMA_HINT
	AIProviderTab.OPENAI -> PreferencesStrings.OPENAI_HINT
	AIProviderTab.ANTHROPIC -> PreferencesStrings.ANTHROPIC_HINT
	AIProviderTab.GEMINI -> PreferencesStrings.GEMINI_HINT
}
