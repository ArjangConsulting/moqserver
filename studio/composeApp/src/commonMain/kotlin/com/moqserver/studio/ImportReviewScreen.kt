package com.moqserver.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moqserver.studio.domain.AIProviderInfo
import com.moqserver.studio.domain.EndpointUpdateStatus
import com.moqserver.studio.domain.ImportEndpointEntry
import com.moqserver.studio.domain.ImportSourceType
import com.moqserver.studio.domain.ImportState
import com.moqserver.studio.domain.UpdateSelection
import com.moqserver.studio.domain.selectedGeneratedResponses
import com.moqserver.studio.projectformat.AuthType
import com.moqserver.studio.ui.MethodBadge
import io.github.maniramezan.kmpcomponents.FlowLayout
import io.github.maniramezan.kmpcomponents.FormTextField
import io.github.maniramezan.kmpcomponents.InlineProgressIndicator
import io.github.maniramezan.kmpcomponents.LoadingIconButton
import io.github.maniramezan.kmpcomponents.MenuPicker
import io.github.maniramezan.kmpcomponents.PrimaryButton
import io.github.maniramezan.kmpcomponents.ProgressIndicator
import io.github.maniramezan.kmpcomponents.SecondaryButton
import io.github.maniramezan.kmpcomponents.SectionCard
import io.github.maniramezan.kmpcomponents.StatusMessage
import io.github.maniramezan.kmpcomponents.StatusTone
import io.github.maniramezan.kmpcomponents.ThemeCheckbox
import io.github.maniramezan.kmpcomponents.ThemeIconButton
import io.github.maniramezan.kmpcomponents.ThemeTextButton
import io.github.maniramezan.kmpcomponents.ThemeTriStateCheckbox
import io.github.maniramezan.kmpcomponents.TonalBadge
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private object ImportReviewStrings {
	const val EXPAND_GROUP = "Expand endpoint group"
	const val COLLAPSE_GROUP = "Collapse endpoint group"
	const val REFRESH_AI = "Refresh AI status"
	const val CHECKING_AI = "Checking AI providers"
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
	const val UPDATE_PARTS = "Update Parts"
	const val UPDATE_PARTS_HELP =
		"Choose what to apply from this source. URL adds newly discovered endpoints, " +
			"Details updates metadata and request rules, and Body updates response content and variants."
	const val UPDATE_PART_URL = "URL"
	const val UPDATE_PART_DETAILS = "Details"
	const val UPDATE_PART_BODY = "Body"
	const val VARIANT_NAME = "Variant name"
	const val EXISTING_VARIANT_NAME = "Existing variant name"
	const val VARIANT_BODY = "Body"
	const val SHOW_BODY = "Show Body"
	const val HIDE_BODY = "Hide Body"
	const val BINARY_BODY_PLACEHOLDER = "Binary content preview unavailable"
}

