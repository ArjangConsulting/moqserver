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

private object CookiesTabStrings {
    const val DELETE_ALL_TITLE = "Delete all cookies?"
    const val DELETE_ALL_MESSAGE = "This removes every cookie rule from this endpoint. This cannot be undone."
    const val NO_COOKIES = "No cookies configured. Add a cookie to set up cookie-based matching criteria."
    const val REQUEST_COOKIES = "Request Cookies"
    const val DELETE_ALL = "Delete all"
    const val ADD = "Add"
    const val COOKIE_NAME = "Cookie Name"
    const val COOKIE_VALUE = "Cookie Value"
    const val REQUIRED = "Required"
    const val CONDITION = "Condition"
    const val MATCH_VALUE = "Match Value"
    const val COOKIE_NAME_PLACEHOLDER = "Cookie name"
    const val VALUE_PLACEHOLDER = "Value"
    const val DELETE_COOKIE = "Delete cookie"
}

private val cookieNameColumnWidth = StudioDimens.tableNameColumnWidth
private val cookieConditionColumnWidth = StudioDimens.tableConditionColumnWidth
private val cookieMatchValueColumnWidth = StudioDimens.tableMatchValueColumnWidth

@Composable
internal fun CookiesTab(
	requestRules: RequestRules,
	onUpdate: (RequestRules) -> Unit,
) {
	val cookies = requestRules.cookies ?: emptyList()
	val placeholderColors = placeholderTextFieldColors()
	var showDeleteAllConfirm by remember { mutableStateOf(false) }

	if (showDeleteAllConfirm) {
		DeleteAllConfirmDialog(
			title = CookiesTabStrings.DELETE_ALL_TITLE,
			message = CookiesTabStrings.DELETE_ALL_MESSAGE,
			onConfirm = {
				showDeleteAllConfirm = false
				onUpdate(deleteAllCookies(requestRules))
			},
			onDismiss = { showDeleteAllConfirm = false },
		)
	}

	Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.xs)) {
		CookiesToolbar(
			hasCookies = cookies.isNotEmpty(),
			onDeleteAll = { showDeleteAllConfirm = true },
			onAdd = { onUpdate(requestRules.copy(cookies = cookies + RuleMatcher(name = "", required = true))) },
		)

		if (cookies.isEmpty()) {
			Text(
				text = CookiesTabStrings.NO_COOKIES,
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			return@Column
		}

		CookiesTableHeader()
		HorizontalDivider()

		cookies.forEachIndexed { index, cookie ->
			CookieRow(
				cookie = cookie,
				index = index,
				cookies = cookies,
				requestRules = requestRules,
				placeholderColors = placeholderColors,
				onUpdate = onUpdate,
			)
		}
	}
}

@Composable
private fun CookiesToolbar(
	hasCookies: Boolean,
	onDeleteAll: () -> Unit,
	onAdd: () -> Unit,
) {
	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.SpaceBetween,
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(CookiesTabStrings.REQUEST_COOKIES, style = MaterialTheme.typography.titleSmall)
		Row(horizontalArrangement = Arrangement.spacedBy(StudioDimens.m), verticalAlignment = Alignment.CenterVertically) {
			if (hasCookies) {
				TextButton(
					onClick = onDeleteAll,
					colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
				) {
					Text(CookiesTabStrings.DELETE_ALL)
				}
			}
			TextButton(onClick = onAdd) {
				Text(CookiesTabStrings.ADD)
			}
		}
	}
}

@Composable
private fun CookiesTableHeader() {
	Row(
		modifier = Modifier.fillMaxWidth().padding(horizontal = StudioDimens.xs),
		horizontalArrangement = Arrangement.spacedBy(StudioDimens.xs),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			CookiesTabStrings.COOKIE_NAME,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.width(cookieNameColumnWidth),
		)
		Text(
			CookiesTabStrings.COOKIE_VALUE,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.weight(2f),
		)
		Text(
			CookiesTabStrings.REQUIRED,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			textAlign = TextAlign.Center,
			modifier = Modifier.width(StudioDimens.tableActionColumnWidth),
		)
		Text(
			CookiesTabStrings.CONDITION,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.width(cookieConditionColumnWidth),
		)
		Text(
			CookiesTabStrings.MATCH_VALUE,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.width(cookieMatchValueColumnWidth),
		)
		Spacer(Modifier.width(StudioDimens.tableDeleteButtonSize))
	}
}

