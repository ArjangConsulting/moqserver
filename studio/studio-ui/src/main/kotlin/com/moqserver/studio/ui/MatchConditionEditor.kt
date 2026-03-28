package com.moqserver.studio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moqserver.studio.projectformat.MatchType
import com.moqserver.studio.projectformat.RuleMatcher

@Composable
private fun placeholderTextFieldColors() = OutlinedTextFieldDefaults.colors().let { defaults ->
    OutlinedTextFieldDefaults.colors(
        focusedPlaceholderColor = defaults.disabledPlaceholderColor,
        unfocusedPlaceholderColor = defaults.disabledPlaceholderColor,
        disabledPlaceholderColor = defaults.disabledPlaceholderColor,
        errorPlaceholderColor = defaults.disabledPlaceholderColor,
    )
}

internal val MatchType.displayName: String
    get() = when (this) {
        MatchType.REQUIRE -> "exists"
        MatchType.EQUAL_TO -> "equals"
        MatchType.NOT_EQUAL_TO -> "not equals"
        MatchType.CONTAINS -> "contains"
        MatchType.NOT_CONTAINS -> "not contain"
        MatchType.BEGINS_WITH -> "starts with"
        MatchType.ENDS_WITH -> "ends with"
        MatchType.MATCHES_REGEX -> "matches regex"
        MatchType.IS_EMPTY -> "is empty"
        MatchType.NOT_EMPTY -> "not empty"
        MatchType.GT -> "gt"
        MatchType.GTE -> "gte"
        MatchType.LT -> "lt"
        MatchType.LTE -> "lte"
    }

internal val MatchType.needsValue: Boolean
    get() = this !in setOf(MatchType.REQUIRE, MatchType.IS_EMPTY, MatchType.NOT_EMPTY)

internal val RuleMatcher.effectiveMatchType: MatchType
    get() = matchType ?: if (match != null) MatchType.EQUAL_TO else MatchType.REQUIRE

/**
 * A self-contained condition editor showing a match-type dropdown followed by an optional
 * match-value text field. Suitable for embedding in table rows or card layouts.
 *
 * @param ruleMatcher The current rule state.
 * @param onUpdate Called whenever the user changes the match type or match value.
 * @param dropdownWidth Width of the match-type selector.
 * @param valueWidth Fixed width for the match-value field. Pass `null` to let it fill the
 *   remaining space via `weight(1f)` within this component's own Row.
 * @param showValuePlaceholder When `true`, a spacer of [valueWidth] is rendered even when the
 *   selected match type needs no value — useful for keeping columns aligned in a table.
 * @param modifier Applied to the outermost Row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchConditionEditor(
    ruleMatcher: RuleMatcher,
    onUpdate: (RuleMatcher) -> Unit,
    availableMatchTypes: List<MatchType> = MatchType.entries,
    fallbackMatchType: MatchType = MatchType.EQUAL_TO,
    enabled: Boolean = true,
    dropdownWidth: Dp = 140.dp,
    valueWidth: Dp? = 160.dp,
    showValuePlaceholder: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val matchType = when {
        ruleMatcher.matchType in availableMatchTypes -> ruleMatcher.matchType!!
        fallbackMatchType in availableMatchTypes -> fallbackMatchType
        else -> availableMatchTypes.firstOrNull() ?: ruleMatcher.effectiveMatchType
    }
    val placeholderColors = placeholderTextFieldColors()
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (enabled) expanded = !expanded },
            modifier = Modifier.width(dropdownWidth),
        ) {
            OutlinedTextField(
                value = matchType.displayName,
                onValueChange = {},
                enabled = enabled,
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                availableMatchTypes.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(type.displayName) },
                        onClick = {
                            onUpdate(
                                ruleMatcher.copy(
                                    matchType = type,
                                    match = if (type.needsValue) ruleMatcher.match else null,
                                ),
                            )
                            expanded = false
                        },
                    )
                }
            }
        }

        if (matchType.needsValue) {
            OutlinedTextField(
                value = ruleMatcher.match ?: "",
                onValueChange = { newMatch -> onUpdate(ruleMatcher.copy(match = newMatch.ifBlank { null })) },
                enabled = enabled,
                placeholder = { Text("Match value", color = placeholderColors.disabledPlaceholderColor) },
                singleLine = true,
                colors = placeholderColors,
                modifier = if (valueWidth != null) Modifier.width(valueWidth) else Modifier.weight(1f),
            )
        } else if (showValuePlaceholder) {
            Spacer(if (valueWidth != null) Modifier.width(valueWidth) else Modifier.weight(1f))
        }
    }
}
