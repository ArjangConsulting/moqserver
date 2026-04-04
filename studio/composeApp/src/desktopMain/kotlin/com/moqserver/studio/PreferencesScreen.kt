package com.moqserver.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.moqserver.studio.data.AISettings
import com.moqserver.studio.data.AnthropicSettings
import com.moqserver.studio.data.GeminiSettings
import com.moqserver.studio.data.OllamaSettings
import com.moqserver.studio.data.OpenAISettings

private object PreferencesStrings {
	const val TITLE = "Preferences"
	const val SAVE_AI = "Save AI Settings"
	const val GENERAL = "General"
	const val AI = "AI"
	const val APPEARANCE = "Appearance"
	const val COLOR_SCHEME = "Color scheme"
	const val COLOR_SCHEME_HELP = "Choose how moq studio looks by default."
	const val SYSTEM = "System"
	const val LIGHT = "Light"
	const val DARK = "Dark"
	const val AI_TITLE = "AI Providers"
	const val AI_SUBTITLE = "Configure local and hosted AI providers used for variants and body generation."
	const val OLLAMA_TITLE = "Ollama"
	const val OLLAMA_SUBTITLE = "Local - no API key required"
	const val OPENAI_TITLE = "OpenAI"
	const val OPENAI_SUBTITLE = "Hosted - requires API key"
	const val ANTHROPIC_TITLE = "Anthropic (Claude)"
	const val ANTHROPIC_SUBTITLE = "Hosted - requires API key"
	const val GEMINI_TITLE = "Google Gemini"
	const val GEMINI_SUBTITLE = "Hosted - requires API key"
	const val BASE_URL = "Base URL"
	const val DEFAULT_MODEL = "Default Model"
	const val API_KEY = "API Key"
	const val TEST_CONNECTION = "Test Connection"
	const val NOT_TESTED = "Not tested yet"
	const val TESTING = "Checking connection..."
	const val CONNECTION_OK = "Connection looks good"
	const val SHOW = "Show"
	const val HIDE = "Hide"
}

private enum class PreferencesSection {
	GENERAL,
	AI,
}

enum class AIProviderTab(
	val id: String,
	val label: String,
) {
	OLLAMA("ollama", PreferencesStrings.OLLAMA_TITLE),
	OPENAI("openai", PreferencesStrings.OPENAI_TITLE),
	ANTHROPIC("anthropic", PreferencesStrings.ANTHROPIC_TITLE),
	GEMINI("gemini", PreferencesStrings.GEMINI_TITLE),
}

sealed interface ProviderConnectionStatus {
	data object Idle : ProviderConnectionStatus
	data object Checking : ProviderConnectionStatus
	data class Success(val message: String) : ProviderConnectionStatus
	data class Failure(val message: String) : ProviderConnectionStatus
}

data class PreferencesState(
	val themeMode: StudioThemeMode,
	val aiSettings: AISettings,
)

@Composable
fun PreferencesScreen(
	state: PreferencesState,
	onThemeModeChange: (StudioThemeMode) -> Unit,
	onSaveAISettings: (AISettings) -> Unit,
	onTestAIProvider: (String, AISettings, (ProviderConnectionStatus) -> Unit) -> Unit,
	modifier: Modifier = Modifier,
) {
	var selectedSection by remember { mutableStateOf(PreferencesSection.GENERAL) }
	var selectedProviderTab by remember { mutableStateOf(AIProviderTab.OLLAMA) }
	var ollama by remember(state) { mutableStateOf(state.aiSettings.ollama) }
	var openai by remember(state) { mutableStateOf(state.aiSettings.openai) }
	var anthropic by remember(state) { mutableStateOf(state.aiSettings.anthropic) }
	var gemini by remember(state) { mutableStateOf(state.aiSettings.gemini) }
	var connectionStatus by remember { mutableStateOf<ProviderConnectionStatus>(ProviderConnectionStatus.Idle) }

	Surface(
		modifier = modifier.fillMaxSize(),
		color = MaterialTheme.colorScheme.background,
	) {
		Row(modifier = Modifier.fillMaxSize()) {
			PreferencesSidebar(
				selectedSection = selectedSection,
				onSelect = {
					selectedSection = it
					connectionStatus = ProviderConnectionStatus.Idle
				},
			)
			HorizontalDivider(modifier = Modifier.fillMaxHeight().width(1.dp))
			Box(
				modifier = Modifier
					.weight(1f)
					.fillMaxHeight()
					.verticalScroll(rememberScrollState())
					.padding(StudioDimens.xxxl),
			) {
				when (selectedSection) {
					PreferencesSection.GENERAL -> GeneralPreferencesPane(
						themeMode = state.themeMode,
						onThemeModeChange = onThemeModeChange,
					)

					PreferencesSection.AI -> {
						val unsavedAISettings = state.aiSettings.copy(
							ollama = ollama,
							openai = openai,
							anthropic = anthropic,
							gemini = gemini,
						)

						AIPreferencesPane(
							selectedProviderTab = selectedProviderTab,
							onSelectProviderTab = {
								selectedProviderTab = it
								connectionStatus = ProviderConnectionStatus.Idle
							},
							ollama = ollama,
							onOllamaChange = {
								ollama = it
								connectionStatus = ProviderConnectionStatus.Idle
							},
							openai = openai,
							onOpenAIChange = {
								openai = it
								connectionStatus = ProviderConnectionStatus.Idle
							},
							anthropic = anthropic,
							onAnthropicChange = {
								anthropic = it
								connectionStatus = ProviderConnectionStatus.Idle
							},
							gemini = gemini,
							onGeminiChange = {
								gemini = it
								connectionStatus = ProviderConnectionStatus.Idle
							},
							connectionStatus = connectionStatus,
							onTestConnection = {
								onTestAIProvider(selectedProviderTab.id, unsavedAISettings) {
									connectionStatus = it
								}
							},
							onSave = { onSaveAISettings(unsavedAISettings) },
						)
					}
				}
			}
		}
	}
}

