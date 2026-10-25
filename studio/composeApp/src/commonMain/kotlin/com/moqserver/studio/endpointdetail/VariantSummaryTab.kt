package com.moqserver.studio.endpointdetail

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moqserver.studio.designsystem.StudioDimens
import com.moqserver.studio.domain.VariantReferenceSyncPreference
import com.moqserver.studio.projectformat.EndpointDocument
import com.moqserver.studio.projectformat.ProjectVariant
import com.moqserver.studio.projectformat.isValidReferenceName
import com.moqserver.studio.ui.*
import io.github.maniramezan.kmpcomponents.FormTextField
import io.github.maniramezan.kmpcomponents.InfoTooltip
import io.github.maniramezan.kmpcomponents.ThemeCard
import io.github.maniramezan.kmpcomponents.ThemeCheckbox
import io.github.maniramezan.kmpcomponents.ThemeSwitch
import io.github.maniramezan.kmpcomponents.ThemeTextButton

private object VariantSummaryStrings {
    const val MORE_HEADERS_PREFIX = "+"
    const val MORE_HEADERS_SUFFIX = " more headers"
    const val VARIANT_NAME = "Variant Name"
    const val VARIANT_NAME_TOOLTIP = "Display name for this variant. Must be unique within the endpoint."
    const val NAME_CONFLICT = "Name must be unique within this endpoint"
    const val REFERENCE_NAME = "Reference Name"
    const val REFERENCE_NAME_TOOLTIP = "Code-friendly name used in tests. Must be unique within this API."
    const val REFERENCE_NAME_REQUIRED = "Reference name is required"
    const val REFERENCE_NAME_INVALID = "Use letters, numbers, and underscores only"
    const val REFERENCE_NAME_CONFLICT = "Reference name must be unique within this API"
    const val UPDATE_REFERENCE_TITLE = "Update reference name?"
    const val UPDATE_REFERENCE_MESSAGE =
        "This reference name still matches the old display name. Update it to match the new name too?"
    const val UPDATE_REFERENCE = "Update Reference"
    const val KEEP_REFERENCE = "Keep Current"
    const val REMEMBER_FOR_PROJECT = "Remember for this project"
    const val STATUS = "Status"
    const val STATUS_TOOLTIP = "HTTP status code this variant returns (100-599)."
    const val STATUS_RANGE = "100-599"
    const val DEFAULT = "Default"
    const val DEFAULT_TOOLTIP = "When enabled, this variant is returned by default when no matching criteria exist."
    const val ONLY_VARIANT = "Only variant"
	const val DELAY_LABEL = "Delay (ms)"
	const val DELAY_TOOLTIP = "Artificial delay in milliseconds before the response is sent."
	const val DESCRIPTION = "Description"
	const val DESCRIPTION_TOOLTIP = "Optional description for this variant, included in exported references when enabled."
	const val REMOVE_VARIANT = "Remove Variant"
	const val CALL_COUNT_LABEL = "Call #"
	const val CALL_COUNT_TOOLTIP =
		"1-indexed. When set, this variant is only eligible on the Nth call to its endpoint. Leave blank to match every call."
}

@Composable
internal fun VariantSummaryTab(
	endpoint: EndpointDocument,
	variant: ProjectVariant,
	projectPath: String,
	variantReferenceSyncPreference: VariantReferenceSyncPreference?,
	onUpdate: (ProjectVariant) -> Unit,
	onVariantReferenceSyncPreferenceChange: (String, VariantReferenceSyncPreference?) -> Unit,
	onRemove: () -> Unit,
	canRemove: Boolean,
) {
	Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.l)) {
		ApiCallSummaryCard(endpoint = endpoint, variant = variant)
		VariantEditingControls(
			endpoint = endpoint,
			variant = variant,
			projectPath = projectPath,
			variantReferenceSyncPreference = variantReferenceSyncPreference,
			onUpdate = onUpdate,
			onVariantReferenceSyncPreferenceChange = onVariantReferenceSyncPreferenceChange,
			onRemove = onRemove,
			canRemove = canRemove,
		)
	}
}

@Composable
private fun ApiCallSummaryCard(
	endpoint: EndpointDocument,
	variant: ProjectVariant,
) {
	ThemeCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
		Column(
			modifier = Modifier.padding(StudioDimens.l),
			verticalArrangement = Arrangement.spacedBy(StudioDimens.m),
		) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(StudioDimens.m),
			) {
				MethodBadge(endpoint.method)
				Text(
					endpoint.path,
					style = MaterialTheme.typography.bodyMedium,
					modifier = Modifier.weight(1f),
				)
				StatusBadge(variant.status)
			}

			val responseHeaders = variant.headers
			if (!responseHeaders.isNullOrEmpty()) {
				HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
				ResponseHeadersSummary(responseHeaders)
			}
		}
	}
}

