package com.moqserver.studio.endpointdetail

import androidx.compose.runtime.Composable
import io.github.maniramezan.kmpcomponents.ConfirmDialog

private object SharedUiStrings {
	const val DELETE_ALL = "Delete all"
	const val CANCEL = "Cancel"
}

/** Reusable confirmation dialog for destructive "delete all" actions. */
@Composable
internal fun DeleteAllConfirmDialog(
	title: String,
	message: String,
	onConfirm: () -> Unit,
	onDismiss: () -> Unit,
) {
	ConfirmDialog(
		title = title,
		message = message,
		onConfirm = onConfirm,
		onDismiss = onDismiss,
		confirmLabel = SharedUiStrings.DELETE_ALL,
		dismissLabel = SharedUiStrings.CANCEL,
		destructive = true,
	)
}
