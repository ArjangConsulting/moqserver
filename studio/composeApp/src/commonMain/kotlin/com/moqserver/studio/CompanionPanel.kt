package com.moqserver.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.moqserver.studio.domain.AIProviderInfo
import com.moqserver.studio.domain.AIState
import com.moqserver.studio.domain.ProviderKind

private object CompanionPanelStrings {
    const val CHECKING_PROVIDERS = "Checking providers..."
    const val AI_READY = "AI ready"
    const val AI_NOT_CONFIGURED = "AI not configured"
    const val NO_PROVIDER = "No provider available"
    const val CHECK = "Check"
    const val REFRESH = "Refresh"
    const val AI_PROVIDERS = "AI Providers"
    const val NO_PROVIDERS_CONFIGURED = "No providers configured"
    const val NO_PROVIDERS_HELP =
            "Open Settings to add API keys for OpenAI, Anthropic, or Google Gemini. " +
            "Ollama is available locally with no API key required."
    const val CHECK_OLLAMA = "Check Ollama"
    const val ERROR_CHECKING = "Error checking providers"
    const val RETRY = "Retry"
    const val LOCAL = "local"
    const val HOSTED = "hosted"
    const val LOCAL_TITLE = "Local"
    const val HOSTED_TITLE = "Hosted"
    const val SELECTED = "Selected"
    const val UNAVAILABLE = "Unavailable"
    const val CAP_ANALYZE = "Analyze"
    const val CAP_GENERATE = "Generate"
    const val CAP_REFINE = "Refine"
    const val CAP_ANALYZE_SPEC = "ANALYZE_SPEC"
    const val CAP_GENERATE_VARIANTS = "GENERATE_VARIANTS"
    const val CAP_REFINE_PROJECT = "REFINE_PROJECT"
}

