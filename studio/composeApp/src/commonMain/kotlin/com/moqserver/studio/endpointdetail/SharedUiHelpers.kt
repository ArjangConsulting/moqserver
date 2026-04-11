package com.moqserver.studio.endpointdetail

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import com.moqserver.studio.designsystem.placeholderTextFieldColors as sharedPlaceholderTextFieldColors

private object SharedUiStrings {
	const val DELETE_ALL = "Delete all"
	const val CANCEL = "Cancel"
}

/** Placeholder text field colors that match the disabled style for all states. */
@Composable
internal fun placeholderTextFieldColors() = sharedPlaceholderTextFieldColors()

/** Reusable confirmation dialog for destructive "delete all" actions. */
@Composable
internal fun DeleteAllConfirmDialog(
	title: String,
	message: String,
	onConfirm: () -> Unit,
	onDismiss: () -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(title) },
		text = { Text(message) },
		confirmButton = {
			TextButton(
				onClick = onConfirm,
				colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
			) {
				Text(SharedUiStrings.DELETE_ALL)
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) {
				Text(SharedUiStrings.CANCEL)
			}
		},
	)
}
