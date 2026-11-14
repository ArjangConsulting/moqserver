package com.moqserver.studio.endpointdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.arjangconsulting.codeeditor.CodeEditor
import com.arjangconsulting.codeeditor.CodeEditorDefaults
import com.arjangconsulting.codeeditor.CodeEditorLabels
import com.arjangconsulting.codeeditor.CodeEditorOptions
import com.arjangconsulting.codeeditor.CodeEditorState
import com.arjangconsulting.codeeditor.JsonLanguageService
import com.arjangconsulting.codeeditor.JsonRules
import com.arjangconsulting.codeeditor.LanguageService
import com.arjangconsulting.codeeditor.PlainText
import com.arjangconsulting.codeeditor.TabBehavior
import com.moqserver.studio.designsystem.StudioColors
import com.moqserver.studio.designsystem.StudioDimens
import com.moqserver.studio.designsystem.StudioTheme
import com.moqserver.studio.designsystem.StudioThemeMode
import com.moqserver.studio.designsystem.studioDarkColors
import com.moqserver.studio.projectformat.ProjectVariant
import com.moqserver.studio.ui.*
import io.github.maniramezan.kmpcomponents.CopyButton
import io.github.maniramezan.kmpcomponents.CopyButtonLabels
import io.github.maniramezan.kmpcomponents.FormTextField
import io.github.maniramezan.kmpcomponents.InlineProgressIndicator
import io.github.maniramezan.kmpcomponents.KeyValueRow
import io.github.maniramezan.kmpcomponents.SecondaryButton
import io.github.maniramezan.kmpcomponents.SegmentedControl
import io.github.maniramezan.kmpcomponents.StatusMessage
import io.github.maniramezan.kmpcomponents.StatusTone
import io.github.maniramezan.kmpcomponents.ThemeIconButton
import io.github.maniramezan.kmpcomponents.ThemeTextButton
import kotlinx.coroutines.launch

private object BodyTabStrings {
	const val RESPONSE_BODY = "Response body"
    const val FILE_NOT_FOUND = "File not found."
    const val NO_BODY = "This variant has no response body yet."
    const val VALIDATE_JSON = "Validate JSON"
    const val FORMAT_JSON = "Format JSON"
	const val CANCEL_BODY_EDIT = "Cancel body edit"
	const val SAVE_BODY_EDIT = "Save body edit"
	const val EDIT_BODY = "Edit body"
	const val COPIED_BODY = "Body copied"
	const val COPY_BODY = "Copy body"
	const val GENERATE_BODY = "Generate body"
	const val AI_PROMPT_TITLE = "Generate response body"
	const val AI_PROMPT_LABEL = "Prompt"
	const val AI_PROMPT_HELP = "AI can use the current status code, variant name, and API URL to generate this response body."
	const val AI_GENERATING = "Generating body with AI..."
	const val AI_ERROR_PREFIX = "AI error: "
	const val AI_CONTEXT_ENDPOINT = "Endpoint"
	const val AI_CONTEXT_VARIANT = "Variant"
	const val AI_CONTEXT_STATUS = "Status"
	const val AI_CONTEXT_PROVIDER = "AI"
	const val AI_GENERATE_ACTION = "Generate"
}

private val bodyPanelColor = StudioColors.editorPanelBackground.copy(alpha = 0.97f)

/**
 * Code editor colors for the always-dark body panel. Chrome comes from Studio's dark scheme, so line
 * numbers, selection, and popups stay readable in the light app theme too; token colors follow the
 * panel background and use the editor's dark syntax palette.
 */
internal val bodyEditorColors = CodeEditorDefaults.colorsFrom(studioDarkColors()).copy(
	background = StudioColors.editorPanelBackground,
	foreground = StudioColors.editorPanelContent,
	gutterBackground = StudioColors.editorPanelBackground,
)
private val bodyPanelBorderColor = StudioColors.editorPanelBorder

