package com.moqserver.studio.endpointdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

import com.moqserver.studio.StudioColors
import com.moqserver.studio.StudioDimens
import com.moqserver.studio.editor.JsonCodeEditor
import com.moqserver.studio.projectformat.ProjectVariant
import com.moqserver.studio.projectformat.YamlValue
import com.moqserver.studio.ui.*
import java.awt.Color as AwtColor

private object BodyTabStrings {
    const val FILE_NOT_FOUND = "File not found."
    const val NO_BODY = "This variant has no response body yet."
    const val VALIDATE_JSON = "Validate JSON"
    const val FORMAT_JSON = "Format JSON"
	const val CANCEL_BODY_EDIT = "Cancel body edit"
	const val SAVE_BODY_EDIT = "Save body edit"
	const val EDIT_BODY = "Edit body"
	const val GENERATE_BODY = "Generate body"
	const val AI_PROMPT_TITLE = "Generate response body"
	const val AI_PROMPT_LABEL = "Prompt"
	const val AI_PROMPT_HELP = "AI can use the current status code, variant name, and API URL to generate this response body."
	const val AI_CONTEXT_ENDPOINT = "Endpoint"
	const val AI_CONTEXT_VARIANT = "Variant"
	const val AI_CONTEXT_STATUS = "Status"
	const val AI_CONTEXT_PROVIDER = "AI"
	const val AI_GENERATE_ACTION = "Generate"
}

private val bodyPanelColor = StudioColors.editorPanelBackground.copy(alpha = 0.97f)
private val bodyPanelBorderColor = StudioColors.editorPanelBorder
private val bodyPanelContentColor = StudioColors.editorPanelContent
/** Keep in sync with [StudioColors.editorPanelBackground] — AWT equivalent (0x161B22). */
private val bodyEditorBackgroundColor = AwtColor(0x16, 0x1B, 0x22)
/** Keep in sync with [StudioColors.editorPanelContent] — AWT equivalent (0xE6EDF3). */
private val bodyEditorForegroundColor = AwtColor(0xE6, 0xED, 0xF3)

