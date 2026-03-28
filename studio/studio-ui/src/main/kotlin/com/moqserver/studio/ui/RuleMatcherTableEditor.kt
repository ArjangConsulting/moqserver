package com.moqserver.studio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moqserver.studio.projectformat.MatchType
import com.moqserver.studio.projectformat.RuleMatcher

private val criteriaNameColumnWidth = 160.dp
private val criteriaConditionColumnWidth = 200.dp
private val criteriaMatchValueColumnWidth = 180.dp
private val criteriaRequiredColumnWidth = 80.dp

@Composable
private fun placeholderTextFieldColors() = OutlinedTextFieldDefaults.colors().let { defaults ->
    OutlinedTextFieldDefaults.colors(
        focusedPlaceholderColor = defaults.disabledPlaceholderColor,
        unfocusedPlaceholderColor = defaults.disabledPlaceholderColor,
        disabledPlaceholderColor = defaults.disabledPlaceholderColor,
        errorPlaceholderColor = defaults.disabledPlaceholderColor,
    )
}

@Composable
fun RuleMatcherTableEditor(
    title: String,
    nameColumnLabel: String,
    emptyText: String,
    items: List<RuleMatcher>,
    onUpdate: (List<RuleMatcher>) -> Unit,
    onAdd: () -> Unit,
    onClear: (() -> Unit)? = null,
    deleteContentDescription: String,
    availableMatchTypes: List<MatchType> = MatchType.entries,
    fallbackMatchType: MatchType = MatchType.EQUAL_TO,
    showRequiredColumn: Boolean = false,
    normalizeItem: (RuleMatcher) -> RuleMatcher = { it },
) {
    val placeholderColors = placeholderTextFieldColors()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onClear != null && items.isNotEmpty()) {
                    androidx.compose.material3.TextButton(onClick = onClear) {
                        Text("Clear")
                    }
                }
                androidx.compose.material3.TextButton(onClick = onAdd) {
                    Text("Add")
                }
            }
        }

        if (items.isEmpty()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                nameColumnLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(criteriaNameColumnWidth),
            )
            if (showRequiredColumn) {
                Text(
                    "Required",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(criteriaRequiredColumnWidth),
                )
            }
            Text(
                "Condition",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(criteriaConditionColumnWidth),
            )
            Text(
                "Match Value",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(criteriaMatchValueColumnWidth),
            )
            Spacer(Modifier.width(32.dp))
        }

        HorizontalDivider()

        items.forEachIndexed { index, item ->
            val conditionEnabled = !showRequiredColumn || item.required == true

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = item.name,
                    onValueChange = { newName ->
                        onUpdate(items.updated(index, normalizeItem(item.copy(name = newName))))
                    },
                    placeholder = { Text("Name", color = placeholderColors.disabledPlaceholderColor) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    colors = placeholderColors,
                    modifier = Modifier.width(criteriaNameColumnWidth),
                )
                if (showRequiredColumn) {
                    Box(modifier = Modifier.width(criteriaRequiredColumnWidth), contentAlignment = Alignment.Center) {
                        Checkbox(
                            checked = item.required == true,
                            onCheckedChange = { checked ->
                                val updated = if (checked) {
                                    item.copy(required = true, matchType = item.matchType ?: fallbackMatchType)
                                } else {
                                    item.copy(required = null, matchType = null)
                                }
                                onUpdate(items.updated(index, normalizeItem(updated)))
                            },
                        )
                    }
                }
                MatchConditionEditor(
                    ruleMatcher = item,
                    onUpdate = { updated ->
                        if (!conditionEnabled) return@MatchConditionEditor
                        val normalized = if (showRequiredColumn) {
                            normalizeItem(updated.copy(required = true))
                        } else {
                            normalizeItem(updated)
                        }
                        onUpdate(items.updated(index, normalized))
                    },
                    availableMatchTypes = availableMatchTypes,
                    fallbackMatchType = fallbackMatchType,
                    enabled = conditionEnabled,
                    dropdownWidth = criteriaConditionColumnWidth,
                    valueWidth = criteriaMatchValueColumnWidth,
                    showValuePlaceholder = true,
                )
                IconButton(
                    onClick = {
                        onUpdate(items.toMutableList().also { it.removeAt(index) })
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = deleteContentDescription,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

private fun <T> List<T>.updated(index: Int, value: T): List<T> {
    return toMutableList().also { it[index] = value }
}