@Composable
fun CompanionStatusBar(
    ai: AIState,
    onRefresh: () -> Unit,
    onSelectProvider: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = StudioDimens.xl, vertical = StudioDimens.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status dot + label
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StudioDimens.s),
        ) {
            Box(
                modifier = Modifier
                    .size(StudioDimens.statusDotSize)
                    .clip(CircleShape)
                    .background(
                        when {
                            ai.loading -> MaterialTheme.colorScheme.tertiary
                            ai.isReady -> StudioColors.success
                            ai.providers.isEmpty() && ai.error == null -> MaterialTheme.colorScheme.outline
                            else -> MaterialTheme.colorScheme.error
                        }
                    ),
            )
            Text(
                text = when {
                    ai.loading -> CompanionPanelStrings.CHECKING_PROVIDERS
                    ai.isReady -> CompanionPanelStrings.AI_READY
                    ai.providers.isEmpty() && ai.error == null -> CompanionPanelStrings.AI_NOT_CONFIGURED
                    else -> CompanionPanelStrings.NO_PROVIDER
                },
                style = MaterialTheme.typography.labelSmall,
            )

            if (ai.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(StudioDimens.smallSpinnerSize),
                    strokeWidth = StudioDimens.thinSpinnerStroke,
                )
            }
        }

        // Provider selector
        if (ai.availableProviders.isNotEmpty()) {
            ProviderSelector(
                providers = ai.availableProviders,
                selectedId = ai.selectedProviderId,
                onSelect = onSelectProvider,
            )
        }

        // Refresh button
        if (!ai.loading) {
            androidx.compose.material3.TextButton(onClick = onRefresh) {
                Text(
                    if (ai.providers.isEmpty() && ai.error == null) CompanionPanelStrings.CHECK else CompanionPanelStrings.REFRESH,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun ProviderSelector(
    providers: List<AIProviderInfo>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = providers.find { it.id == selectedId } ?: providers.first()

    Box {
        Text(
            text = selected.displayName,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .clip(RoundedCornerShape(StudioDimens.xs))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .clickable { expanded = true }
                .padding(horizontal = StudioDimens.m, vertical = StudioDimens.xs),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            providers.forEach { provider ->
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(StudioDimens.m),
                        ) {
                            Text(provider.displayName)
                            Text(
                                text = if (provider.kind == ProviderKind.LOCAL) {
                                    CompanionPanelStrings.LOCAL
                                } else {
                                    CompanionPanelStrings.HOSTED
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        onSelect(provider.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProviderSettingsPanel(
    ai: AIState,
    onRefresh: () -> Unit,
    onSelectProvider: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(StudioDimens.xxxl),
        verticalArrangement = Arrangement.spacedBy(StudioDimens.xl),
    ) {
        Text(CompanionPanelStrings.AI_PROVIDERS, style = MaterialTheme.typography.titleLarge)

        if (ai.providers.isEmpty() && !ai.loading && ai.error == null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(StudioDimens.xl), verticalArrangement = Arrangement.spacedBy(StudioDimens.m)) {
                    Text(CompanionPanelStrings.NO_PROVIDERS_CONFIGURED, style = MaterialTheme.typography.titleSmall)
                    Text(
                        CompanionPanelStrings.NO_PROVIDERS_HELP,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onRefresh) { Text(CompanionPanelStrings.CHECK_OLLAMA) }
                }
            }
            return
        }

        if (ai.error != null && ai.providers.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(StudioDimens.xl), verticalArrangement = Arrangement.spacedBy(StudioDimens.m)) {
                    Text(CompanionPanelStrings.ERROR_CHECKING, style = MaterialTheme.typography.titleSmall)
                    Text(
                        ai.error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onRefresh) { Text(CompanionPanelStrings.RETRY) }
                }
            }
            return
        }

        if (ai.loading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(StudioDimens.m),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(StudioDimens.smallIconSize),
                    strokeWidth = StudioDimens.spinnerStrokeWidth,
                )
                Text(CompanionPanelStrings.CHECKING_PROVIDERS, style = MaterialTheme.typography.bodyMedium)
            }
            return
        }

        // Provider list
        ai.providers.forEach { provider ->
            ProviderCard(
                provider = provider,
                isSelected = provider.id == ai.selectedProviderId,
                onSelect = { onSelectProvider(provider.id) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderCard(
    provider: AIProviderInfo,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = provider.available) { onSelect() },
    ) {
        Column(modifier = Modifier.padding(StudioDimens.xl), verticalArrangement = Arrangement.spacedBy(StudioDimens.m)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(StudioDimens.m),
                ) {
                    Box(
                        modifier = Modifier
                            .size(StudioDimens.statusDotSize)
                            .clip(CircleShape)
                            .background(
                                if (provider.available) StudioColors.success else MaterialTheme.colorScheme.error,
                            ),
                    )
                    Text(provider.displayName, style = MaterialTheme.typography.titleSmall)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(StudioDimens.m)) {
                    Text(
                        text = if (provider.kind == ProviderKind.LOCAL) {
                            CompanionPanelStrings.LOCAL_TITLE
                        } else {
                            CompanionPanelStrings.HOSTED_TITLE
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (isSelected) {
                        Text(
                            text = CompanionPanelStrings.SELECTED,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            if (provider.available) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(StudioDimens.s)) {
                    provider.capabilities.forEach { cap ->
                        Text(
                            text = capabilityLabel(cap),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .clip(RoundedCornerShape(StudioDimens.xs))
                                .background(MaterialTheme.colorScheme.tertiaryContainer)
                                .padding(horizontal = StudioDimens.s, vertical = StudioDimens.xxs),
                        )
                    }
                }
            } else {
                Text(
                    CompanionPanelStrings.UNAVAILABLE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun capabilityLabel(cap: String): String = when (cap) {
    CompanionPanelStrings.CAP_ANALYZE_SPEC -> CompanionPanelStrings.CAP_ANALYZE
    CompanionPanelStrings.CAP_GENERATE_VARIANTS -> CompanionPanelStrings.CAP_GENERATE
    CompanionPanelStrings.CAP_REFINE_PROJECT -> CompanionPanelStrings.CAP_REFINE
    else -> cap.lowercase().replace("_", " ")
}