private val bodyPreviewJson = Json {
	prettyPrint = true
	prettyPrintIndent = "  "
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
	onUpdateSelection: (UpdateSelection) -> Unit,
	onUpdateResponseName: (Int, Int, String) -> Unit,
	onUpdateGeneratedResponseName: (Int, Int, String) -> Unit,
	onToggleGeneratedResponse: (Int, Int) -> Unit,
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

		if (isUpdateMode) {
			UpdateSelectionCard(
				selection = state.updateSelection,
				onSelectionChange = onUpdateSelection,
			)
			Spacer(Modifier.height(StudioDimens.l))
		}

        // Warnings
        if (state.warnings.isNotEmpty()) {
            val warnings = state.warnings.take(5).toMutableList()
            if (state.warnings.size > 5) {
                warnings += "${ImportReviewStrings.AND_MORE_PREFIX}${state.warnings.size - 5}${ImportReviewStrings.AND_MORE_SUFFIX}"
            }
            StatusMessage(
                title = ImportReviewStrings.WARNINGS,
                message = warnings.joinToString("\n"),
                tone = StatusTone.WARNING,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(StudioDimens.l))
        }

        // Project name (only for new-project imports)
        if (!isUpdateMode) {
            FormTextField(
                value = state.projectName,
                onValueChange = onUpdateProjectName,
                label = ImportReviewStrings.PROJECT_NAME,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(0.5f),
            )

            Spacer(Modifier.height(StudioDimens.xl))
        }

		if (state.source == ImportSourceType.OPENAPI) {
            val selectableProviders = aiProviders.filter { it.available }
            SectionCard(
                title = ImportReviewStrings.GENERATE_ALL,
                modifier = Modifier.fillMaxWidth(),
                description = ImportReviewStrings.GENERATE_ALL_HELP,
            ) {
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
                    if (aiProvidersLoading) InlineProgressIndicator(label = ImportReviewStrings.AI_CHECKING)
                    if (state.aiBulkState.running) {
                        ProgressIndicator(
                            label = ImportReviewStrings.BULK_PROGRESS_PREFIX +
                                "${state.aiBulkState.completedCount}/${state.aiBulkState.totalCount}",
                            progress = state.aiBulkState.completedCount.toFloat() / state.aiBulkState.totalCount.coerceAtLeast(1),
                        )
                    } else if (!canGenerateWithAi) {
                        Text(
                            text = ImportReviewStrings.AI_NOT_READY,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    PrimaryButton(
                        text = ImportReviewStrings.GENERATE_ALL,
                        onClick = { showBulkDialog = true },
                        enabled = canGenerateWithAi && state.acceptedCount > 0 && !state.aiBulkState.running && !aiProvidersLoading,
                    )
                    Text(
                        text = ImportReviewStrings.GENERATE_INDIVIDUAL_HELP,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
				"${state.effectiveAcceptedCount} of ${state.totalCount} endpoints selected",
				style = MaterialTheme.typography.titleSmall,
			)
                        Row(horizontalArrangement = Arrangement.spacedBy(StudioDimens.m)) {
                ThemeTextButton(
                    text = ImportReviewStrings.SELECT_ALL,
                    onClick = onSelectAll,
                )
                ThemeTextButton(
                    text = ImportReviewStrings.DESELECT_ALL,
                    onClick = onDeselectAll,
                )
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
							onUpdateResponseName = { responseIndex, name ->
								onUpdateResponseName(index, responseIndex, name)
							},
							onUpdateGeneratedResponseName = { responseIndex, name ->
								onUpdateGeneratedResponseName(index, responseIndex, name)
							},
							onToggleGeneratedResponse = { responseIndex -> onToggleGeneratedResponse(index, responseIndex) },
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
            SecondaryButton(
                text = ImportReviewStrings.CANCEL,
                onClick = onCancel,
            )
            Spacer(Modifier.width(StudioDimens.m))
			PrimaryButton(
				text = if (isUpdateMode) ImportReviewStrings.UPDATE else ImportReviewStrings.IMPORT,
				onClick = onConfirm,
				enabled = state.effectiveAcceptedCount > 0 && state.projectName.isNotBlank() &&
					(!isUpdateMode || state.updateSelection.hasEnabledParts),
			)
        }
    }
}

@Composable
private fun UpdateSelectionCard(
	selection: UpdateSelection,
	onSelectionChange: (UpdateSelection) -> Unit,
) {
	SectionCard(
		title = ImportReviewStrings.UPDATE_PARTS,
		description = ImportReviewStrings.UPDATE_PARTS_HELP,
		modifier = Modifier.fillMaxWidth(),
	) {
		FlowLayout {
			ThemeCheckbox(
				label = ImportReviewStrings.UPDATE_PART_URL,
				checked = selection.url,
				onCheckedChange = { onSelectionChange(selection.copy(url = it)) },
			)
			ThemeCheckbox(
				label = ImportReviewStrings.UPDATE_PART_DETAILS,
				checked = selection.details,
				onCheckedChange = { onSelectionChange(selection.copy(details = it)) },
			)
			ThemeCheckbox(
				label = ImportReviewStrings.UPDATE_PART_BODY,
				checked = selection.body,
				onCheckedChange = { onSelectionChange(selection.copy(body = it)) },
			)
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
		ThemeTriStateCheckbox(
			label = "$acceptedCount/$totalCount selected",
			state = toggleableState,
			onClick = {
				onSetAccepted(toggleableState != ToggleableState.On)
			},
		)
		ThemeIconButton(
			icon = rememberVectorPainter(if (expanded) Icons.Outlined.ArrowDropUp else Icons.Outlined.ArrowDropDown),
			contentDescription = if (expanded) ImportReviewStrings.COLLAPSE_GROUP else ImportReviewStrings.EXPAND_GROUP,
			onClick = onToggleExpanded,
		)
		Text(group)
	}
}

@Composable
private fun RefreshAIProvidersIcon(
    loading: Boolean,
    onClick: () -> Unit,
) {
    LoadingIconButton(
        loading = loading,
        icon = rememberVectorPainter(Icons.Outlined.Refresh),
        contentDescription = ImportReviewStrings.REFRESH_AI,
        loadingLabel = ImportReviewStrings.CHECKING_AI,
        onClick = onClick,
    )
}

@Composable
private fun EndpointStatusBadge(status: EndpointUpdateStatus) {
	val (label, tone) = when (status) {
		EndpointUpdateStatus.NEW -> ImportReviewStrings.STATUS_NEW to StatusTone.SUCCESS
		EndpointUpdateStatus.CHANGED -> ImportReviewStrings.STATUS_CHANGED to StatusTone.WARNING
		EndpointUpdateStatus.UNCHANGED -> ImportReviewStrings.STATUS_UNCHANGED to StatusTone.INFO
	}
	TonalBadge(text = label, tone = tone)
}

@Composable
private fun ImportEndpointRow(
	entry: ImportEndpointEntry,
	canGenerateWithAi: Boolean,
	bulkGenerationRunning: Boolean,
	isUpdateMode: Boolean,
	onToggle: () -> Unit,
	onUpdateResponseName: (Int, String) -> Unit,
	onUpdateGeneratedResponseName: (Int, String) -> Unit,
	onToggleGeneratedResponse: (Int) -> Unit,
	onAIClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	var expanded by remember { mutableStateOf(false) }
	val endpoint = entry.endpoint
	val accepted = entry.accepted

	Card(
		modifier = modifier.fillMaxWidth(),
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
				ThemeCheckbox(
					checked = accepted,
					onCheckedChange = { onToggle() },
					label = endpoint.path,
					modifier = Modifier.weight(1f),
				)
				MethodBadge(endpoint.method)
				Spacer(Modifier.width(StudioDimens.s))
				// Show update-status badge only in update mode
				if (isUpdateMode) {
					EndpointStatusBadge(entry.updateStatus)
					Spacer(Modifier.width(StudioDimens.s))
				}

				val totalVariantCount = endpoint.responses.size + entry.selectedGeneratedResponses.size
				Text(
					text = "$totalVariantCount variant${if (totalVariantCount != 1) "s" else ""}",
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				if (canGenerateWithAi) {
					Spacer(Modifier.width(StudioDimens.s))
					LoadingIconButton(
						loading = entry.aiGenerationLoading,
						icon = rememberVectorPainter(Icons.Outlined.AutoAwesome),
						contentDescription = ImportReviewStrings.GENERATE,
						loadingLabel = ImportReviewStrings.AI_LOADING,
						onClick = onAIClick,
						enabled = accepted && !bulkGenerationRunning,
					)
				}
				Spacer(Modifier.width(StudioDimens.m))
				ThemeTextButton(
					text = if (expanded) ImportReviewStrings.HIDE else ImportReviewStrings.DETAILS,
					onClick = { expanded = !expanded },
				)
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

			entry.aiGenerationError?.let { error ->
				StatusMessage(message = ImportReviewStrings.AI_ERROR_PREFIX + error, tone = StatusTone.ERROR)
			}
			if (entry.aiGenerationLoading) InlineProgressIndicator(label = ImportReviewStrings.AI_LOADING)

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
					for ((responseIndex, resp) in endpoint.responses.withIndex()) {
						val preservesExistingVariantName = isUpdateMode && responseIndex in entry.lockedResponseIndices
						Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.xxs)) {
							Row(horizontalArrangement = Arrangement.spacedBy(StudioDimens.m)) {
								Text(
									"${resp.statusCode}",
									style = MaterialTheme.typography.labelSmall,
									fontWeight = FontWeight.Bold,
								)
								resp.headers["Content-Type"]?.let { ct ->
									Text(
										ct,
										style = MaterialTheme.typography.labelSmall,
										color = MaterialTheme.colorScheme.outline,
									)
								}
							}
							FormTextField(
								value = resp.name,
								onValueChange = { onUpdateResponseName(responseIndex, it) },
								label = if (preservesExistingVariantName) {
											ImportReviewStrings.EXISTING_VARIANT_NAME
										} else {
											ImportReviewStrings.VARIANT_NAME
										},
								enabled = !preservesExistingVariantName,
								singleLine = true,
								modifier = Modifier.fillMaxWidth(),
							)
							resp.body?.takeIf { it.isNotBlank() }?.let { body ->
								VariantBodyPreview(body = body)
							}
						}
					}
					for ((generatedResponseIndex, resp) in entry.generatedResponses.withIndex()) {
						Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.xxs)) {
							Row(
								horizontalArrangement = Arrangement.spacedBy(StudioDimens.m),
								verticalAlignment = Alignment.CenterVertically,
							) {
								ThemeCheckbox(
									checked = generatedResponseIndex in entry.selectedGeneratedResponseIndices,
									onCheckedChange = { onToggleGeneratedResponse(generatedResponseIndex) },
									label = resp.name,
									modifier = Modifier.weight(1f),
								)
								Icon(
									Icons.Outlined.AutoAwesome,
									contentDescription = "AI generated",
									modifier = Modifier.size(StudioDimens.smallIconSize),
									tint = MaterialTheme.colorScheme.tertiary,
								)
								Text(
									"${resp.statusCode}",
									style = MaterialTheme.typography.labelSmall,
									fontWeight = FontWeight.Bold,
								)
								resp.headers["Content-Type"]?.let { ct ->
									Text(
										ct,
										style = MaterialTheme.typography.labelSmall,
										color = MaterialTheme.colorScheme.outline,
									)
								}
							}
							FormTextField(
								value = resp.name,
								onValueChange = { onUpdateGeneratedResponseName(generatedResponseIndex, it) },
								label = ImportReviewStrings.VARIANT_NAME,
								enabled = true,
								singleLine = true,
								modifier = Modifier.fillMaxWidth(),
							)
							resp.body?.takeIf { it.isNotBlank() }?.let { body ->
								VariantBodyPreview(body = body)
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
private fun VariantBodyPreview(body: String) {
	var expanded by remember(body) { mutableStateOf(false) }
	Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.xxs)) {
		ThemeTextButton(
			text = if (expanded) ImportReviewStrings.HIDE_BODY else ImportReviewStrings.SHOW_BODY,
			onClick = { expanded = !expanded },
		)
		if (expanded) {
			val preview = remember(body) { formatBodyPreview(body) }
			Text(
				text = preview,
				style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier
					.fillMaxWidth()
					.background(
						color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
						shape = RoundedCornerShape(StudioDimens.s),
					)
					.padding(StudioDimens.s),
			)
		}
	}
}

private fun formatBodyPreview(body: String): String {
	val trimmed = body.trim()
	if (trimmed.isEmpty()) return body
	if (isLikelyBinaryBody(trimmed)) return ImportReviewStrings.BINARY_BODY_PLACEHOLDER
	return parsePrettyJson(trimmed) ?: body
}

private fun parsePrettyJson(body: String): String? {
	return runCatching {
		val element: JsonElement = bodyPreviewJson.parseToJsonElement(body)
		bodyPreviewJson.encodeToString(JsonElement.serializer(), element)
	}.getOrNull()
}

private fun isLikelyBinaryBody(body: String): Boolean {
	if (body.equals("binary", ignoreCase = true)) return true
	if (body.length > 128 && body.matches(Regex("^[A-Za-z0-9+/=\\r\\n]+$"))) return true
	val controlChars = body.count { ch -> ch.code in 0..8 || ch.code in 14..31 }
	return controlChars > 0
}

@Composable
private fun ImportAIProviderSelector(
    providers: List<AIProviderInfo>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    val selected = providers.find { it.id == selectedId } ?: providers.firstOrNull() ?: return
    MenuPicker(
        items = providers,
        selected = selected,
        onSelect = { onSelect(it.id) },
        label = ImportReviewStrings.AI_PROVIDER,
        itemLabel = { it.displayName },
    )
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
				FormTextField(
					value = hint,
					onValueChange = { hint = it },
					label = ImportReviewStrings.AI_CONTEXT_HINT_LABEL,
					placeholder = ImportReviewStrings.AI_CONTEXT_HINT_PLACEHOLDER,
					modifier = Modifier.fillMaxWidth().heightIn(
						min = StudioDimens.multilineFieldMinHeight,
						max = StudioDimens.multilineFieldMaxHeight,
					),
					singleLine = false,
				)
			}
		},
		confirmButton = {
			PrimaryButton(
				text = ImportReviewStrings.GENERATE,
				onClick = { onGenerate(hint) },
			)
		},
		dismissButton = {
			SecondaryButton(
				text = ImportReviewStrings.CANCEL,
				onClick = onDismiss,
			)
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
								FormTextField(
									value = hints[index].orEmpty(),
									onValueChange = { hints[index] = it },
									placeholder = ImportReviewStrings.AI_CONTEXT_HINT_PLACEHOLDER,
									modifier = Modifier.fillMaxWidth().heightIn(
										min = StudioDimens.multilineFieldMinHeight,
										max = StudioDimens.multilineFieldMaxHeight,
									),
									label = ImportReviewStrings.AI_CONTEXT_HINT_LABEL,
									singleLine = false,
								)
							}
						}
					}
				}
			}
		},
		confirmButton = {
			PrimaryButton(
				text = ImportReviewStrings.GENERATE_ALL,
				onClick = { onGenerate(hints.toMap()) },
			)
		},
		dismissButton = {
			SecondaryButton(
				text = ImportReviewStrings.CANCEL,
				onClick = onDismiss,
			)
		},
	)
}
