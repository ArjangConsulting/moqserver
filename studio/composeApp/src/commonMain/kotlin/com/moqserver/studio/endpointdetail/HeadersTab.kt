package com.moqserver.studio.endpointdetail

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign

import com.moqserver.studio.StudioDimens
import com.moqserver.studio.projectformat.MatchType
import com.moqserver.studio.projectformat.RequestRules
import com.moqserver.studio.projectformat.RuleMatcher
import com.moqserver.studio.ui.*

private object HeadersTabStrings {
    const val DELETE_ALL_TITLE = "Delete all headers?"
    const val DELETE_ALL_MESSAGE = "This removes every response header and imported header rule from this variant. This cannot be undone."
    const val NO_HEADERS = "No response headers configured for this variant."
    const val RESPONSE_HEADERS = "Response Headers"
    const val DELETE_ALL = "Delete all"
    const val ADD = "Add"
    const val HEADER_NAME = "Header Name"
    const val HEADER_VALUE = "Header Value"
    const val REQUIRED = "Required"
    const val CONDITION = "Condition"
    const val MATCH_VALUE = "Match Value"
    const val NAME_PLACEHOLDER = "Name"
    const val VALUE_PLACEHOLDER = "Value"
    const val DELETE_HEADER = "Delete header"
}

private val headerNameColumnWidth = StudioDimens.tableNameColumnWidth
private val headerConditionColumnWidth = StudioDimens.tableConditionColumnWidth
private val headerMatchValueColumnWidth = StudioDimens.tableMatchValueColumnWidth

@Composable
internal fun HeadersTab(
	headers: Map<String, String>,
	requestRules: RequestRules,
	onUpdate: (Map<String, String>, RequestRules) -> Unit,
) {
	val headerCriteria = requestRules.headers ?: emptyList()
	val headersList = headers.entries.toList()
	val placeholderColors = placeholderTextFieldColors()
	val hasAnyHeaders = hasHeaderEntries(headers, requestRules)
	var showDeleteAllConfirm by remember { mutableStateOf(false) }

	if (showDeleteAllConfirm) {
		DeleteAllConfirmDialog(
			title = HeadersTabStrings.DELETE_ALL_TITLE,
			message = HeadersTabStrings.DELETE_ALL_MESSAGE,
			onConfirm = {
				showDeleteAllConfirm = false
				onUpdate(emptyMap(), deleteAllHeaders(requestRules))
			},
			onDismiss = { showDeleteAllConfirm = false },
		)
	}

	fun removeHeader(name: String, wasRequired: Boolean) {
		val updatedHeaders = headers.filterKeys { !it.equals(name, ignoreCase = true) }
		val updatedRules = if (wasRequired) {
			requestRules.copy(
				headers = headerCriteria
					.filter { !it.name.equals(name, ignoreCase = true) }
					.ifEmpty { null },
			)
		} else {
			requestRules
		}
		onUpdate(updatedHeaders, updatedRules)
	}

	Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.xs)) {
		HeadersToolbar(
			hasAnyHeaders = hasAnyHeaders,
			onDeleteAll = { showDeleteAllConfirm = true },
			onAdd = { onUpdate(headers + ("" to ""), requestRules) },
		)

		if (headers.isEmpty()) {
			Text(
				HeadersTabStrings.NO_HEADERS,
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		} else {
			HeadersTableHeader()
			HorizontalDivider()
			headersList.forEachIndexed { index, (key, value) ->
				val ruleMatcher = headerCriteria.find { it.name.equals(key, ignoreCase = true) }
				val isRequired = ruleMatcher != null
				HeaderRow(
					key = key,
					value = value,
					index = index,
					headers = headers,
					headerCriteria = headerCriteria,
					requestRules = requestRules,
					isRequired = isRequired,
					ruleMatcher = ruleMatcher,
					placeholderColors = placeholderColors,
					onUpdate = onUpdate,
					onRemove = { removeHeader(key, isRequired) },
				)
			}
		}
	}
}

@Composable
private fun HeadersToolbar(
	hasAnyHeaders: Boolean,
	onDeleteAll: () -> Unit,
	onAdd: () -> Unit,
) {
	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.SpaceBetween,
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(HeadersTabStrings.RESPONSE_HEADERS, style = MaterialTheme.typography.titleSmall)
		Row(horizontalArrangement = Arrangement.spacedBy(StudioDimens.m), verticalAlignment = Alignment.CenterVertically) {
			if (hasAnyHeaders) {
				TextButton(
					onClick = onDeleteAll,
					colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
				) {
					Text(HeadersTabStrings.DELETE_ALL)
				}
			}
			TextButton(onClick = onAdd) {
				Text(HeadersTabStrings.ADD)
			}
		}
	}
}

