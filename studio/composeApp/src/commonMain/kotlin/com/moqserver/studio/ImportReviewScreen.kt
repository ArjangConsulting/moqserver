package com.moqserver.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moqserver.studio.domain.AIProviderInfo
import com.moqserver.studio.domain.ImportEndpointEntry
import com.moqserver.studio.domain.ImportSourceType
import com.moqserver.studio.domain.ImportState
import com.moqserver.studio.domain.ProviderKind
import com.moqserver.studio.projectformat.AuthType
import com.moqserver.studio.ui.MethodBadge

private object ImportReviewStrings {
    const val IMPORT_OPENAPI = "Import OpenAPI Spec"
    const val IMPORT_HAR = "Import HAR File"
    const val WARNINGS = "Warnings"
    const val PROJECT_NAME = "Project Name"
    const val SELECT_ALL = "Select All"
    const val DESELECT_ALL = "Deselect All"
    const val CANCEL = "Cancel"
    const val IMPORT = "Import"
    const val GENERATE_ALL = "Generate AI Mocks"
    const val GENERATE_ALL_HELP = "Generate response mocks for all selected endpoints using the active AI provider."
    const val GENERATE = "Generate"
    const val AI_NOT_READY = "Configure an AI provider with generation support to use AI mock generation."
    const val AI_PROVIDER = "AI Provider"
    const val AI_CHECKING = "Checking AI providers..."
    const val AI_GENERATED_PREFIX = "AI variants: "
    const val AI_LOADING = "Generating AI variants..."
    const val AI_ERROR_PREFIX = "AI error: "
    const val BULK_PROGRESS_PREFIX = "Generating AI mocks "
    const val HIDE = "Hide"
    const val DETAILS = "Details"
    const val AUTH_PREFIX = "Auth: "
    const val AND_MORE_PREFIX = "...and "
    const val AND_MORE_SUFFIX = " more"
}

