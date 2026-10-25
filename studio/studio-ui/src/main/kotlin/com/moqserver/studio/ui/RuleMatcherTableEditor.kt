package com.moqserver.studio.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moqserver.studio.designsystem.StudioDimens
import com.moqserver.studio.projectformat.MatchType
import com.moqserver.studio.projectformat.RuleMatcher
import io.github.maniramezan.kmpcomponents.FormTextField
import io.github.maniramezan.kmpcomponents.ThemeCheckbox
import io.github.maniramezan.kmpcomponents.ThemeTextButton

private object MatcherTableStrings {
	const val CLEAR = "Clear"
	const val ADD = "Add"
	const val NAME = "Name"
	const val REQUIRED = "Required"
	const val CONDITION = "Condition"
	const val MATCH_VALUE = "Match Value"
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
	val tableScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
	Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.xs)) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(title, style = MaterialTheme.typography.titleSmall)
			Row(horizontalArrangement = Arrangement.spacedBy(StudioDimens.m), verticalAlignment = Alignment.CenterVertically) {
				if (onClear != null && items.isNotEmpty()) {
					ThemeTextButton(
						text = MatcherTableStrings.CLEAR,
						onClick = onClear,
					)
				}
				ThemeTextButton(
					text = MatcherTableStrings.ADD,
					onClick = onAdd,
				)
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

		BoxWithConstraints(Modifier.fillMaxWidth()) {
			val minimumWidth = (
				StudioDimens.tableNameColumnWidth + StudioDimens.tableConditionColumnWidth +
					StudioDimens.tableMatchValueColumnWidth + StudioDimens.tableDeleteButtonSize + StudioDimens.xl +
					(if (showRequiredColumn) StudioDimens.tableActionColumnWidth else 0.dp)
			) * tableScale
			Column(Modifier.horizontalScroll(rememberScrollState()).width(maxWidth.coerceAtLeast(minimumWidth))) {
				Row(
					modifier = Modifier.fillMaxWidth().padding(horizontal = StudioDimens.xs),
					horizontalArrangement = Arrangement.spacedBy(StudioDimens.xs),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						nameColumnLabel,
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.width(StudioDimens.tableNameColumnWidth * tableScale),
					)
					if (showRequiredColumn) {
						Text(
							MatcherTableStrings.REQUIRED,
							style = MaterialTheme.typography.labelSmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							textAlign = TextAlign.Center,
							modifier = Modifier.width(StudioDimens.tableActionColumnWidth * tableScale),
						)
					}
					Text(
						MatcherTableStrings.CONDITION,
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.width(StudioDimens.tableConditionColumnWidth * tableScale),
					)
					Text(
						MatcherTableStrings.MATCH_VALUE,
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.width(StudioDimens.tableMatchValueColumnWidth * tableScale),
					)
					Spacer(Modifier.width(StudioDimens.tableDeleteButtonSize * tableScale))
				}

				HorizontalDivider()

				items.forEachIndexed { index, item ->
					val conditionEnabled = !showRequiredColumn || item.required == true

					Row(
						modifier = Modifier.fillMaxWidth().padding(horizontal = StudioDimens.xs, vertical = StudioDimens.m),
						horizontalArrangement = Arrangement.spacedBy(StudioDimens.xs),
						verticalAlignment = Alignment.CenterVertically,
					) {
						FormTextField(
							value = item.name,
							onValueChange = { newName ->
								onUpdate(items.updated(index, normalizeItem(item.copy(name = newName))))
							},
							placeholder = MatcherTableStrings.NAME,
							singleLine = true,
							modifier = Modifier.width(StudioDimens.tableNameColumnWidth * tableScale),
							label = nameColumnLabel,
						)
						if (showRequiredColumn) {
							Box(
								modifier = Modifier.width(StudioDimens.tableActionColumnWidth * tableScale),
								contentAlignment = Alignment.Center,
							) {
								ThemeCheckbox(
									checked = item.required == true,
									onCheckedChange = { checked ->
										val updated = if (checked) {
											item.copy(required = true, matchType = item.matchType ?: fallbackMatchType)
										} else {
											item.copy(required = null, matchType = null)
										}
										onUpdate(items.updated(index, normalizeItem(updated)))
									},
									label = MatcherTableStrings.REQUIRED,
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
							dropdownWidth = StudioDimens.tableConditionColumnWidth * tableScale,
							valueWidth = StudioDimens.tableMatchValueColumnWidth * tableScale,
							showValuePlaceholder = true,
						)
						ThemeTextButton(
							text = deleteContentDescription,
							onClick = {
								onUpdate(items.toMutableList().also { it.removeAt(index) })
							},
							destructive = true,
							modifier = Modifier.width(StudioDimens.tableDeleteButtonSize * tableScale),
						)
					}
				}
			}
		}
	}
}

private fun <T> List<T>.updated(index: Int, value: T): List<T> {
	return toMutableList().also { it[index] = value }
}
