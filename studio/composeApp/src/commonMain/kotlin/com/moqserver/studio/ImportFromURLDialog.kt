package com.moqserver.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.maniramezan.kmpcomponents.DisclosureCard
import io.github.maniramezan.kmpcomponents.FormTextField
import io.github.maniramezan.kmpcomponents.InlineProgressIndicator
import io.github.maniramezan.kmpcomponents.MenuPicker
import io.github.maniramezan.kmpcomponents.PrimaryButton
import io.github.maniramezan.kmpcomponents.SecondaryButton
import io.github.maniramezan.kmpcomponents.SecretTextField
import io.github.maniramezan.kmpcomponents.StatusMessage
import io.github.maniramezan.kmpcomponents.StatusTone

private object ImportURLStrings {
	const val INVALID_URL = "Enter a valid spec URL"
	const val EXPANDED = "Authentication expanded"
	const val COLLAPSED = "Authentication collapsed"
	const val OPENAPI_DIALOG_TITLE = "Import OpenAPI from URL"
	const val SWAGGER_DIALOG_TITLE = "Import Swagger from URL"
	const val UPDATE_OPENAPI_DIALOG_TITLE = "Update from OpenAPI URL"
	const val UPDATE_SWAGGER_DIALOG_TITLE = "Update from Swagger URL"
	const val URL_LABEL = "Spec URL"
	const val URL_PLACEHOLDER = "https://example.com/api/docs or https://example.com/openapi.json"
	const val URL_HELPER = "Enter a direct URL to a spec file (.json/.yaml) or a Swagger UI / API docs page."
	const val AUTH_SECTION = "Authentication"
	const val AUTH_TYPE_NONE = "None"
	const val AUTH_TYPE_BEARER = "Bearer Token"
	const val AUTH_TYPE_BASIC = "Basic Auth"
	const val TOKEN_LABEL = "Token"
	const val TOKEN_PLACEHOLDER = "Enter bearer token"
	const val USERNAME_LABEL = "Username"
	const val PASSWORD_LABEL = "Password"
	const val IMPORT_BUTTON = "Import"
	const val UPDATE_BUTTON = "Update"
	const val CANCEL_BUTTON = "Cancel"
	const val FETCHING = "Fetching spec..."
	const val AUTH_HELPER = "Add credentials if the API docs require authentication."
	const val SHOW_SECRET = "Show"
	const val HIDE_SECRET = "Hide"
}

/**
 * The authentication type selected in the URL import dialog.
 */
enum class URLAuthType(val label: String) {
	NONE(ImportURLStrings.AUTH_TYPE_NONE),
	BEARER(ImportURLStrings.AUTH_TYPE_BEARER),
	BASIC(ImportURLStrings.AUTH_TYPE_BASIC),
}

enum class URLImportAction {
	IMPORT,
	UPDATE,
}

/**
 * State holder for the import-from-URL dialog.
 */
data class ImportFromURLState(
	val url: String = "",
	val mode: URLImportMode = URLImportMode.OPENAPI,
	val action: URLImportAction = URLImportAction.IMPORT,
	val authType: URLAuthType = URLAuthType.NONE,
	val bearerToken: String = "",
	val basicUsername: String = "",
	val basicPassword: String = "",
	val loading: Boolean = false,
	val error: String? = null,
)

enum class URLImportMode {
	OPENAPI,
	SWAGGER,
}

internal data class ImportFromURLDialogCopy(
	val title: String,
	val submitButton: String,
)

internal fun importFromURLDialogCopy(state: ImportFromURLState): ImportFromURLDialogCopy {
	val title = when (state.action) {
		URLImportAction.IMPORT -> when (state.mode) {
			URLImportMode.OPENAPI -> ImportURLStrings.OPENAPI_DIALOG_TITLE
			URLImportMode.SWAGGER -> ImportURLStrings.SWAGGER_DIALOG_TITLE
		}

		URLImportAction.UPDATE -> when (state.mode) {
			URLImportMode.OPENAPI -> ImportURLStrings.UPDATE_OPENAPI_DIALOG_TITLE
			URLImportMode.SWAGGER -> ImportURLStrings.UPDATE_SWAGGER_DIALOG_TITLE
		}
	}
	val submitButton = when (state.action) {
		URLImportAction.IMPORT -> ImportURLStrings.IMPORT_BUTTON
		URLImportAction.UPDATE -> ImportURLStrings.UPDATE_BUTTON
	}
	return ImportFromURLDialogCopy(title = title, submitButton = submitButton)
}