@Composable
private fun PreferencesSidebar(
	selectedSection: PreferencesSection,
	onSelect: (PreferencesSection) -> Unit,
) {
	Column(
		modifier = Modifier
			.width(220.dp)
			.fillMaxHeight()
			.padding(StudioDimens.l),
		verticalArrangement = Arrangement.spacedBy(StudioDimens.xs),
	) {
		Text(
			text = PreferencesStrings.TITLE,
			style = MaterialTheme.typography.titleMedium,
			fontWeight = FontWeight.SemiBold,
			modifier = Modifier.padding(horizontal = StudioDimens.m, vertical = StudioDimens.s),
		)
		SidebarItem(
			label = PreferencesStrings.GENERAL,
			selected = selectedSection == PreferencesSection.GENERAL,
			onClick = { onSelect(PreferencesSection.GENERAL) },
		)
		SidebarItem(
			label = PreferencesStrings.AI,
			selected = selectedSection == PreferencesSection.AI,
			onClick = { onSelect(PreferencesSection.AI) },
		)
	}
}

@Composable
private fun SidebarItem(
	label: String,
	selected: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val containerColor = if (selected) {
		MaterialTheme.colorScheme.secondaryContainer
	} else {
		MaterialTheme.colorScheme.surface
	}
	val textColor = if (selected) {
		MaterialTheme.colorScheme.onSecondaryContainer
	} else {
		MaterialTheme.colorScheme.onSurface
	}

	Box(
		modifier = modifier
			.fillMaxWidth()
			.background(containerColor, RoundedCornerShape(10.dp))
			.clickable(onClick = onClick)
			.padding(horizontal = StudioDimens.m, vertical = StudioDimens.l),
	) {
		Text(label, color = textColor, style = MaterialTheme.typography.bodyMedium)
	}
}

@Composable
private fun GeneralPreferencesPane(
	themeMode: StudioThemeMode,
	onThemeModeChange: (StudioThemeMode) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.xl)) {
		Text(PreferencesStrings.GENERAL, style = MaterialTheme.typography.headlineSmall)
		Card(modifier = Modifier.fillMaxWidth()) {
			Column(
				modifier = Modifier.padding(StudioDimens.xl),
				verticalArrangement = Arrangement.spacedBy(StudioDimens.l),
			) {
				Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.xxs)) {
					Text(PreferencesStrings.APPEARANCE, style = MaterialTheme.typography.titleMedium)
					Text(
						PreferencesStrings.COLOR_SCHEME_HELP,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				ThemeModeDropdown(themeMode = themeMode, onThemeModeChange = onThemeModeChange)
			}
		}
	}
}

@Composable
private fun ThemeModeDropdown(
	themeMode: StudioThemeMode,
	onThemeModeChange: (StudioThemeMode) -> Unit,
) {
	var expanded by remember { mutableStateOf(false) }

	Box {
		OutlinedTextField(
			value = themeModeLabel(themeMode),
			onValueChange = {},
			label = { Text(PreferencesStrings.COLOR_SCHEME) },
			modifier = Modifier.fillMaxWidth(),
			readOnly = true,
		)
		Box(
			modifier = Modifier
				.matchParentSize()
				.clickable { expanded = true },
		)
		DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
			DropdownMenuItem(
				text = { Text(PreferencesStrings.SYSTEM) },
				onClick = {
					expanded = false
					onThemeModeChange(StudioThemeMode.SYSTEM)
				},
			)
			DropdownMenuItem(
				text = { Text(PreferencesStrings.LIGHT) },
				onClick = {
					expanded = false
					onThemeModeChange(StudioThemeMode.LIGHT)
				},
			)
			DropdownMenuItem(
				text = { Text(PreferencesStrings.DARK) },
				onClick = {
					expanded = false
					onThemeModeChange(StudioThemeMode.DARK)
				},
			)
		}
	}
}