@Composable
internal fun BodyTab(
	endpointMethod: String,
	endpointPath: String,
	variant: ProjectVariant,
	aiProviderLabel: String?,
	canGenerateWithAi: Boolean,
	onGenerateBody: (String) -> Unit,
	projectPath: String = "",
	onUpdate: (ProjectVariant) -> Unit,
) {
	val bodyFile = variant.bodyFile
	val body = variant.body
	var showAiPrompt by remember(variant.referenceName) { mutableStateOf(false) }
	var aiPrompt by remember(variant.referenceName) { mutableStateOf("") }

	val fileContent = if (bodyFile != null) {
		remember(projectPath, bodyFile) {
			runCatching {
				java.io.File(projectPath, bodyFile).takeIf { it.isFile }?.readText()
			}.getOrNull()
		}
	} else {
		null
	}
	var selectedFormat by remember(variant.name) { mutableStateOf(if (body != null && variant.isJsonBody()) BodyFormat.JSON else BodyFormat.RAW) }
	val currentText = when {
		fileContent != null -> fileContent
		body != null -> when (selectedFormat) {
			BodyFormat.JSON -> yamlValueToJsonString(body)
			BodyFormat.PLAIN_TEXT -> yamlValueToPlainText(body)
			BodyFormat.RAW -> yamlValueToDisplayString(body)
		}
		else -> null
	}
	val editableText = when {
		fileContent != null -> fileContent
		body != null -> editableBodyText(variant, body)
		else -> null
	}
	val isJsonBody = variant.isJsonBody(editableText)
	var isEditing by remember(variant.name, selectedFormat, bodyFile, body) { mutableStateOf(false) }
	var draftText by remember(variant.name, bodyFile, body) { mutableStateOf(editableText.orEmpty()) }
	var formatBeforeEdit by remember(variant.name) { mutableStateOf(if (body != null && variant.isJsonBody()) BodyFormat.JSON else BodyFormat.RAW) }
	var validationError by remember(variant.name, selectedFormat, bodyFile, body) { mutableStateOf<String?>(null) }
	val validationErrorRequester = remember { BringIntoViewRequester() }

	if (showAiPrompt) {
		AIBodyPromptDialog(
			endpointMethod = endpointMethod,
			endpointPath = endpointPath,
			variant = variant,
			providerLabel = aiProviderLabel,
			prompt = aiPrompt,
			onPromptChange = { aiPrompt = it },
			onDismiss = { showAiPrompt = false },
			onConfirm = {
				onGenerateBody(aiPrompt.trim())
				showAiPrompt = false
			},
		)
	}

	fun setValidationError(message: String?) {
		validationError = message
	}

	LaunchedEffect(validationError) {
		if (validationError != null) {
			withFrameNanos { }
			validationErrorRequester.bringIntoView()
		}
	}

	fun startEditing() {
		formatBeforeEdit = selectedFormat
		selectedFormat = BodyFormat.RAW
		draftText = editableText.orEmpty()
		validationError = null
		isEditing = true
	}

	fun cancelEditing() {
		selectedFormat = formatBeforeEdit
		draftText = editableText.orEmpty()
		validationError = null
		isEditing = false
	}

	fun validateJson() {
		setValidationError(validateJsonBodyText(draftText))
	}

	fun formatJson() {
		formatJsonBodyText(draftText)
			.onSuccess { formatted ->
				draftText = formatted
				setValidationError(null)
			}
			.onFailure { error ->
				setValidationError(invalidJsonMessage(error))
			}
	}

	fun saveEdits() {
		when {
			isJsonBody -> {
				parseJsonBodyText(draftText)
					.onSuccess { parsedBody ->
						selectedFormat = formatBeforeEdit
						onUpdate(variant.copy(body = parsedBody, bodyFile = null))
						setValidationError(null)
						isEditing = false
					}
					.onFailure { error ->
						setValidationError(invalidJsonMessage(error))
					}
			}
			else -> {
				selectedFormat = formatBeforeEdit
				val updatedBody = parseEditableText(draftText, body)
				onUpdate(variant.copy(body = updatedBody, bodyFile = null))
				setValidationError(null)
				isEditing = false
			}
		}
	}

	Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.l)) {
		when {
			bodyFile != null && fileContent == null -> {
				Text(
					text = BodyTabStrings.FILE_NOT_FOUND,
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.error,
				)
			}
			currentText != null -> {
				BodyFormatTabs(selectedFormat = selectedFormat, onSelect = { selectedFormat = it })
				BodyEditorPanel(
					currentText = currentText,
					isEditing = isEditing,
					isJsonBody = isJsonBody,
					draftText = draftText,
					canGenerateWithAi = canGenerateWithAi,
					onDraftTextChange = { updated ->
						draftText = updated
						if (validationError != null && isJsonBody) {
							validationError = validateJsonBodyText(updated)
						}
					},
					selectedFormat = selectedFormat,
					onEdit = ::startEditing,
					onGenerateBody = {
						validationError = null
						showAiPrompt = true
					},
					onCancel = ::cancelEditing,
					onSave = ::saveEdits,
					onValidateJson = ::validateJson,
					onFormatJson = ::formatJson,
				)
				validationError?.let { error ->
					Text(
						text = error,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.error,
						modifier = Modifier.bringIntoViewRequester(validationErrorRequester),
					)
				}
			}
			else -> {
			Text(
				BodyTabStrings.NO_BODY,
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

@Composable
private fun BodyFormatTabs(
	selectedFormat: BodyFormat,
	onSelect: (BodyFormat) -> Unit,
) {
	TabStrip {
		BodyFormat.entries.forEach { format ->
			TabChip(
				label = format.label,
				selected = format == selectedFormat,
				onClick = { onSelect(format) },
			)
		}
	}
}

@Composable
private fun BodyEditorPanel(
	currentText: String,
	isEditing: Boolean,
	isJsonBody: Boolean,
	draftText: String,
	canGenerateWithAi: Boolean,
	onDraftTextChange: (String) -> Unit,
	selectedFormat: BodyFormat,
	onEdit: () -> Unit,
	onGenerateBody: () -> Unit,
	onCancel: () -> Unit,
	onSave: () -> Unit,
	onValidateJson: () -> Unit,
	onFormatJson: () -> Unit,
) {
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(StudioDimens.xl))
			.background(bodyPanelColor)
			.padding(start = StudioDimens.l, top = StudioDimens.m, end = StudioDimens.m, bottom = StudioDimens.l),
	) {
		Surface(
			modifier = Modifier.matchParentSize(),
			shape = RoundedCornerShape(StudioDimens.xl),
			color = Color.Transparent,
			border = androidx.compose.foundation.BorderStroke(1.dp, bodyPanelBorderColor),
		) {}
		Column(
			modifier = Modifier.fillMaxWidth(),
			verticalArrangement = Arrangement.spacedBy(StudioDimens.xs),
		) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(top = StudioDimens.xxs, end = StudioDimens.xxs),
				horizontalArrangement = Arrangement.End,
			) {
				BodyTabActions(
					isEditing = isEditing,
					canEdit = true,
					canGenerateWithAi = canGenerateWithAi,
					isJsonBody = isJsonBody,
					onEdit = onEdit,
					onGenerateBody = onGenerateBody,
					onCancel = onCancel,
					onSave = onSave,
					onValidateJson = onValidateJson,
					onFormatJson = onFormatJson,
				)
			}

			if (isEditing) {
				OutlinedTextField(
					value = draftText,
					onValueChange = onDraftTextChange,
					readOnly = false,
					minLines = 8,
					maxLines = 18,
					colors = bodyEditorFieldColors(),
					modifier = Modifier
						.fillMaxWidth()
						.padding(top = StudioDimens.xxs, start = StudioDimens.s, end = StudioDimens.xs, bottom = StudioDimens.xs),
				)
			} else {
				when (selectedFormat) {
					BodyFormat.JSON -> {
						JsonCodeEditor(
							text = currentText,
							onTextChange = {},
							readOnly = true,
							modifier = Modifier
								.fillMaxWidth()
								.height(280.dp)
								.padding(top = StudioDimens.xxs, start = StudioDimens.s, end = StudioDimens.xs, bottom = StudioDimens.xs),
							backgroundColor = bodyEditorBackgroundColor,
							foregroundColor = bodyEditorForegroundColor,
						)
					}
					BodyFormat.PLAIN_TEXT, BodyFormat.RAW -> {
						OutlinedTextField(
							value = currentText,
							onValueChange = {},
							readOnly = true,
							minLines = 8,
							maxLines = 18,
							colors = bodyEditorFieldColors(),
							modifier = Modifier
								.fillMaxWidth()
								.padding(top = StudioDimens.xxs, start = StudioDimens.s, end = StudioDimens.xs, bottom = StudioDimens.xs),
						)
					}
				}
			}
		}
	}
}