@Composable
internal fun BodyTab(
	endpointMethod: String,
	endpointPath: String,
	variant: ProjectVariant,
	aiProviderLabel: String?,
	canGenerateWithAi: Boolean,
	isGeneratingWithAi: Boolean,
	generationError: String?,
	onGenerateBody: (String) -> Unit,
	projectPath: String = "",
	endpointId: String = "$endpointMethod $endpointPath",
	readFixture: (String, String) -> String? = { _, _ -> null },
	onUpdate: (ProjectVariant) -> Unit,
) {
	key(projectPath, endpointId, variant.name, variant.referenceName) {
		val bodyFile = variant.bodyFile
		val body = variant.body
		var showAiPrompt by remember(variant.referenceName) { mutableStateOf(false) }
		var aiPrompt by remember(variant.referenceName) { mutableStateOf("") }

		val fileContent = if (bodyFile != null) {
			remember(projectPath, bodyFile, readFixture) {
				runCatching { readFixture(projectPath, bodyFile) }.getOrNull()
			}
		} else {
			null
		}
		var selectedFormat by remember(variant.name) {
			mutableStateOf(if (body != null && variant.isJsonBody()) BodyFormat.JSON else BodyFormat.RAW)
		}
		val currentText = when {
			fileContent != null -> fileContent
			body != null -> when (selectedFormat) {
				BodyFormat.JSON -> yamlValueToJsonString(body)
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
		val editorSession = remember(variant.name, bodyFile, body, editableText) { BodyEditorSession(editableText.orEmpty()) }
		DisposableEffect(editorSession) { onDispose { editorSession.dispose() } }
		val isEditing = editorSession.isEditing
		val formatScope = rememberCoroutineScope()
		val jsonService = remember { JsonLanguageService() }
		var formatBeforeEdit by remember(variant.name) {
			mutableStateOf(if (body != null && variant.isJsonBody()) BodyFormat.JSON else BodyFormat.RAW)
		}
		val validationError = editorSession.validationError
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
					aiPrompt = ""
					showAiPrompt = false
				},
			)
		}

		LaunchedEffect(editorSession, editorSession.state.version) { editorSession.clearValidationError() }

		LaunchedEffect(validationError) {
			if (validationError != null) {
				withFrameNanos { }
				validationErrorRequester.bringIntoView()
			}
		}

		fun startEditing() {
			formatBeforeEdit = selectedFormat
			selectedFormat = BodyFormat.RAW
			editorSession.start()
		}

		fun cancelEditing() {
			selectedFormat = formatBeforeEdit
			editorSession.cancel()
		}

		fun formatJson() {
			formatScope.launch { editorSession.format(jsonService) }
		}

		fun saveEdits() {
			editorSession.save(variant, isJsonBody)?.let { updated ->
				selectedFormat = formatBeforeEdit
				if (updated != variant) onUpdate(updated)
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
					BodyFormatTabs(selectedFormat = selectedFormat, enabled = !isEditing, onSelect = { selectedFormat = it })
					if (isGeneratingWithAi) InlineProgressIndicator(label = BodyTabStrings.AI_GENERATING)
					generationError?.let { error ->
						StatusMessage(message = BodyTabStrings.AI_ERROR_PREFIX + error, tone = StatusTone.ERROR)
					}
					BodyEditorPanel(
						currentText = currentText,
						isEditing = isEditing,
						isJsonBody = isJsonBody,
						editorState = editorSession.state,
						jsonService = jsonService,
						canGenerateWithAi = canGenerateWithAi,
						isGeneratingWithAi = isGeneratingWithAi,
						selectedFormat = selectedFormat,
						onEdit = ::startEditing,
						onGenerateBody = {
							showAiPrompt = true
						},
						onCancel = ::cancelEditing,
						onSave = ::saveEdits,
						onValidateJson = editorSession::validate,
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
}

@Composable
private fun BodyFormatTabs(
	selectedFormat: BodyFormat,
	enabled: Boolean,
	onSelect: (BodyFormat) -> Unit,
) {
	SegmentedControl(
		options = BodyFormat.entries.map { it.label },
		enabled = enabled,
		selectedIndex = selectedFormat.ordinal,
		onOptionSelected = { onSelect(BodyFormat.entries[it]) },
	)
}

@Composable
private fun BodyEditorPanel(
	currentText: String,
	isEditing: Boolean,
	isJsonBody: Boolean,
	editorState: CodeEditorState,
	jsonService: JsonLanguageService,
	canGenerateWithAi: Boolean,
	isGeneratingWithAi: Boolean,
	selectedFormat: BodyFormat,
	onEdit: () -> Unit,
	onGenerateBody: () -> Unit,
	onCancel: () -> Unit,
	onSave: () -> Unit,
	onValidateJson: () -> Unit,
	onFormatJson: () -> Unit,
) {
	StudioTheme(themeMode = StudioThemeMode.DARK) {
		CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
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
					border = androidx.compose.foundation.BorderStroke(StudioDimens.borderWidth, bodyPanelBorderColor),
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
							copyText = if (isEditing) editorState.text else currentText,
							isEditing = isEditing,
							canEdit = true,
							canGenerateWithAi = canGenerateWithAi,
							isGeneratingWithAi = isGeneratingWithAi,
							isJsonBody = isJsonBody,
							onEdit = onEdit,
							onGenerateBody = onGenerateBody,
							onCancel = onCancel,
							onSave = onSave,
							onValidateJson = onValidateJson,
							onFormatJson = onFormatJson,
						)
					}

					val displayState = remember(currentText) { CodeEditorState(currentText) }
					val showJson = if (isEditing) isJsonBody else selectedFormat == BodyFormat.JSON
					CodeEditor(
						state = if (isEditing) editorState else displayState,
						readOnly = !isEditing,
						language = if (showJson) JsonRules else PlainText,
						languageService = if (showJson) jsonService else LanguageService.None,
						tabBehavior = TabBehavior.MOVE_FOCUS,
						options = CodeEditorOptions(softWrap = true),
						modifier = Modifier
							.fillMaxWidth()
							.height(StudioDimens.bodyEditorHeight)
							.padding(top = StudioDimens.xxs, start = StudioDimens.s, end = StudioDimens.xs, bottom = StudioDimens.xs),
						colors = bodyEditorColors,
						labels = CodeEditorLabels(editorDescription = BodyTabStrings.RESPONSE_BODY),
					)
				}
			}
		}
	}
}

