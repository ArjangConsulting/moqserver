package com.moqserver.studio

import com.moqserver.studio.data.AISettings
import io.github.maniramezan.kmpcomponents.ProviderConfigurationState

internal fun AIProviderTab.configuration(settings: AISettings): ProviderConfigurationState = when (this) {
	AIProviderTab.OLLAMA -> ProviderConfigurationState(label, settings.ollama.baseUrl, settings.ollama.defaultModel)
	AIProviderTab.OPENAI -> ProviderConfigurationState(
		label,
		settings.openai.baseUrl,
		settings.openai.defaultModel,
		settings.openai.apiKey,
	)
	AIProviderTab.ANTHROPIC -> ProviderConfigurationState(
		label,
		settings.anthropic.baseUrl,
		settings.anthropic.defaultModel,
		settings.anthropic.apiKey,
	)
	AIProviderTab.GEMINI -> ProviderConfigurationState(
		label,
		settings.gemini.baseUrl,
		settings.gemini.defaultModel,
		settings.gemini.apiKey,
	)
}

internal fun AISettings.withProviderConfiguration(provider: AIProviderTab, configuration: ProviderConfigurationState): AISettings =
	when (provider) {
		AIProviderTab.OLLAMA -> copy(
			ollama = ollama.copy(baseUrl = configuration.baseUrl, defaultModel = configuration.model),
		)
		AIProviderTab.OPENAI -> copy(
			openai = openai.copy(
				baseUrl = configuration.baseUrl,
				defaultModel = configuration.model,
				apiKey = configuration.apiKey,
			),
		)
		AIProviderTab.ANTHROPIC -> copy(
			anthropic = anthropic.copy(
				baseUrl = configuration.baseUrl,
				defaultModel = configuration.model,
				apiKey = configuration.apiKey,
			),
		)
		AIProviderTab.GEMINI -> copy(
			gemini = gemini.copy(
				baseUrl = configuration.baseUrl,
				defaultModel = configuration.model,
				apiKey = configuration.apiKey,
			),
		)
	}
