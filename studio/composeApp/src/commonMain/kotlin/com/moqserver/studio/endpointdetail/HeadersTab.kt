package com.moqserver.studio.endpointdetail

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import com.moqserver.studio.designsystem.StudioDimens
import com.moqserver.studio.projectformat.MatchType
import com.moqserver.studio.projectformat.RequestRules
import com.moqserver.studio.projectformat.RuleMatcher
import com.moqserver.studio.ui.*
import io.github.maniramezan.kmpcomponents.FormTextField
import io.github.maniramezan.kmpcomponents.ThemeCheckbox
import io.github.maniramezan.kmpcomponents.ThemeTextButton

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
	val tableScale = LocalDensity.current.fontScale.coerceAtLeast(1f)

	val headerCriteria = requestRules.headers ?: emptyList()
	val headersList = headers.entries.toList()

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
			BoxWithConstraints(Modifier.fillMaxWidth()) {
				val minimumWidth = (
					StudioDimens.tableNameColumnWidth + StudioDimens.tableActionColumnWidth +
						StudioDimens.tableConditionColumnWidth + StudioDimens.tableMatchValueColumnWidth * 2 +
						StudioDimens.tableDeleteButtonSize + StudioDimens.xxxl
				) * tableScale
				Column(Modifier.horizontalScroll(rememberScrollState()).width(maxWidth.coerceAtLeast(minimumWidth))) {
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
							onUpdate = onUpdate,
							onRemove = { removeHeader(key, isRequired) },
						)
					}
				}
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
				ThemeTextButton(
					text = HeadersTabStrings.DELETE_ALL,
					onClick = onDeleteAll,
					destructive = true,
				)
			}
			ThemeTextButton(
				text = HeadersTabStrings.ADD,
				onClick = onAdd,
			)
		}
	}
}

@Composable
private fun HeadersTableHeader() {
	val tableScale = LocalDensity.current.fontScale.coerceAtLeast(1f)

	Row(
		modifier = Modifier.fillMaxWidth().padding(horizontal = StudioDimens.xs),
		horizontalArrangement = Arrangement.spacedBy(StudioDimens.xs),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			HeadersTabStrings.HEADER_NAME,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.width(headerNameColumnWidth * tableScale),
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
			modifier = Modifier.width(StudioDimens.tableActionColumnWidth * tableScale),
		)
		Text(
			HeadersTabStrings.CONDITION,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.width(headerConditionColumnWidth * tableScale),
		)
		Text(
			HeadersTabStrings.MATCH_VALUE,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.width(headerMatchValueColumnWidth * tableScale),
		)
		Spacer(Modifier.width(StudioDimens.tableDeleteButtonSize * tableScale))
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
	onUpdate: (Map<String, String>, RequestRules) -> Unit,
	onRemove: () -> Unit,
) {
	val tableScale = LocalDensity.current.fontScale.coerceAtLeast(1f)

	Row(
		modifier = Modifier.fillMaxWidth().padding(horizontal = StudioDimens.xs, vertical = StudioDimens.m),
		horizontalArrangement = Arrangement.spacedBy(StudioDimens.xs),
		verticalAlignment = Alignment.CenterVertically,
	) {
		FormTextField(
			value = key,
			onValueChange = { newKey ->
				val entries = headers.entries.toMutableList()
				entries[index] = object : Map.Entry<String, String> {
					override val key = newKey
					override val value = value
				}
				val updatedHeaders = entries.associate { it.key to it.value }
				val updatedRules = if (isRequired) {
					requestRules.copy(
					    headers = headerCriteria.map {
						if (it.name.equals(key, ignoreCase = true)) it.copy(name = newKey) else it
					},
					)
				} else {
					requestRules
				}
				onUpdate(updatedHeaders, updatedRules)
			},
			placeholder = HeadersTabStrings.NAME_PLACEHOLDER,
			singleLine = true,
			modifier = Modifier.width(headerNameColumnWidth * tableScale),
			label = HeadersTabStrings.HEADER_NAME,
		)
		FormTextField(
			value = value,
			onValueChange = { newValue ->
				val updated = headers.entries.mapIndexed { entryIndex, entry ->
					if (entryIndex == index) newValue else entry.value
				}
				onUpdate(headers.keys.zip(updated).associate { it.first to it.second }, requestRules)
			},
			placeholder = HeadersTabStrings.VALUE_PLACEHOLDER,
			singleLine = true,
			modifier = Modifier.weight(2f),
			label = HeadersTabStrings.HEADER_VALUE,
		)
		Box(
			modifier = Modifier.width(StudioDimens.tableActionColumnWidth * tableScale),
			contentAlignment = Alignment.Center,
		) {
			ThemeCheckbox(
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
				label = HeadersTabStrings.REQUIRED,
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
			dropdownWidth = headerConditionColumnWidth * tableScale,
			valueWidth = headerMatchValueColumnWidth * tableScale,
			showValuePlaceholder = true,
		)
		ThemeTextButton(
			text = HeadersTabStrings.DELETE_HEADER,
			onClick = onRemove,
			destructive = true,
			modifier = Modifier.width(StudioDimens.tableDeleteButtonSize * tableScale),
		)
	}
}