/**
 * Dialog for importing an OpenAPI spec from a URL.
 *
 * Supports direct spec URLs and Swagger UI pages with auto-discovery.
 * Optional authentication via Bearer token or Basic auth.
 */
@Composable
internal fun ImportFromURLDialog(
	state: ImportFromURLState,
	onUrlChange: (String) -> Unit,
	onAuthTypeChange: (URLAuthType) -> Unit,
	onBearerTokenChange: (String) -> Unit,
	onBasicUsernameChange: (String) -> Unit,
	onBasicPasswordChange: (String) -> Unit,
	onConfirm: () -> Unit,
	onDismiss: () -> Unit,
) {
	var authExpanded by remember { mutableStateOf(state.authType != URLAuthType.NONE) }
	val isUrlValid = state.url.isNotBlank() && looksLikeUrl(state.url)
	val canSubmit = isUrlValid && !state.loading
	val dialogCopy = importFromURLDialogCopy(state)
	val keyboardActions = KeyboardActions(onDone = { if (canSubmit) onConfirm() })
	AlertDialog(
		onDismissRequest = { if (!state.loading) onDismiss() },
		title = { Text(dialogCopy.title) },
		text = {
			Column(
				modifier = Modifier.width(480.dp).verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(StudioDimens.m),
			) {
				FormTextField(
					value = state.url,
					onValueChange = onUrlChange,
					label = ImportURLStrings.URL_LABEL,
					placeholder = ImportURLStrings.URL_PLACEHOLDER,
					supportingText = ImportURLStrings.URL_HELPER,
					errorText = if (state.url.isNotBlank() && !isUrlValid) ImportURLStrings.INVALID_URL else null,
					modifier = Modifier.fillMaxWidth(),
					enabled = !state.loading,
					keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
					keyboardActions = keyboardActions,
				)
				DisclosureCard(
					expanded = authExpanded,
					onExpandedChange = { if (!state.loading) authExpanded = it },
					expandedLabel = ImportURLStrings.EXPANDED,
					collapsedLabel = ImportURLStrings.COLLAPSED,
					summary = { Text(ImportURLStrings.AUTH_SECTION) },
					detail = {
						Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.m)) {
							Text(ImportURLStrings.AUTH_HELPER, style = MaterialTheme.typography.bodySmall)
							MenuPicker(
								items = URLAuthType.entries,
								selected = state.authType,
								onSelect = onAuthTypeChange,
								label = ImportURLStrings.AUTH_SECTION,
								itemLabel = { it.label },
								enabled = !state.loading,
							)
							when (state.authType) {
								URLAuthType.BEARER -> SecretTextField(
									value = state.bearerToken,
									onValueChange = onBearerTokenChange,
									label = ImportURLStrings.TOKEN_LABEL,
									enabled = !state.loading,
									showLabel = ImportURLStrings.SHOW_SECRET,
									hideLabel = ImportURLStrings.HIDE_SECRET,
									imeAction = ImeAction.Done,
									keyboardActions = keyboardActions,
								)
								URLAuthType.BASIC -> {
									FormTextField(
										value = state.basicUsername,
										onValueChange = onBasicUsernameChange,
										label = ImportURLStrings.USERNAME_LABEL,
										enabled = !state.loading,
										keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
									)
									SecretTextField(
										value = state.basicPassword,
										onValueChange = onBasicPasswordChange,
										label = ImportURLStrings.PASSWORD_LABEL,
										enabled = !state.loading,
										showLabel = ImportURLStrings.SHOW_SECRET,
										hideLabel = ImportURLStrings.HIDE_SECRET,
										imeAction = ImeAction.Done,
										keyboardActions = keyboardActions,
									)
								}
								URLAuthType.NONE -> Unit
							}
						}
					},
				)
				state.error?.let { StatusMessage(message = it, tone = StatusTone.ERROR) }
				if (state.loading) InlineProgressIndicator(label = ImportURLStrings.FETCHING)
			}
		},
		confirmButton = { PrimaryButton(text = dialogCopy.submitButton, onClick = onConfirm, enabled = canSubmit) },
		dismissButton = {
			SecondaryButton(text = ImportURLStrings.CANCEL_BUTTON, onClick = onDismiss, enabled = !state.loading)
		},
	)
}

/**
 * Basic URL validation -- checks for a plausible URL format.
 */
private fun looksLikeUrl(input: String): Boolean {
	val trimmed = input.trim()
	// Accept with or without scheme prefix.
	val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
		trimmed
	} else {
		"https://$trimmed"
	}
	return try {
		val uri = java.net.URI(withScheme)
		!uri.host.isNullOrBlank() && uri.host.contains(".")
	} catch (_: Exception) {
		false
	}
}
