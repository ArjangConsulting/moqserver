package com.moqserver.studio.designsystem

import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable

/**
 * Text field colors that render the placeholder in the disabled style for every state.
 *
 * Shared across rule-matcher tables, condition editors, and endpoint detail forms
 * so that placeholder text has a consistently muted appearance regardless of focus.
 */
@Composable
fun placeholderTextFieldColors() = OutlinedTextFieldDefaults.colors().let { defaults ->
	OutlinedTextFieldDefaults.colors(
		focusedPlaceholderColor = defaults.disabledPlaceholderColor,
		unfocusedPlaceholderColor = defaults.disabledPlaceholderColor,
		disabledPlaceholderColor = defaults.disabledPlaceholderColor,
		errorPlaceholderColor = defaults.disabledPlaceholderColor,
	)
}
