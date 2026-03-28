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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.moqserver.studio.domain.AIProviderInfo
import com.moqserver.studio.domain.AIState
import com.moqserver.studio.domain.ProviderKind

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
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status dot + label
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            ai.loading -> MaterialTheme.colorScheme.tertiary
                            ai.isReady -> Color(0xFF4CAF50)
                            ai.providers.isEmpty() && ai.error == null -> MaterialTheme.colorScheme.outline
                            else -> MaterialTheme.colorScheme.error
                        }
                    ),
            )
            Text(
                text = when {
                    ai.loading -> "Checking providers..."
                    ai.isReady -> "AI ready"
                    ai.providers.isEmpty() && ai.error == null -> "AI not configured"
                    else -> "No provider available"
                },
                style = MaterialTheme.typography.labelSmall,
            )

            if (ai.loading) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
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
                    if (ai.providers.isEmpty() && ai.error == null) "Check" else "Refresh",
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
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            providers.forEach { provider ->
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(provider.displayName)
                            Text(
                                text = if (provider.kind == ProviderKind.LOCAL) "local" else "hosted",
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
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("AI Providers", style = MaterialTheme.typography.titleLarge)

        if (ai.providers.isEmpty() && !ai.loading && ai.error == null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No providers configured", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Open Settings to add API keys for OpenAI, Anthropic, or Google Gemini. Ollama is available locally with no API key required.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onRefresh) { Text("Check Ollama") }
                }
            }
            return
        }

        if (ai.error != null && ai.providers.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Error checking providers", style = MaterialTheme.typography.titleSmall)
                    Text(
                        ai.error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onRefresh) { Text("Retry") }
                }
            }
            return
        }

        if (ai.loading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Checking providers...", style = MaterialTheme.typography.bodyMedium)
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
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (provider.available) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error),
                    )
                    Text(provider.displayName, style = MaterialTheme.typography.titleSmall)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (provider.kind == ProviderKind.LOCAL) "Local" else "Hosted",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (isSelected) {
                        Text(
                            text = "Selected",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            if (provider.available) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    provider.capabilities.forEach { cap ->
                        Text(
                            text = capabilityLabel(cap),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.tertiaryContainer)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            } else {
                Text(
                    "Unavailable",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun capabilityLabel(cap: String): String = when (cap) {
    "ANALYZE_SPEC" -> "Analyze"
    "GENERATE_VARIANTS" -> "Generate"
    "REFINE_PROJECT" -> "Refine"
    else -> cap.lowercase().replace("_", " ")
}