@Composable
private fun bodyEditorFieldColors() = OutlinedTextFieldDefaults.colors(
	focusedContainerColor = Color.Transparent,
	unfocusedContainerColor = Color.Transparent,
	disabledContainerColor = Color.Transparent,
	focusedTextColor = bodyPanelContentColor,
	unfocusedTextColor = bodyPanelContentColor,
	disabledTextColor = bodyPanelContentColor,
	cursorColor = bodyPanelContentColor,
	focusedBorderColor = Color.Transparent,
	unfocusedBorderColor = Color.Transparent,
	disabledBorderColor = Color.Transparent,
)

@Composable
private fun BodyTabActions(
	isEditing: Boolean,
	canEdit: Boolean,
	canGenerateWithAi: Boolean,
	isJsonBody: Boolean,
	onEdit: () -> Unit,
	onGenerateBody: () -> Unit,
	onCancel: () -> Unit,
	onSave: () -> Unit,
	onValidateJson: () -> Unit,
	onFormatJson: () -> Unit,
) {
	Row(
		horizontalArrangement = Arrangement.spacedBy(StudioDimens.m),
		verticalAlignment = Alignment.CenterVertically,
	) {
		if (isEditing && isJsonBody) {
			OutlinedButton(onClick = onValidateJson, contentPadding = PaddingValues(horizontal = StudioDimens.l, vertical = StudioDimens.m)) {
				Text(BodyTabStrings.VALIDATE_JSON)
			}
			FilledTonalButton(onClick = onFormatJson, contentPadding = PaddingValues(horizontal = StudioDimens.l, vertical = StudioDimens.m)) {
				Text(BodyTabStrings.FORMAT_JSON)
			}
		}

		when {
			isEditing -> {
				IconButton(onClick = onCancel) {
					Icon(Icons.Outlined.Close, contentDescription = BodyTabStrings.CANCEL_BODY_EDIT)
				}
				FilledTonalIconButton(onClick = onSave) {
					Icon(Icons.Outlined.Save, contentDescription = BodyTabStrings.SAVE_BODY_EDIT)
				}
			}
			canEdit -> {
				if (canGenerateWithAi) {
					FilledTonalButton(
						onClick = onGenerateBody,
						contentPadding = PaddingValues(horizontal = StudioDimens.l, vertical = StudioDimens.m),
					) {
						Icon(Icons.Outlined.AutoAwesome, contentDescription = BodyTabStrings.GENERATE_BODY)
						Spacer(modifier = Modifier.width(StudioDimens.s))
						Text(BodyTabStrings.GENERATE_BODY)
					}
				}
				FilledTonalIconButton(onClick = onEdit) {
					Icon(Icons.Outlined.Edit, contentDescription = BodyTabStrings.EDIT_BODY)
				}
			}
		}
	}
}