@Composable
private fun AIPreferencesPane(
	selectedProviderTab: AIProviderTab,
	onSelectProviderTab: (AIProviderTab) -> Unit,
	ollama: OllamaSettings,
	onOllamaChange: (OllamaSettings) -> Unit,
	openai: OpenAISettings,
	onOpenAIChange: (OpenAISettings) -> Unit,
	anthropic: AnthropicSettings,
	onAnthropicChange: (AnthropicSettings) -> Unit,
	gemini: GeminiSettings,
	onGeminiChange: (GeminiSettings) -> Unit,
	connectionStatus: ProviderConnectionStatus,
	onTestConnection: () -> Unit,
	onSave: () -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.xl)) {
		Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.xxs)) {
			Text(PreferencesStrings.AI_TITLE, style = MaterialTheme.typography.headlineSmall)
			Text(
				PreferencesStrings.AI_SUBTITLE,
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}

		AIProviderTabs(
			selectedProviderTab = selectedProviderTab,
			onSelectProviderTab = onSelectProviderTab,
		)

		when (selectedProviderTab) {
			AIProviderTab.OLLAMA -> ProviderSettingsCard(
				title = PreferencesStrings.OLLAMA_TITLE,
				subtitle = PreferencesStrings.OLLAMA_SUBTITLE,
				connectionStatus = connectionStatus,
				onTestConnection = onTestConnection,
			) {
				OutlinedTextField(
					value = ollama.baseUrl,
					onValueChange = { onOllamaChange(ollama.copy(baseUrl = it)) },
					label = { Text(PreferencesStrings.BASE_URL) },
					modifier = Modifier.fillMaxWidth(),
					singleLine = true,
				)
				OutlinedTextField(
					value = ollama.defaultModel,
					onValueChange = { onOllamaChange(ollama.copy(defaultModel = it)) },
					label = { Text(PreferencesStrings.DEFAULT_MODEL) },
					modifier = Modifier.fillMaxWidth(),
					singleLine = true,
				)
			}

			AIProviderTab.OPENAI -> ProviderSettingsCard(
				title = PreferencesStrings.OPENAI_TITLE,
				subtitle = PreferencesStrings.OPENAI_SUBTITLE,
				connectionStatus = connectionStatus,
				onTestConnection = onTestConnection,
			) {
				ApiKeyField(value = openai.apiKey, onValueChange = { onOpenAIChange(openai.copy(apiKey = it)) })
				OutlinedTextField(
					value = openai.baseUrl,
					onValueChange = { onOpenAIChange(openai.copy(baseUrl = it)) },
					label = { Text(PreferencesStrings.BASE_URL) },
					modifier = Modifier.fillMaxWidth(),
					singleLine = true,
				)
				OutlinedTextField(
					value = openai.defaultModel,
					onValueChange = { onOpenAIChange(openai.copy(defaultModel = it)) },
					label = { Text(PreferencesStrings.DEFAULT_MODEL) },
					modifier = Modifier.fillMaxWidth(),
					singleLine = true,
				)
			}

			AIProviderTab.ANTHROPIC -> ProviderSettingsCard(
				title = PreferencesStrings.ANTHROPIC_TITLE,
				subtitle = PreferencesStrings.ANTHROPIC_SUBTITLE,
				connectionStatus = connectionStatus,
				onTestConnection = onTestConnection,
			) {
				ApiKeyField(value = anthropic.apiKey, onValueChange = { onAnthropicChange(anthropic.copy(apiKey = it)) })
				OutlinedTextField(
					value = anthropic.baseUrl,
					onValueChange = { onAnthropicChange(anthropic.copy(baseUrl = it)) },
					label = { Text(PreferencesStrings.BASE_URL) },
					modifier = Modifier.fillMaxWidth(),
					singleLine = true,
				)
				OutlinedTextField(
					value = anthropic.defaultModel,
					onValueChange = { onAnthropicChange(anthropic.copy(defaultModel = it)) },
					label = { Text(PreferencesStrings.DEFAULT_MODEL) },
					modifier = Modifier.fillMaxWidth(),
					singleLine = true,
				)
			}

			AIProviderTab.GEMINI -> ProviderSettingsCard(
				title = PreferencesStrings.GEMINI_TITLE,
				subtitle = PreferencesStrings.GEMINI_SUBTITLE,
				connectionStatus = connectionStatus,
				onTestConnection = onTestConnection,
			) {
				ApiKeyField(value = gemini.apiKey, onValueChange = { onGeminiChange(gemini.copy(apiKey = it)) })
				OutlinedTextField(
					value = gemini.baseUrl,
					onValueChange = { onGeminiChange(gemini.copy(baseUrl = it)) },
					label = { Text(PreferencesStrings.BASE_URL) },
					modifier = Modifier.fillMaxWidth(),
					singleLine = true,
				)
				OutlinedTextField(
					value = gemini.defaultModel,
					onValueChange = { onGeminiChange(gemini.copy(defaultModel = it)) },
					label = { Text(PreferencesStrings.DEFAULT_MODEL) },
					modifier = Modifier.fillMaxWidth(),
					singleLine = true,
				)
			}
		}

		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.End,
		) {
			Button(onClick = onSave) {
				Text(PreferencesStrings.SAVE_AI)
			}
		}
	}
}