@Composable
private fun ResponseHeadersSummary(responseHeaders: Map<String, String>) {
	val importantKeys = listOf("content-type", "content-length", "location", "cache-control")
	val displayed = responseHeaders.entries
		.sortedBy { entry ->
			val idx = importantKeys.indexOf(entry.key.lowercase())
			if (idx == -1) importantKeys.size else idx
		}
		.take(4)
	displayed.forEach { (key, value) ->
		Row(horizontalArrangement = Arrangement.spacedBy(StudioDimens.s)) {
			Text(
				key,
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.primary,
				fontWeight = FontWeight.SemiBold,
			)
			Text(
				value,
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
	if (responseHeaders.size > 4) {
		Text(
			"${VariantSummaryStrings.MORE_HEADERS_PREFIX}${responseHeaders.size - 4}${VariantSummaryStrings.MORE_HEADERS_SUFFIX}",
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

@Composable
private fun VariantEditingControls(
	endpoint: EndpointDocument,
	variant: ProjectVariant,
	projectPath: String,
	variantReferenceSyncPreference: VariantReferenceSyncPreference?,
	onUpdate: (ProjectVariant) -> Unit,
	onVariantReferenceSyncPreferenceChange: (String, VariantReferenceSyncPreference?) -> Unit,
	onRemove: () -> Unit,
	canRemove: Boolean,
) {
	val siblingNames = endpoint.variants.map { it.name }.filter { it != variant.name }.toSet()
	val siblingReferenceNames = endpoint.variants
		.filter { it != variant }
		.map(ProjectVariant::referenceName)
		.toSet()
	val nameConflict = variant.name.isNotBlank() && variant.name in siblingNames
	val referenceNameBlank = variant.referenceName.isBlank()
	val referenceNameInvalid = variant.referenceName.isNotBlank() && !isValidReferenceName(variant.referenceName)
	val referenceNameConflict = variant.referenceName.isNotBlank() && variant.referenceName in siblingReferenceNames
	val statusError = variant.status !in 100..599
	var pendingReferenceUpdate by remember(variant.referenceName, variant.name) { mutableStateOf<String?>(null) }
	var showReferenceSyncDialog by remember(variant.referenceName, variant.name) { mutableStateOf(false) }
	var rememberDecision by remember(variant.referenceName, variant.name) { mutableStateOf(false) }
	var nameFieldFocused by remember { mutableStateOf(false) }

	fun applyNameUpdate(updatedName: String, updatedReferenceName: String = variant.referenceName) {
		onUpdate(variant.copy(name = updatedName, referenceName = updatedReferenceName))
	}

	fun persistPreference(preference: VariantReferenceSyncPreference?) {
		if (projectPath.isNotBlank()) {
			onVariantReferenceSyncPreferenceChange(projectPath, preference)
		}
	}

	Row(horizontalArrangement = Arrangement.spacedBy(StudioDimens.l)) {
		FormTextField(
			value = variant.name,
			onValueChange = { updatedName ->
				val result = applyVariantNameChange(
					newName = updatedName,
					currentReferenceName = variant.referenceName,
					previousName = variant.name,
					existingReferenceNames = siblingReferenceNames + variant.referenceName,
					preference = variantReferenceSyncPreference,
				)
				if (result.shouldPrompt) {
					pendingReferenceUpdate = syncedVariantReferenceName(
						newName = updatedName,
						currentReferenceName = variant.referenceName,
						existingReferenceNames = siblingReferenceNames + variant.referenceName,
					)
					onUpdate(variant.copy(name = updatedName))
				} else {
					pendingReferenceUpdate = null
					showReferenceSyncDialog = false
					applyNameUpdate(updatedName, result.newReferenceName)
				}
			},
			modifier = Modifier
				.weight(1f)
				.onFocusChanged { focusState ->
					val lostFocus = nameFieldFocused && !focusState.isFocused
					nameFieldFocused = focusState.isFocused
					if (lostFocus && pendingReferenceUpdate != null) {
						showReferenceSyncDialog = true
					}
				},
			label = VariantSummaryStrings.VARIANT_NAME,
			singleLine = true,
			errorText = if (nameConflict) VariantSummaryStrings.NAME_CONFLICT else null,
			trailingContent = { InfoTooltip(VariantSummaryStrings.VARIANT_NAME_TOOLTIP) },
		)
		FormTextField(
			value = variant.referenceName,
			onValueChange = {
				pendingReferenceUpdate = null
				showReferenceSyncDialog = false
				rememberDecision = false
				onUpdate(variant.copy(referenceName = it))
			},
			label = VariantSummaryStrings.REFERENCE_NAME,
			singleLine = true,
			modifier = Modifier.weight(1f),
			errorText = when {
				referenceNameBlank -> VariantSummaryStrings.REFERENCE_NAME_REQUIRED
				referenceNameInvalid -> VariantSummaryStrings.REFERENCE_NAME_INVALID
				referenceNameConflict -> VariantSummaryStrings.REFERENCE_NAME_CONFLICT
				else -> null
			},
			trailingContent = { InfoTooltip(VariantSummaryStrings.REFERENCE_NAME_TOOLTIP) },
		)
        FormTextField(
            value = variant.status.toString(),
            onValueChange = { value ->
                when (val update = acceptedIntInput(value, variant.status)) {
                    is NumericInputUpdate.Accepted -> onUpdate(variant.copy(status = update.value))
                    NumericInputUpdate.Ignored -> Unit
                }
            },
            label = VariantSummaryStrings.STATUS,
            singleLine = true,
            modifier = Modifier.width(130.dp),
            errorText = if (statusError) VariantSummaryStrings.STATUS_RANGE else null,
            trailingContent = { InfoTooltip(VariantSummaryStrings.STATUS_TOOLTIP) },
        )
	}

	FormTextField(
		value = variant.description ?: "",
		onValueChange = { onUpdate(variant.copy(description = it.ifBlank { null })) },
		label = VariantSummaryStrings.DESCRIPTION,
		singleLine = false,
		modifier = Modifier.fillMaxWidth().heightIn(
			min = StudioDimens.multilineFieldMinHeight,
			max = StudioDimens.multilineFieldMaxHeight,
		),
		trailingContent = { InfoTooltip(VariantSummaryStrings.DESCRIPTION_TOOLTIP) },
	)

	if (showReferenceSyncDialog && pendingReferenceUpdate != null) {
		AlertDialog(
			onDismissRequest = {
				showReferenceSyncDialog = false
				rememberDecision = false
			},
			title = { Text(VariantSummaryStrings.UPDATE_REFERENCE_TITLE) },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.m)) {
					Text(VariantSummaryStrings.UPDATE_REFERENCE_MESSAGE)
					ThemeCheckbox(
						checked = rememberDecision,
						onCheckedChange = { rememberDecision = it },
						label = VariantSummaryStrings.REMEMBER_FOR_PROJECT,
					)
				}
			},
			confirmButton = {
				ThemeTextButton(
					text = VariantSummaryStrings.UPDATE_REFERENCE,
					onClick = {
						if (rememberDecision) {
							persistPreference(VariantReferenceSyncPreference.ALWAYS_UPDATE)
						}
						onUpdate(variant.copy(referenceName = pendingReferenceUpdate ?: variant.referenceName))
						pendingReferenceUpdate = null
						showReferenceSyncDialog = false
						rememberDecision = false
					},
				)
			},
			dismissButton = {
				ThemeTextButton(
					text = VariantSummaryStrings.KEEP_REFERENCE,
					onClick = {
						if (rememberDecision) {
							persistPreference(VariantReferenceSyncPreference.NEVER_UPDATE)
						}
						pendingReferenceUpdate = null
						showReferenceSyncDialog = false
						rememberDecision = false
					},
				)
			},
		)
	}

	DefaultAndDelayRow(variant = variant, onUpdate = onUpdate, canRemove = canRemove)

	if (canRemove) {
		Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
			ThemeTextButton(
				text = VariantSummaryStrings.REMOVE_VARIANT,
				onClick = onRemove,
				destructive = true,
			)
		}
	}
}

@Composable
private fun DefaultAndDelayRow(
	variant: ProjectVariant,
	onUpdate: (ProjectVariant) -> Unit,
	canRemove: Boolean,
) {
	Row(
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(StudioDimens.m),
	) {
		ThemeSwitch(
			checked = variant.isDefault == true,
			onCheckedChange = { onUpdate(variant.copy(isDefault = if (it) true else null)) },
			label = VariantSummaryStrings.DEFAULT,
			tooltip = VariantSummaryStrings.DEFAULT_TOOLTIP,
			enabled = canRemove,
			supportingText = if (canRemove) null else VariantSummaryStrings.ONLY_VARIANT,
			modifier = Modifier.weight(1f),
		)
        FormTextField(
            value = (variant.delayMs ?: 0).toString(),
            onValueChange = {
                when (val update = acceptedPositiveIntOrNullInput(it, variant.delayMs)) {
                    is NumericInputUpdate.Accepted -> onUpdate(variant.copy(delayMs = update.value))
                    NumericInputUpdate.Ignored -> Unit
                }
            },
            label = VariantSummaryStrings.DELAY_LABEL,
            singleLine = true,
            modifier = Modifier.width(StudioDimens.tableNameColumnWidth),
            trailingContent = { InfoTooltip(VariantSummaryStrings.DELAY_TOOLTIP) },
        )
        FormTextField(
            value = (variant.callCount ?: 0).toString(),
            onValueChange = {
                when (val update = acceptedPositiveIntOrNullInput(it, variant.callCount)) {
                    is NumericInputUpdate.Accepted -> onUpdate(variant.copy(callCount = update.value))
                    NumericInputUpdate.Ignored -> Unit
                }
            },
            label = VariantSummaryStrings.CALL_COUNT_LABEL,
            singleLine = true,
            modifier = Modifier.width(StudioDimens.tableNameColumnWidth),
            trailingContent = { InfoTooltip(VariantSummaryStrings.CALL_COUNT_TOOLTIP) },
        )
	}
}
