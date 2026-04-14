package com.moqserver.studio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

private object ImportURLStrings {
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
	val submitOnEnter = remember(canSubmit, onConfirm) {
		{
			if (canSubmit) onConfirm()
		}
	}

	AlertDialog(
		onDismissRequest = {
			if (!state.loading) onDismiss()
		},
		title = {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(StudioDimens.s),
			) {
				Icon(
					Icons.Outlined.CloudDownload,
					contentDescription = null,
					modifier = Modifier.size(24.dp),
				)
				Text(dialogCopy.title)
			}
		},
		text = {
			Column(
				verticalArrangement = Arrangement.spacedBy(StudioDimens.m),
				modifier = Modifier.width(480.dp),
			) {
				// URL input field.
				OutlinedTextField(
					value = state.url,
					onValueChange = onUrlChange,
					label = { Text(ImportURLStrings.URL_LABEL) },
					placeholder = {
						Text(
							ImportURLStrings.URL_PLACEHOLDER,
							style = MaterialTheme.typography.bodySmall,
						)
					},
					singleLine = true,
					modifier = Modifier.fillMaxWidth(),
					textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
					enabled = !state.loading,
					isError = state.url.isNotBlank() && !isUrlValid,
					keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
					keyboardActions = KeyboardActions(onDone = { submitOnEnter() }),
				)
				Text(
					text = ImportURLStrings.URL_HELPER,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)

				// Auth section (collapsible).
				TextButton(
					onClick = { authExpanded = !authExpanded },
					enabled = !state.loading,
				) {
					Icon(
						Icons.Outlined.Lock,
						contentDescription = null,
						modifier = Modifier.size(16.dp),
					)
					Spacer(Modifier.width(StudioDimens.xs))
					Text(ImportURLStrings.AUTH_SECTION, style = MaterialTheme.typography.labelMedium)
					Spacer(Modifier.width(StudioDimens.xs))
					Icon(
						if (authExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
						contentDescription = null,
						modifier = Modifier.size(16.dp),
					)
				}

				AnimatedVisibility(visible = authExpanded) {
					Column(
						verticalArrangement = Arrangement.spacedBy(StudioDimens.s),
						modifier = Modifier.padding(start = StudioDimens.m),
					) {
						Text(
							text = ImportURLStrings.AUTH_HELPER,
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)

						AuthTypeSelector(
							selected = state.authType,
							onSelect = onAuthTypeChange,
							enabled = !state.loading,
						)

						when (state.authType) {
							URLAuthType.BEARER -> {
								OutlinedTextField(
									value = state.bearerToken,
									onValueChange = onBearerTokenChange,
									label = { Text(ImportURLStrings.TOKEN_LABEL) },
									placeholder = {
										Text(
											ImportURLStrings.TOKEN_PLACEHOLDER,
											style = MaterialTheme.typography.bodySmall,
										)
									},
									singleLine = true,
									modifier = Modifier.fillMaxWidth(),
									textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
									enabled = !state.loading,
									keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
									keyboardActions = KeyboardActions(onDone = { submitOnEnter() }),
								)
							}
							URLAuthType.BASIC -> {
								OutlinedTextField(
									value = state.basicUsername,
									onValueChange = onBasicUsernameChange,
									label = { Text(ImportURLStrings.USERNAME_LABEL) },
									singleLine = true,
									modifier = Modifier.fillMaxWidth(),
									textStyle = MaterialTheme.typography.bodySmall,
									enabled = !state.loading,
									keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
								)
								OutlinedTextField(
									value = state.basicPassword,
									onValueChange = onBasicPasswordChange,
									label = { Text(ImportURLStrings.PASSWORD_LABEL) },
									singleLine = true,
									modifier = Modifier.fillMaxWidth(),
									textStyle = MaterialTheme.typography.bodySmall,
									enabled = !state.loading,
									keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
									keyboardActions = KeyboardActions(onDone = { submitOnEnter() }),
								)
							}
							URLAuthType.NONE -> {}
						}
					}
				}

				// Error display.
				if (state.error != null) {
					Text(
						text = state.error,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.error,
						modifier = Modifier
							.fillMaxWidth()
							.padding(vertical = StudioDimens.xs),
					)
				}
			}
		},
		confirmButton = {
			Button(
				onClick = onConfirm,
				enabled = canSubmit,
			) {
				if (state.loading) {
					CircularProgressIndicator(
						modifier = Modifier.size(16.dp),
						strokeWidth = 2.dp,
						color = MaterialTheme.colorScheme.onPrimary,
					)
					Spacer(Modifier.width(StudioDimens.s))
					Text(ImportURLStrings.FETCHING)
				} else {
					Icon(Icons.Outlined.CloudDownload, contentDescription = null)
					Spacer(Modifier.width(StudioDimens.s))
					Text(dialogCopy.submitButton)
				}
			}
		},
		dismissButton = {
			OutlinedButton(
				onClick = onDismiss,
				enabled = !state.loading,
			) {
				Text(ImportURLStrings.CANCEL_BUTTON)
			}
		},
	)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthTypeSelector(
	selected: URLAuthType,
	onSelect: (URLAuthType) -> Unit,
	enabled: Boolean,
) {
	var expanded by remember { mutableStateOf(false) }

	ExposedDropdownMenuBox(
		expanded = expanded,
		onExpandedChange = { if (enabled) expanded = it },
	) {
		OutlinedTextField(
			value = selected.label,
			onValueChange = {},
			readOnly = true,
			trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
			modifier = Modifier
				.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
				.fillMaxWidth(),
			textStyle = MaterialTheme.typography.bodySmall,
			enabled = enabled,
		)
		ExposedDropdownMenu(
			expanded = expanded,
			onDismissRequest = { expanded = false },
		) {
			URLAuthType.entries.forEach { authType ->
				DropdownMenuItem(
					text = { Text(authType.label, style = MaterialTheme.typography.bodySmall) },
					onClick = {
						onSelect(authType)
						expanded = false
					},
				)
			}
		}
	}
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