@Composable
private fun AIBodyPromptDialog(
	endpointMethod: String,
	endpointPath: String,
	variant: ProjectVariant,
	providerLabel: String?,
	prompt: String,
	onPromptChange: (String) -> Unit,
	onDismiss: () -> Unit,
	onConfirm: () -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(BodyTabStrings.AI_PROMPT_TITLE) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.l)) {
				Text(
					text = BodyTabStrings.AI_PROMPT_HELP,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				AIBodyContextRow(BodyTabStrings.AI_CONTEXT_ENDPOINT, "$endpointMethod $endpointPath")
				AIBodyContextRow(BodyTabStrings.AI_CONTEXT_VARIANT, variant.name)
				AIBodyContextRow(BodyTabStrings.AI_CONTEXT_STATUS, variant.status.toString())
				providerLabel?.let { AIBodyContextRow(BodyTabStrings.AI_CONTEXT_PROVIDER, it) }
				OutlinedTextField(
					value = prompt,
					onValueChange = onPromptChange,
					label = { Text(BodyTabStrings.AI_PROMPT_LABEL) },
					minLines = 4,
					maxLines = 8,
					modifier = Modifier.fillMaxWidth(),
				)
			}
		},
		confirmButton = {
			TextButton(onClick = onConfirm, enabled = prompt.isNotBlank()) {
				Text(BodyTabStrings.AI_GENERATE_ACTION)
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) {
				Text("Cancel")
			}
		},
	)
}

@Composable
private fun AIBodyContextRow(
	label: String,
	value: String,
) {
	Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.xxs)) {
		Text(
			text = label,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Text(
			text = value,
			style = MaterialTheme.typography.bodyMedium,
		)
	}
}