@Composable
private fun HeadersTableHeader() {
	Row(
		modifier = Modifier.fillMaxWidth().padding(horizontal = StudioDimens.xs),
		horizontalArrangement = Arrangement.spacedBy(StudioDimens.xs),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			HeadersTabStrings.HEADER_NAME,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.width(headerNameColumnWidth),
		)
		Text(
			HeadersTabStrings.HEADER_VALUE,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.weight(2f),
		)
		Text(
			HeadersTabStrings.REQUIRED,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			textAlign = TextAlign.Center,
			modifier = Modifier.width(StudioDimens.tableActionColumnWidth),
		)
		Text(
			HeadersTabStrings.CONDITION,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.width(headerConditionColumnWidth),
		)
		Text(
			HeadersTabStrings.MATCH_VALUE,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.width(headerMatchValueColumnWidth),
		)
		Spacer(Modifier.width(StudioDimens.tableDeleteButtonSize))
	}
}

@Composable
private fun HeaderRow(
	key: String,
	value: String,
	index: Int,
	headers: Map<String, String>,
	headerCriteria: List<RuleMatcher>,
	requestRules: RequestRules,
	isRequired: Boolean,
	ruleMatcher: RuleMatcher?,
	placeholderColors: TextFieldColors,
	onUpdate: (Map<String, String>, RequestRules) -> Unit,
	onRemove: () -> Unit,
) {
	Row(
		modifier = Modifier.fillMaxWidth().padding(horizontal = StudioDimens.xs),
		horizontalArrangement = Arrangement.spacedBy(StudioDimens.xs),
		verticalAlignment = Alignment.CenterVertically,
	) {
		OutlinedTextField(
			value = key,
			onValueChange = { newKey ->
				val entries = headers.entries.toMutableList()
				entries[index] = object : Map.Entry<String, String> {
					override val key = newKey
					override val value = value
				}
				val updatedHeaders = entries.associate { it.key to it.value }
				val updatedRules = if (isRequired) {
					requestRules.copy(headers = headerCriteria.map {
						if (it.name.equals(key, ignoreCase = true)) it.copy(name = newKey) else it
					})
				} else {
					requestRules
				}
				onUpdate(updatedHeaders, updatedRules)
			},
			placeholder = { Text(HeadersTabStrings.NAME_PLACEHOLDER, color = placeholderColors.disabledPlaceholderColor) },
			singleLine = true,
			textStyle = MaterialTheme.typography.bodySmall,
			colors = placeholderColors,
			modifier = Modifier.width(headerNameColumnWidth),
		)
		OutlinedTextField(
			value = value,
			onValueChange = { newValue ->
				val updated = headers.entries.mapIndexed { entryIndex, entry ->
					if (entryIndex == index) newValue else entry.value
				}
				onUpdate(headers.keys.zip(updated).associate { it.first to it.second }, requestRules)
			},
			placeholder = { Text(HeadersTabStrings.VALUE_PLACEHOLDER, color = placeholderColors.disabledPlaceholderColor) },
			singleLine = true,
			textStyle = MaterialTheme.typography.bodySmall,
			colors = placeholderColors,
			modifier = Modifier.weight(2f),
		)
		Box(modifier = Modifier.width(StudioDimens.tableActionColumnWidth), contentAlignment = Alignment.Center) {
			Checkbox(
				checked = isRequired,
				onCheckedChange = { checked ->
					val updatedRules = if (checked) {
						requestRules.copy(
							headers = headerCriteria + RuleMatcher(
								name = key,
								required = true,
								matchType = MatchType.EQUAL_TO,
							),
						)
					} else {
						requestRules.copy(
							headers = headerCriteria
								.filter { !it.name.equals(key, ignoreCase = true) }
								.ifEmpty { null },
						)
					}
					onUpdate(headers, updatedRules)
				},
			)
		}
		MatchConditionEditor(
			ruleMatcher = ruleMatcher ?: RuleMatcher(
				name = key,
				required = false,
				matchType = MatchType.EQUAL_TO,
			),
			onUpdate = { updated ->
				if (!isRequired) return@MatchConditionEditor
				onUpdate(
					headers,
					requestRules.copy(
						headers = headerCriteria.map {
							if (it.name.equals(key, ignoreCase = true)) updated else it
						},
					),
				)
			},
			availableMatchTypes = headerMatchTypes,
			fallbackMatchType = MatchType.EQUAL_TO,
			enabled = isRequired,
			dropdownWidth = headerConditionColumnWidth,
			valueWidth = headerMatchValueColumnWidth,
			showValuePlaceholder = true,
		)
		IconButton(
			onClick = onRemove,
			modifier = Modifier.size(StudioDimens.tableDeleteButtonSize),
		) {
			Icon(
				imageVector = Icons.Outlined.Delete,
				contentDescription = HeadersTabStrings.DELETE_HEADER,
				tint = MaterialTheme.colorScheme.error,
			)
		}
	}
}