private fun normalizedCookie(cookie: RuleMatcher): RuleMatcher {
	val value = cookie.match?.takeUnless { it.isBlank() }
	val isRequired = cookie.required == true
	val currentMatchType = cookie.matchType
	val currentNeedsValue = currentMatchType != null && currentMatchType !in setOf(
		MatchType.REQUIRE,
		MatchType.IS_EMPTY,
		MatchType.NOT_EMPTY,
	)
	return cookie.copy(
		match = value,
		required = if (isRequired) true else null,
		matchType = when {
			!isRequired -> null
			value != null -> currentMatchType ?: MatchType.EQUAL_TO
			currentNeedsValue -> MatchType.EQUAL_TO
			else -> currentMatchType ?: MatchType.EQUAL_TO
		},
	)
}

@Composable
private fun CookieRow(
	cookie: RuleMatcher,
	index: Int,
	cookies: List<RuleMatcher>,
	requestRules: RequestRules,
	placeholderColors: TextFieldColors,
	onUpdate: (RequestRules) -> Unit,
) {
	Row(
		modifier = Modifier.fillMaxWidth().padding(horizontal = StudioDimens.xs),
		horizontalArrangement = Arrangement.spacedBy(StudioDimens.xs),
		verticalAlignment = Alignment.CenterVertically,
	) {
		OutlinedTextField(
			value = cookie.name,
			onValueChange = { newName ->
				onUpdate(requestRules.copy(cookies = cookies.updated(index, normalizedCookie(cookie.copy(name = newName)))))
			},
			placeholder = {
				Text(CookiesTabStrings.COOKIE_NAME_PLACEHOLDER, color = placeholderColors.disabledPlaceholderColor)
			},
			singleLine = true,
			textStyle = MaterialTheme.typography.bodySmall,
			colors = placeholderColors,
			modifier = Modifier.width(cookieNameColumnWidth),
		)
		OutlinedTextField(
			value = cookie.match.orEmpty(),
			onValueChange = { newValue ->
				onUpdate(
					requestRules.copy(
						cookies = cookies.updated(
							index,
							normalizedCookie(cookie.copy(match = newValue)),
						),
					),
				)
			},
			placeholder = { Text(CookiesTabStrings.VALUE_PLACEHOLDER, color = placeholderColors.disabledPlaceholderColor) },
			singleLine = true,
			textStyle = MaterialTheme.typography.bodySmall,
			colors = placeholderColors,
			modifier = Modifier.weight(2f),
		)
		Box(modifier = Modifier.width(StudioDimens.tableActionColumnWidth), contentAlignment = Alignment.Center) {
			Checkbox(
				checked = cookie.required == true,
				onCheckedChange = { checked ->
					val updated = if (checked) {
						normalizedCookie(cookie.copy(required = true, matchType = cookie.matchType ?: MatchType.EQUAL_TO))
					} else {
						cookie.copy(required = null, matchType = null)
					}
					onUpdate(requestRules.copy(cookies = cookies.updated(index, updated)))
				},
			)
		}
		MatchConditionEditor(
			ruleMatcher = cookie,
			onUpdate = { updated ->
				if (cookie.required != true) return@MatchConditionEditor
				onUpdate(requestRules.copy(cookies = cookies.updated(index, normalizedCookie(updated.copy(required = true)))))
			},
			availableMatchTypes = headerMatchTypes,
			fallbackMatchType = MatchType.EQUAL_TO,
			enabled = cookie.required == true,
			dropdownWidth = cookieConditionColumnWidth,
			valueWidth = cookieMatchValueColumnWidth,
			showValuePlaceholder = true,
		)
		IconButton(
			onClick = {
				val updated = cookies.toMutableList().also { it.removeAt(index) }
				onUpdate(requestRules.copy(cookies = updated.ifEmpty { null }))
			},
			modifier = Modifier.size(StudioDimens.tableDeleteButtonSize),
		) {
			Icon(
				imageVector = Icons.Outlined.Delete,
				contentDescription = CookiesTabStrings.DELETE_COOKIE,
				tint = MaterialTheme.colorScheme.error,
			)
		}
	}
}