@Composable
private fun BodyTabActions(
	copyText: String,
	isEditing: Boolean,
	canEdit: Boolean,
	canGenerateWithAi: Boolean,
	isGeneratingWithAi: Boolean,
	isJsonBody: Boolean,
	onEdit: () -> Unit,
	onGenerateBody: () -> Unit,
	onCancel: () -> Unit,
	onSave: () -> Unit,
	onValidateJson: () -> Unit,
	onFormatJson: () -> Unit,
) {
	FlowRow(
		horizontalArrangement = Arrangement.spacedBy(StudioDimens.m, Alignment.End),
		verticalArrangement = Arrangement.spacedBy(StudioDimens.xs),
		itemVerticalAlignment = Alignment.CenterVertically,
	) {
		if (isEditing && isJsonBody) {
			SecondaryButton(
				text = BodyTabStrings.VALIDATE_JSON,
				onClick = onValidateJson,
			)
			SecondaryButton(
				text = BodyTabStrings.FORMAT_JSON,
				onClick = onFormatJson,
			)
		}

		CopyButton(
			text = copyText,
			enabled = copyText.isNotEmpty(),
			labels = CopyButtonLabels(copy = BodyTabStrings.COPY_BODY, copied = BodyTabStrings.COPIED_BODY),
		)

		when {
			isEditing -> {
				ThemeIconButton(
					icon = rememberVectorPainter(Icons.Outlined.Close),
					contentDescription = BodyTabStrings.CANCEL_BODY_EDIT,
					onClick = onCancel,
				)
				ThemeIconButton(
					icon = rememberVectorPainter(Icons.Outlined.Save),
					contentDescription = BodyTabStrings.SAVE_BODY_EDIT,
					onClick = onSave,
				)
			}
			canEdit -> {
				if (canGenerateWithAi) {
					SecondaryButton(
						text = BodyTabStrings.GENERATE_BODY,
						onClick = onGenerateBody,
						enabled = !isGeneratingWithAi,
					)
				}
				ThemeIconButton(
					icon = rememberVectorPainter(Icons.Outlined.Edit),
					contentDescription = BodyTabStrings.EDIT_BODY,
					onClick = onEdit,
				)
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
				FormTextField(
					value = prompt,
					onValueChange = onPromptChange,
					label = BodyTabStrings.AI_PROMPT_LABEL,
					modifier = Modifier.fillMaxWidth().heightIn(
						min = StudioDimens.multilineFieldMinHeight,
						max = StudioDimens.multilineFieldMaxHeight,
					),
					singleLine = false,
				)
			}
		},
		confirmButton = {
			ThemeTextButton(
				text = BodyTabStrings.AI_GENERATE_ACTION,
				onClick = onConfirm,
				enabled = prompt.isNotBlank(),
			)
		},
		dismissButton = {
			ThemeTextButton(
				text = "Cancel",
				onClick = onDismiss,
			)
		},
	)
}

@Composable
private fun AIBodyContextRow(
	label: String,
	value: String,
) {
	KeyValueRow(key = label, value = value)
}
