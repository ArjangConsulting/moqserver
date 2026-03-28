package com.moqserver.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.moqserver.studio.data.AISettings
import com.moqserver.studio.data.AnthropicSettings
import com.moqserver.studio.data.GeminiSettings
import com.moqserver.studio.data.OllamaSettings
import com.moqserver.studio.data.OpenAISettings

private object AISettingsStrings {
    const val AI_SETTINGS = "AI Settings"
    const val SAVE = "Save"
    const val OLLAMA_TITLE = "Ollama"
    const val OLLAMA_SUBTITLE = "Local — no API key required"
    const val OPENAI_TITLE = "OpenAI"
    const val OPENAI_SUBTITLE = "Hosted — requires API key"
    const val ANTHROPIC_TITLE = "Anthropic (Claude)"
    const val ANTHROPIC_SUBTITLE = "Hosted — requires API key"
    const val GEMINI_TITLE = "Google Gemini"
    const val GEMINI_SUBTITLE = "Hosted — requires API key"
    const val BASE_URL = "Base URL"
    const val DEFAULT_MODEL = "Default Model"
    const val API_KEY = "API Key"
    const val SHOW = "Show"
    const val HIDE = "Hide"
}

@Composable
fun AISettingsScreen(
    settings: AISettings,
    onSave: (AISettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    var ollama by remember(settings) { mutableStateOf(settings.ollama) }
    var openai by remember(settings) { mutableStateOf(settings.openai) }
    var anthropic by remember(settings) { mutableStateOf(settings.anthropic) }
    var gemini by remember(settings) { mutableStateOf(settings.gemini) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(StudioDimens.xxxl),
            verticalArrangement = Arrangement.spacedBy(StudioDimens.xxl),
        ) {
            Text(AISettingsStrings.AI_SETTINGS, style = MaterialTheme.typography.headlineSmall)

            OllamaSettingsCard(
                settings = ollama,
                onChange = { ollama = it },
            )

            OpenAISettingsCard(
                settings = openai,
                onChange = { openai = it },
            )

            AnthropicSettingsCard(
                settings = anthropic,
                onChange = { anthropic = it },
            )

            GeminiSettingsCard(
                settings = gemini,
                onChange = { gemini = it },
            )

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    onClick = {
                        onSave(
                            settings.copy(
                                ollama = ollama,
                                openai = openai,
                                anthropic = anthropic,
                                gemini = gemini,
                            )
                        )
                    }
                ) {
                    Text(AISettingsStrings.SAVE)
                }
            }
        }
    }
}

@Composable
private fun OllamaSettingsCard(
    settings: OllamaSettings,
    onChange: (OllamaSettings) -> Unit,
) {
    ProviderSettingsCard(title = AISettingsStrings.OLLAMA_TITLE, subtitle = AISettingsStrings.OLLAMA_SUBTITLE) {
        OutlinedTextField(
            value = settings.baseUrl,
            onValueChange = { onChange(settings.copy(baseUrl = it)) },
            label = { Text(AISettingsStrings.BASE_URL) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = settings.defaultModel,
            onValueChange = { onChange(settings.copy(defaultModel = it)) },
            label = { Text(AISettingsStrings.DEFAULT_MODEL) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
    }
}

@Composable
private fun OpenAISettingsCard(
    settings: OpenAISettings,
    onChange: (OpenAISettings) -> Unit,
) {
    ProviderSettingsCard(title = AISettingsStrings.OPENAI_TITLE, subtitle = AISettingsStrings.OPENAI_SUBTITLE) {
        ApiKeyField(
            value = settings.apiKey,
            onValueChange = { onChange(settings.copy(apiKey = it)) },
        )
        OutlinedTextField(
            value = settings.baseUrl,
            onValueChange = { onChange(settings.copy(baseUrl = it)) },
            label = { Text(AISettingsStrings.BASE_URL) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = settings.defaultModel,
            onValueChange = { onChange(settings.copy(defaultModel = it)) },
            label = { Text(AISettingsStrings.DEFAULT_MODEL) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
    }
}

@Composable
private fun AnthropicSettingsCard(
    settings: AnthropicSettings,
    onChange: (AnthropicSettings) -> Unit,
) {
    ProviderSettingsCard(title = AISettingsStrings.ANTHROPIC_TITLE, subtitle = AISettingsStrings.ANTHROPIC_SUBTITLE) {
        ApiKeyField(
            value = settings.apiKey,
            onValueChange = { onChange(settings.copy(apiKey = it)) },
        )
        OutlinedTextField(
            value = settings.baseUrl,
            onValueChange = { onChange(settings.copy(baseUrl = it)) },
            label = { Text(AISettingsStrings.BASE_URL) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = settings.defaultModel,
            onValueChange = { onChange(settings.copy(defaultModel = it)) },
            label = { Text(AISettingsStrings.DEFAULT_MODEL) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
    }
}

@Composable
private fun GeminiSettingsCard(
    settings: GeminiSettings,
    onChange: (GeminiSettings) -> Unit,
) {
    ProviderSettingsCard(title = AISettingsStrings.GEMINI_TITLE, subtitle = AISettingsStrings.GEMINI_SUBTITLE) {
        ApiKeyField(
            value = settings.apiKey,
            onValueChange = { onChange(settings.copy(apiKey = it)) },
        )
        OutlinedTextField(
            value = settings.baseUrl,
            onValueChange = { onChange(settings.copy(baseUrl = it)) },
            label = { Text(AISettingsStrings.BASE_URL) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = settings.defaultModel,
            onValueChange = { onChange(settings.copy(defaultModel = it)) },
            label = { Text(AISettingsStrings.DEFAULT_MODEL) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
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
        label = { Text(AISettingsStrings.API_KEY) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (revealValue) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = { revealValue = !revealValue }) {
                Text(if (revealValue) AISettingsStrings.HIDE else AISettingsStrings.SHOW)
            }
        },
    )
}

@Composable
private fun ProviderSettingsCard(
    title: String,
    subtitle: String,
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
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            content()
        }
    }
}