@Composable
fun ImportReviewScreen(
    state: ImportState,
    aiProviders: List<AIProviderInfo>,
    aiProvidersLoading: Boolean,
    canGenerateWithAi: Boolean,
    selectedAIProviderId: String?,
    aiProviderLabel: String?,
    onRefreshAIProviders: () -> Unit,
    onSelectAIProvider: (String) -> Unit,
    onToggleEndpoint: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onUpdateProjectName: (String) -> Unit,
    onGenerateEndpointMocks: (Int) -> Unit,
    onGenerateAllMocks: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(StudioDimens.xxxl)) {
        // Header
        Text(
            text = when (state.source) {
                ImportSourceType.OPENAPI -> ImportReviewStrings.IMPORT_OPENAPI
                ImportSourceType.HAR -> ImportReviewStrings.IMPORT_HAR
            },
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(StudioDimens.xs))
        Text(
            text = "${state.sourceFileName} — ${state.parsedSpec.title} v${state.parsedSpec.version}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(StudioDimens.xl))

        // Warnings
        if (state.warnings.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(StudioDimens.l)) {
                    Text(
                        ImportReviewStrings.WARNINGS,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    for (warning in state.warnings.take(5)) {
                        Text(
                            text = warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    if (state.warnings.size > 5) {
                        Text(
                            text = "${ImportReviewStrings.AND_MORE_PREFIX}${state.warnings.size - 5}${ImportReviewStrings.AND_MORE_SUFFIX}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
            Spacer(Modifier.height(StudioDimens.l))
        }

        // Project name
        OutlinedTextField(
            value = state.projectName,
            onValueChange = onUpdateProjectName,
            label = { Text(ImportReviewStrings.PROJECT_NAME) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(0.5f),
        )

        Spacer(Modifier.height(StudioDimens.xl))

        if (state.source == ImportSourceType.OPENAPI) {
            val selectableProviders = aiProviders.filter { it.available }
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(StudioDimens.l),
                    verticalArrangement = Arrangement.spacedBy(StudioDimens.s),
                ) {
                    Text(
                        text = ImportReviewStrings.GENERATE_ALL,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = ImportReviewStrings.GENERATE_ALL_HELP,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(StudioDimens.s),
                    ) {
                        RefreshAIProvidersIcon(
                            loading = aiProvidersLoading,
                            onClick = onRefreshAIProviders,
                        )
                        if (selectableProviders.size > 1) {
                            ImportAIProviderSelector(
                                providers = selectableProviders,
                                selectedId = selectedAIProviderId,
                                onSelect = onSelectAIProvider,
                            )
                        } else {
                            aiProviderLabel?.let {
                                Text(
                                    text = "${ImportReviewStrings.AI_PROVIDER}: $it",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                    if (aiProvidersLoading) {
                        Text(
                            text = ImportReviewStrings.AI_CHECKING,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.aiBulkState.running) {
                        Text(
                            text = ImportReviewStrings.BULK_PROGRESS_PREFIX +
                                "${state.aiBulkState.completedCount}/${state.aiBulkState.totalCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (!canGenerateWithAi) {
                        Text(
                            text = ImportReviewStrings.AI_NOT_READY,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = onGenerateAllMocks,
                        enabled = canGenerateWithAi && state.acceptedCount > 0 && !state.aiBulkState.running && !aiProvidersLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary,
                        ),
                    ) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.width(StudioDimens.s))
                        Text(ImportReviewStrings.GENERATE_ALL)
                    }
                }
            }

            Spacer(Modifier.height(StudioDimens.l))
        }

        // Summary and select/deselect
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${state.acceptedCount} of ${state.totalCount} endpoints selected",
                style = MaterialTheme.typography.titleSmall,
            )
                        Row(horizontalArrangement = Arrangement.spacedBy(StudioDimens.m)) {
                TextButton(onClick = onSelectAll) { Text(ImportReviewStrings.SELECT_ALL) }
                TextButton(onClick = onDeselectAll) { Text(ImportReviewStrings.DESELECT_ALL) }
            }
        }

        Spacer(Modifier.height(StudioDimens.m))

        // Endpoint list
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(StudioDimens.xs),
        ) {
            itemsIndexed(state.entries) { index, entry ->
                ImportEndpointRow(
                    entry = entry,
                    canGenerateWithAi = canGenerateWithAi && state.source == ImportSourceType.OPENAPI,
                    bulkGenerationRunning = state.aiBulkState.running,
                    onToggle = { onToggleEndpoint(index) },
                    onGenerateMocks = { onGenerateEndpointMocks(index) },
                )
            }
        }

        Spacer(Modifier.height(StudioDimens.xl))
        HorizontalDivider()
        Spacer(Modifier.height(StudioDimens.l))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onCancel) {
                Text(ImportReviewStrings.CANCEL)
            }
            Spacer(Modifier.width(StudioDimens.m))
            Button(
                onClick = onConfirm,
                enabled = state.acceptedCount > 0 && state.projectName.isNotBlank(),
            ) {
                Text(ImportReviewStrings.IMPORT)
            }
        }
    }
}

@Composable
private fun RefreshAIProvidersIcon(
    loading: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                shape = RoundedCornerShape(StudioDimens.m),
            )
            .clickable(enabled = !loading, onClick = onClick)
            .padding(StudioDimens.s),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(StudioDimens.smallSpinnerSize),
                strokeWidth = StudioDimens.thinSpinnerStroke,
            )
        } else {
            Icon(
                Icons.Outlined.Refresh,
                contentDescription = "Refresh AI status",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun ImportEndpointRow(
    entry: ImportEndpointEntry,
    canGenerateWithAi: Boolean,
    bulkGenerationRunning: Boolean,
    onToggle: () -> Unit,
    onGenerateMocks: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val endpoint = entry.endpoint
    val accepted = entry.accepted

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onToggle() },
        colors = CardDefaults.cardColors(
            containerColor = if (accepted) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
        ),
    ) {
        Column(modifier = Modifier.padding(StudioDimens.m)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Checkbox(
                    checked = accepted,
                    onCheckedChange = { onToggle() },
                )
                MethodBadge(endpoint.method)
                Spacer(Modifier.width(StudioDimens.m))
                Text(
                    text = endpoint.path,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${endpoint.responses.size} variant${if (endpoint.responses.size != 1) "s" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (canGenerateWithAi) {
                    Spacer(Modifier.width(StudioDimens.s))
                    FilledTonalButton(
                        onClick = onGenerateMocks,
                        enabled = accepted && !entry.aiGenerationLoading && !bulkGenerationRunning,
                    ) {
                        if (entry.aiGenerationLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(StudioDimens.smallSpinnerSize),
                                strokeWidth = StudioDimens.thinSpinnerStroke,
                            )
                        } else {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                        }
                        Spacer(Modifier.width(StudioDimens.xs))
                        Text(ImportReviewStrings.GENERATE)
                    }
                }
                Spacer(Modifier.width(StudioDimens.m))
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) ImportReviewStrings.HIDE else ImportReviewStrings.DETAILS)
                }
            }

            if (entry.generatedResponses.isNotEmpty()) {
                Text(
                    text = ImportReviewStrings.AI_GENERATED_PREFIX + entry.generatedResponses.size,
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioColors.success,
                    modifier = Modifier.padding(start = 48.dp),
                )
            }
            entry.aiGenerationError?.let { error ->
                Text(
                    text = ImportReviewStrings.AI_ERROR_PREFIX + error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 48.dp),
                )
            }
            if (entry.aiGenerationLoading) {
                Text(
                    text = ImportReviewStrings.AI_LOADING,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 48.dp),
                )
            }

            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 48.dp, top = StudioDimens.xs),
                    verticalArrangement = Arrangement.spacedBy(StudioDimens.xxs),
                ) {
                    if (endpoint.authType != AuthType.NONE) {
                        Text(
                            "${ImportReviewStrings.AUTH_PREFIX}${endpoint.authType.name.lowercase()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    for (resp in endpoint.responses) {
                        Row(horizontalArrangement = Arrangement.spacedBy(StudioDimens.m)) {
                            Text(
                                "${resp.statusCode}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                resp.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            resp.headers["Content-Type"]?.let { ct ->
                                Text(
                                    ct,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                    if (entry.generatedResponses.isNotEmpty()) {
                        Spacer(Modifier.height(StudioDimens.xs))
                        Text(
                            text = "AI generated variants",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioColors.success,
                        )
                        for (resp in entry.generatedResponses) {
                            Row(horizontalArrangement = Arrangement.spacedBy(StudioDimens.m)) {
                                Text(
                                    text = "${resp.statusCode}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = resp.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                resp.headers["Content-Type"]?.let { ct ->
                                    Text(
                                        text = ct,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportAIProviderSelector(
    providers: List<AIProviderInfo>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = providers.find { it.id == selectedId } ?: providers.first()

    Box {
        Row(
            modifier = Modifier
                .clickable { expanded = true }
                .background(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(StudioDimens.m),
                )
                .padding(horizontal = StudioDimens.m, vertical = StudioDimens.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StudioDimens.xs),
        ) {
            Text(selected.displayName)
            Icon(
                Icons.Outlined.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            providers.forEach { provider ->
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(StudioDimens.s),
                        ) {
                            Text(provider.displayName)
                            Text(
                                text = if (provider.kind == ProviderKind.LOCAL) "Local" else "Hosted",
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