@Composable
private fun AIProviderTabs(
	selectedProviderTab: AIProviderTab,
	onSelectProviderTab: (AIProviderTab) -> Unit,
) {
	Row(horizontalArrangement = Arrangement.spacedBy(StudioDimens.s)) {
		AIProviderTab.entries.forEach { providerTab ->
			SidebarItem(
				label = providerTab.label,
				selected = selectedProviderTab == providerTab,
				onClick = { onSelectProviderTab(providerTab) },
				modifier = Modifier.weight(1f),
			)
		}
	}
}

@Composable
private fun ApiKeyField(
	value: String,
	onValueChange: (String) -> Unit,
) {
	var revealValue by remember { mutableStateOf(false) }

	OutlinedTextField(
		value = value,
		onValueChange = onValueChange,
		label = { Text(PreferencesStrings.API_KEY) },
		modifier = Modifier.fillMaxWidth(),
		singleLine = true,
		visualTransformation = if (revealValue) VisualTransformation.None else PasswordVisualTransformation(),
		trailingIcon = {
			TextButton(onClick = { revealValue = !revealValue }) {
				Text(if (revealValue) PreferencesStrings.HIDE else PreferencesStrings.SHOW)
			}
		},
	)
}

@Composable
private fun ProviderSettingsCard(
	title: String,
	subtitle: String,
	connectionStatus: ProviderConnectionStatus,
	onTestConnection: () -> Unit,
	content: @Composable () -> Unit,
) {
	Card(modifier = Modifier.fillMaxWidth()) {
		Column(
			modifier = Modifier.padding(StudioDimens.xl),
			verticalArrangement = Arrangement.spacedBy(StudioDimens.l),
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.Top,
			) {
				Column(
					modifier = Modifier.weight(1f),
					verticalArrangement = Arrangement.spacedBy(StudioDimens.xxs),
				) {
					Text(title, style = MaterialTheme.typography.titleSmall)
					Text(
						subtitle,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					Text(
						text = connectionStatusLabel(connectionStatus),
						style = MaterialTheme.typography.bodySmall,
						color = connectionStatusColor(connectionStatus),
					)
				}
				Spacer(modifier = Modifier.width(StudioDimens.l))
				OutlinedButton(
					onClick = onTestConnection,
					enabled = connectionStatus !is ProviderConnectionStatus.Checking,
				) {
					Text(PreferencesStrings.TEST_CONNECTION)
				}
			}
			content()
		}
	}
}

private fun themeModeLabel(themeMode: StudioThemeMode): String = when (themeMode) {
	StudioThemeMode.SYSTEM -> PreferencesStrings.SYSTEM
	StudioThemeMode.LIGHT -> PreferencesStrings.LIGHT
	StudioThemeMode.DARK -> PreferencesStrings.DARK
}

private fun connectionStatusLabel(status: ProviderConnectionStatus): String = when (status) {
	ProviderConnectionStatus.Idle -> PreferencesStrings.NOT_TESTED
	ProviderConnectionStatus.Checking -> PreferencesStrings.TESTING
	is ProviderConnectionStatus.Success -> status.message
	is ProviderConnectionStatus.Failure -> status.message
}

@Composable
private fun connectionStatusColor(status: ProviderConnectionStatus) = when (status) {
	ProviderConnectionStatus.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
	ProviderConnectionStatus.Checking -> MaterialTheme.colorScheme.primary
	is ProviderConnectionStatus.Success -> StudioColors.success
	is ProviderConnectionStatus.Failure -> MaterialTheme.colorScheme.error
}
