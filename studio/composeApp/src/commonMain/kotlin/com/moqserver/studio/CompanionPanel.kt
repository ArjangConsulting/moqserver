package com.moqserver.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import com.moqserver.studio.domain.AICapability
import com.moqserver.studio.domain.CompanionProvider
import com.moqserver.studio.domain.CompanionState
import com.moqserver.studio.domain.ProviderKind

@Composable
fun CompanionStatusBar(
    companion: CompanionState,
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
        // Connection status dot + label
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
                            companion.checking -> MaterialTheme.colorScheme.tertiary
                            companion.connected -> Color(0xFF4CAF50)
                            companion.isIdle -> MaterialTheme.colorScheme.outline
                            else -> MaterialTheme.colorScheme.error
                        }
                    ),
            )
            Text(
                text = when {
                    companion.checking -> "Connecting..."
                    companion.connected -> "AI companion"
                    companion.isIdle -> "AI companion idle"
                    else -> "AI companion offline"
                },
                style = MaterialTheme.typography.labelSmall,
            )

            if (companion.checking) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
            }
        }

        // Provider selector
        if (companion.connected && companion.availableProviders.isNotEmpty()) {
            ProviderSelector(
                providers = companion.availableProviders,
                selectedId = companion.selectedProviderId,
                onSelect = onSelectProvider,
            )
        }

        // Refresh button
        if (!companion.checking) {
            androidx.compose.material3.TextButton(onClick = onRefresh) {
                Text(
                    if (companion.isIdle) "Connect" else "Refresh",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun ProviderSelector(
    providers: List<CompanionProvider>,
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
    companion: CompanionState,
    onRefresh: () -> Unit,
    onSelectProvider: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("AI Companion", style = MaterialTheme.typography.titleLarge)

        if (companion.isIdle) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("AI companion is optional", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Studio does not contact the local Swift companion until you open AI features. When you want AI assistance, start it on 127.0.0.1:8081 with: swift run moqserver companion --port 8081",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onRefresh) { Text("Connect") }
                }
            }
            return
        }

        if (!companion.connected && !companion.checking) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Companion Not Connected", style = MaterialTheme.typography.titleSmall)
                    Text(
                        companion.error ?: "Start the local Swift companion: swift run moqserver companion --port 8081",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onRefresh) { Text("Retry") }
                }
            }
            return
        }

        if (companion.checking) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Connecting to companion...", style = MaterialTheme.typography.bodyMedium)
            }
            return
        }

        // Provider list
        companion.providers.forEach { provider ->
            ProviderCard(
                provider = provider,
                isSelected = provider.id == companion.selectedProviderId,
                onSelect = { onSelectProvider(provider.id) },
            )
        }

        if (companion.providers.isEmpty()) {
            Text(
                "No providers configured. Check companion server logs.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderCard(
    provider: CompanionProvider,
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

private fun capabilityLabel(cap: AICapability): String = when (cap) {
    AICapability.ANALYZE_SPEC -> "Analyze"
    AICapability.GENERATE_VARIANTS -> "Generate"
    AICapability.REFINE_PROJECT -> "Refine"
}
