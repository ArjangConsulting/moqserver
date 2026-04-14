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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ArrowDropUp
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moqserver.studio.domain.AIProviderInfo
import com.moqserver.studio.domain.EndpointUpdateStatus
import com.moqserver.studio.domain.ImportEndpointEntry
import com.moqserver.studio.domain.ImportSourceType
import com.moqserver.studio.domain.ImportState
import com.moqserver.studio.domain.ProviderKind
import com.moqserver.studio.projectformat.AuthType
import com.moqserver.studio.ui.MethodBadge

private object ImportReviewStrings {
	const val IMPORT_OPENAPI = "Import OpenAPI Spec"
	const val IMPORT_HAR = "Import HAR File"
	const val UPDATE_OPENAPI = "Update from OpenAPI Spec"
	const val UPDATE_HAR = "Update from HAR File"
	const val WARNINGS = "Warnings"
	const val PROJECT_NAME = "Project Name"
	const val SELECT_ALL = "Select All"
	const val DESELECT_ALL = "Deselect All"
	const val CANCEL = "Cancel"
	const val IMPORT = "Import"
	const val UPDATE = "Update"
	const val GENERATE_ALL = "Generate AI Mocks"
	const val GENERATE_ALL_HELP = "Use AI to generate realistic mock response bodies and additional " +
		"variants (e.g. error responses, edge cases) for every selected endpoint. " +
		"The AI reads each endpoint's method, path, and existing schema to produce " +
		"context-aware JSON payloads you can review before importing."
	const val GENERATE_INDIVIDUAL_HELP = "You can also generate mocks for a single endpoint using the " +
		"sparkle button on each row. This lets you provide custom context per endpoint."
	const val GENERATE = "Generate"
	const val AI_NOT_READY = "Configure an AI provider with generation support to use AI mock generation."
	const val AI_PROVIDER = "AI Provider"
	const val AI_CHECKING = "Checking AI providers..."
	const val AI_GENERATED_PREFIX = "AI variants: "
	const val AI_LOADING = "Generating AI variants..."
	const val AI_ERROR_PREFIX = "AI error: "
	const val BULK_PROGRESS_PREFIX = "Generating AI mocks "
	const val AI_CONTEXT_HINT_LABEL = "Additional context for AI"
	const val AI_CONTEXT_HINT_PLACEHOLDER =
		"e.g. \"Use realistic e-commerce data\", \"Return XML\", \"Include pagination\""
	const val HIDE = "Hide"
	const val DETAILS = "Details"
	const val AUTH_PREFIX = "Auth: "
	const val TAGS_PREFIX = "Tags: "
	const val UNGROUPED = "Ungrouped"
	const val AND_MORE_PREFIX = "...and "
	const val AND_MORE_SUFFIX = " more"
	const val DIALOG_GENERATE_TITLE = "Generate AI Mocks"
	const val DIALOG_GENERATE_ENDPOINT_SUBTITLE = "Optionally provide extra context to guide the AI generation."
	const val DIALOG_BULK_SUBTITLE = "Optionally provide per-endpoint context to guide AI generation for all selected endpoints."
	const val AI_GENERATED_VARIANTS = "AI generated variants"
	const val STATUS_NEW = "New"
	const val STATUS_CHANGED = "Changed"
	const val STATUS_UNCHANGED = "Unchanged"
	const val CHANGED_DIFF_PREFIX = "Changes: "
	const val UPDATE_SUMMARY_NEW = "new"
	const val UPDATE_SUMMARY_CHANGED = "changed"
	const val UPDATE_SUMMARY_UNCHANGED = "unchanged"
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
	onSetGroupAccepted: (List<Int>, Boolean) -> Unit,
	onSelectAll: () -> Unit,
	onDeselectAll: () -> Unit,
	onUpdateProjectName: (String) -> Unit,
	onUpdateEndpointAIContextHint: (Int, String) -> Unit,
	onGenerateEndpointMocks: (Int) -> Unit,
	onGenerateAllMocks: () -> Unit,
	onConfirm: () -> Unit,
	onCancel: () -> Unit,
	modifier: Modifier = Modifier,
) {
	var singleEndpointDialogIndex by remember { mutableStateOf<Int?>(null) }
	var showBulkDialog by remember { mutableStateOf(false) }
	val isOpenApiSource = state.source == ImportSourceType.OPENAPI
	val isUpdateMode = state.isUpdateMode

	singleEndpointDialogIndex?.let { index ->
		val entry = state.entries.getOrNull(index)
		if (entry != null) {
			SingleEndpointAIDialog(
				entry = entry,
				onDismiss = { singleEndpointDialogIndex = null },
				onGenerate = { hint ->
					onUpdateEndpointAIContextHint(index, hint)
					onGenerateEndpointMocks(index)
					singleEndpointDialogIndex = null
				},
			)
		}
	}

	if (showBulkDialog) {
		BulkAIDialog(
			entries = state.entries,
			onDismiss = { showBulkDialog = false },
			onGenerate = { hints ->
				hints.forEach { (index, hint) -> onUpdateEndpointAIContextHint(index, hint) }
				onGenerateAllMocks()
				showBulkDialog = false
			},
		)
	}

	Column(modifier = modifier.padding(StudioDimens.xxxl)) {
        // Header
        Text(
            text = when {
                isUpdateMode && state.source == ImportSourceType.OPENAPI -> ImportReviewStrings.UPDATE_OPENAPI
                isUpdateMode && state.source == ImportSourceType.HAR -> ImportReviewStrings.UPDATE_HAR
                state.source == ImportSourceType.OPENAPI -> ImportReviewStrings.IMPORT_OPENAPI
                else -> ImportReviewStrings.IMPORT_HAR
            },
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(StudioDimens.xs))
        Text(
            text = "${state.sourceFileName} — ${state.parsedSpec.title} v${state.parsedSpec.version}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Update-mode summary line
        if (isUpdateMode) {
            Spacer(Modifier.height(StudioDimens.xs))
            val newCount = state.entries.count { it.updateStatus == EndpointUpdateStatus.NEW }
            val changedCount = state.entries.count { it.updateStatus == EndpointUpdateStatus.CHANGED }
            val unchangedCount = state.entries.count { it.updateStatus == EndpointUpdateStatus.UNCHANGED }
            Text(
                text = "$newCount ${ImportReviewStrings.UPDATE_SUMMARY_NEW}" +
                    " · $changedCount ${ImportReviewStrings.UPDATE_SUMMARY_CHANGED}" +
                    " · $unchangedCount ${ImportReviewStrings.UPDATE_SUMMARY_UNCHANGED}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

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

        // Project name (only for new-project imports)
        if (!isUpdateMode) {
            OutlinedTextField(
                value = state.projectName,
                onValueChange = onUpdateProjectName,
                label = { Text(ImportReviewStrings.PROJECT_NAME) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(0.5f),
            )

            Spacer(Modifier.height(StudioDimens.xl))
        }

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
                        RefreshAIProvidersIcon(
                            loading = aiProvidersLoading,
                            onClick = onRefreshAIProviders,
                        )
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
                        onClick = { showBulkDialog = true },
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
                    Text(
                        text = ImportReviewStrings.GENERATE_INDIVIDUAL_HELP,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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

		val groupedEntries = remember(state.entries) {
			state.entries
				.mapIndexed { index, entry -> index to entry }
				.groupBy { (_, entry) -> importGroupLabel(entry) }
				.toSortedMap()
		}
		val expandedGroups = remember(groupedEntries.keys) {
			mutableStateMapOf<String, Boolean>().apply {
				groupedEntries.keys.forEach { put(it, true) }
			}
		}

		// Endpoint list
		LazyColumn(
			modifier = Modifier.weight(1f).fillMaxWidth(),
			verticalArrangement = Arrangement.spacedBy(StudioDimens.xs),
		) {
			groupedEntries.forEach { (group, entries) ->
				item(key = "group-$group") {
					ImportGroupHeader(
						group = group,
						entries = entries,
						expanded = expandedGroups[group] != false,
						onToggleExpanded = {
							expandedGroups[group] = expandedGroups[group] == false
						},
						onSetAccepted = { accepted ->
							onSetGroupAccepted(entries.map { it.first }, accepted)
						},
					)
				}
				if (expandedGroups[group] != false) {
					items(
						count = entries.size,
						key = { itemIndex ->
							val (index, entry) = entries[itemIndex]
							"import-$index-${entry.endpoint.method}-${entry.endpoint.path}"
						},
					) { itemIndex ->
						val (index, entry) = entries[itemIndex]
						ImportEndpointRow(
							entry = entry,
							canGenerateWithAi = canGenerateWithAi && isOpenApiSource,
							bulkGenerationRunning = state.aiBulkState.running,
							isUpdateMode = isUpdateMode,
							onToggle = { onToggleEndpoint(index) },
							onAIClick = { singleEndpointDialogIndex = index },
							modifier = Modifier.padding(start = 28.dp),
						)
					}
				}
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
                Text(if (isUpdateMode) ImportReviewStrings.UPDATE else ImportReviewStrings.IMPORT)
            }
        }
    }
}

private fun importGroupLabel(entry: ImportEndpointEntry): String {
	return entry.endpoint.tags.firstOrNull()?.takeIf { it.isNotBlank() } ?: run {
		val path = entry.endpoint.path
		val segments = path.removePrefix("/").split("/")
		when {
			path.isBlank() || path == "/" -> ImportReviewStrings.UNGROUPED
			segments.size > 1 && segments.first().isNotBlank() -> "/${segments.first()}"
			else -> path
		}
	}
}

@Composable
private fun ImportGroupHeader(
	group: String,
	entries: List<Pair<Int, ImportEndpointEntry>>,
	expanded: Boolean,
	onToggleExpanded: () -> Unit,
	onSetAccepted: (Boolean) -> Unit,
) {
	val acceptedCount = entries.count { (_, entry) -> entry.accepted }
	val totalCount = entries.size
	val toggleableState = when {
		acceptedCount == 0 -> ToggleableState.Off
		acceptedCount == totalCount -> ToggleableState.On
		else -> ToggleableState.Indeterminate
	}
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(top = StudioDimens.s, bottom = StudioDimens.xs),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(StudioDimens.s),
	) {
		TriStateCheckbox(
			state = toggleableState,
			onClick = {
				onSetAccepted(toggleableState != ToggleableState.On)
			},
		)
		TextButton(onClick = onToggleExpanded) {
			Icon(
				imageVector = if (expanded) Icons.Outlined.ArrowDropUp else Icons.Outlined.ArrowDropDown,
				contentDescription = null,
			)
			Text(group)
		}
		Text(
			text = "$acceptedCount/$totalCount selected",
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.weight(1f),
		)
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
private fun EndpointStatusBadge(status: EndpointUpdateStatus) {
	val (label, containerColor, contentColor) = when (status) {
		EndpointUpdateStatus.NEW -> Triple(
			ImportReviewStrings.STATUS_NEW,
			StudioColors.success.copy(alpha = 0.15f),
			StudioColors.success,
		)
		EndpointUpdateStatus.CHANGED -> Triple(
			ImportReviewStrings.STATUS_CHANGED,
			MaterialTheme.colorScheme.primaryContainer,
			MaterialTheme.colorScheme.onPrimaryContainer,
		)
		EndpointUpdateStatus.UNCHANGED -> Triple(
			ImportReviewStrings.STATUS_UNCHANGED,
			MaterialTheme.colorScheme.surfaceVariant,
			MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
	Box(
		modifier = Modifier
			.background(color = containerColor, shape = RoundedCornerShape(StudioDimens.xs))
			.padding(horizontal = StudioDimens.s, vertical = 2.dp),
		contentAlignment = Alignment.Center,
	) {
		Text(
			text = label,
			style = MaterialTheme.typography.labelSmall,
			color = contentColor,
			fontWeight = FontWeight.Medium,
		)
	}
}

@Composable
private fun ImportEndpointRow(
	entry: ImportEndpointEntry,
	canGenerateWithAi: Boolean,
	bulkGenerationRunning: Boolean,
	isUpdateMode: Boolean,
	onToggle: () -> Unit,
	onAIClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	var expanded by remember { mutableStateOf(false) }
	val endpoint = entry.endpoint
	val accepted = entry.accepted

	Card(
		modifier = modifier.fillMaxWidth().clickable { onToggle() },
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
				Spacer(Modifier.width(StudioDimens.s))
				// Show update-status badge only in update mode
				if (isUpdateMode) {
					EndpointStatusBadge(entry.updateStatus)
					Spacer(Modifier.width(StudioDimens.s))
				}
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
					FilledIconButton(
						onClick = onAIClick,
						enabled = accepted && !entry.aiGenerationLoading && !bulkGenerationRunning,
						colors = IconButtonDefaults.filledIconButtonColors(
							containerColor = MaterialTheme.colorScheme.tertiaryContainer,
							contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
						),
					) {
						if (entry.aiGenerationLoading) {
							CircularProgressIndicator(
								modifier = Modifier.size(StudioDimens.smallSpinnerSize),
								strokeWidth = StudioDimens.thinSpinnerStroke,
							)
						} else {
							Icon(
								Icons.Outlined.AutoAwesome,
								contentDescription = ImportReviewStrings.GENERATE,
							)
						}
					}
				}
				Spacer(Modifier.width(StudioDimens.m))
				TextButton(onClick = { expanded = !expanded }) {
					Text(if (expanded) ImportReviewStrings.HIDE else ImportReviewStrings.DETAILS)
				}
			}

			if (endpoint.tags.isNotEmpty()) {
				Text(
					text = ImportReviewStrings.TAGS_PREFIX + endpoint.tags.joinToString(" / "),
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(start = 48.dp),
				)
			}

			// Show diff summary for changed endpoints
			entry.specDiff?.takeIf { it.hasChanges }?.let { diff ->
				Text(
					text = ImportReviewStrings.CHANGED_DIFF_PREFIX + diff.summary(),
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.primary,
					modifier = Modifier.padding(start = 48.dp),
				)
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
							text = ImportReviewStrings.AI_GENERATED_VARIANTS,
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
					if (entry.aiContextHint.isNotBlank()) {
						Text(
							text = "${ImportReviewStrings.AI_CONTEXT_HINT_LABEL}: ${entry.aiContextHint}",
							style = MaterialTheme.typography.labelSmall,
							color = MaterialTheme.colorScheme.primary,
						)
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

@Composable
private fun SingleEndpointAIDialog(
	entry: ImportEndpointEntry,
	onDismiss: () -> Unit,
	onGenerate: (hint: String) -> Unit,
) {
	var hint by remember { mutableStateOf(entry.aiContextHint) }
	val endpoint = entry.endpoint

	AlertDialog(
		onDismissRequest = onDismiss,
		title = {
			Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.xs)) {
				Text(ImportReviewStrings.DIALOG_GENERATE_TITLE)
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(StudioDimens.s),
				) {
					MethodBadge(endpoint.method)
					Text(
						text = endpoint.path,
						style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
					)
				}
			}
		},
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.m)) {
				Text(
					text = ImportReviewStrings.DIALOG_GENERATE_ENDPOINT_SUBTITLE,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				OutlinedTextField(
					value = hint,
					onValueChange = { hint = it },
					label = { Text(ImportReviewStrings.AI_CONTEXT_HINT_LABEL) },
					placeholder = {
						Text(
							ImportReviewStrings.AI_CONTEXT_HINT_PLACEHOLDER,
							style = MaterialTheme.typography.bodySmall,
						)
					},
					minLines = 2,
					maxLines = 4,
					modifier = Modifier.fillMaxWidth(),
					textStyle = MaterialTheme.typography.bodySmall,
				)
			}
		},
		confirmButton = {
			Button(
				onClick = { onGenerate(hint) },
				colors = ButtonDefaults.buttonColors(
					containerColor = MaterialTheme.colorScheme.tertiary,
					contentColor = MaterialTheme.colorScheme.onTertiary,
				),
			) {
				Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
				Spacer(Modifier.width(StudioDimens.s))
				Text(ImportReviewStrings.GENERATE)
			}
		},
		dismissButton = {
			OutlinedButton(onClick = onDismiss) {
				Text(ImportReviewStrings.CANCEL)
			}
		},
	)
}

@Composable
private fun BulkAIDialog(
	entries: List<ImportEndpointEntry>,
	onDismiss: () -> Unit,
	onGenerate: (hints: Map<Int, String>) -> Unit,
) {
	val acceptedIndices = remember(entries) {
		entries.mapIndexedNotNull { index, entry -> if (entry.accepted) index else null }
	}
	val hints = remember(entries) {
		mutableStateMapOf<Int, String>().apply {
			for (index in acceptedIndices) {
				put(index, entries[index].aiContextHint)
			}
		}
	}

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(ImportReviewStrings.DIALOG_GENERATE_TITLE) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.m)) {
				Text(
					text = ImportReviewStrings.DIALOG_BULK_SUBTITLE,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				LazyColumn(
					modifier = Modifier.heightIn(max = 400.dp).fillMaxWidth(),
					verticalArrangement = Arrangement.spacedBy(StudioDimens.m),
				) {
					for (index in acceptedIndices) {
						val entry = entries[index]
						item(key = index) {
							Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.xs)) {
								Row(
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.spacedBy(StudioDimens.s),
								) {
									MethodBadge(entry.endpoint.method)
									Text(
										text = entry.endpoint.path,
										style = MaterialTheme.typography.bodySmall.copy(
											fontFamily = FontFamily.Monospace,
										),
										modifier = Modifier.weight(1f),
									)
								}
								OutlinedTextField(
									value = hints[index].orEmpty(),
									onValueChange = { hints[index] = it },
									placeholder = {
										Text(
											ImportReviewStrings.AI_CONTEXT_HINT_PLACEHOLDER,
											style = MaterialTheme.typography.bodySmall,
										)
									},
									minLines = 1,
									maxLines = 2,
									modifier = Modifier.fillMaxWidth(),
									textStyle = MaterialTheme.typography.bodySmall,
								)
							}
						}
					}
				}
			}
		},
		confirmButton = {
			Button(
				onClick = { onGenerate(hints.toMap()) },
				colors = ButtonDefaults.buttonColors(
					containerColor = MaterialTheme.colorScheme.tertiary,
					contentColor = MaterialTheme.colorScheme.onTertiary,
				),
			) {
				Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
				Spacer(Modifier.width(StudioDimens.s))
				Text(ImportReviewStrings.GENERATE_ALL)
			}
		},
		dismissButton = {
			OutlinedButton(onClick = onDismiss) {
				Text(ImportReviewStrings.CANCEL)
			}
		},
	)
}
