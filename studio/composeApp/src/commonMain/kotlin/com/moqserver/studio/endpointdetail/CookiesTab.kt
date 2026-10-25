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
	val tableScale = LocalDensity.current.fontScale.coerceAtLeast(1f)

	val cookies = requestRules.cookies ?: emptyList()

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

		BoxWithConstraints(Modifier.fillMaxWidth()) {
			val minimumWidth = (
				StudioDimens.tableNameColumnWidth + StudioDimens.tableActionColumnWidth +
					StudioDimens.tableConditionColumnWidth + StudioDimens.tableMatchValueColumnWidth * 2 +
					StudioDimens.tableDeleteButtonSize + StudioDimens.xxxl
			) * tableScale
			Column(Modifier.horizontalScroll(rememberScrollState()).width(maxWidth.coerceAtLeast(minimumWidth))) {
				CookiesTableHeader()
				HorizontalDivider()

				cookies.forEachIndexed { index, cookie ->
					CookieRow(
						cookie = cookie,
						index = index,
						cookies = cookies,
						requestRules = requestRules,
						onUpdate = onUpdate,
					)
				}
			}
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
				ThemeTextButton(
					text = CookiesTabStrings.DELETE_ALL,
					onClick = onDeleteAll,
					destructive = true,
				)
			}
			ThemeTextButton(
				text = CookiesTabStrings.ADD,
				onClick = onAdd,
			)
		}
	}
}

@Composable
private fun CookiesTableHeader() {
	val tableScale = LocalDensity.current.fontScale.coerceAtLeast(1f)

	Row(
		modifier = Modifier.fillMaxWidth().padding(horizontal = StudioDimens.xs),
		horizontalArrangement = Arrangement.spacedBy(StudioDimens.xs),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			CookiesTabStrings.COOKIE_NAME,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.width(cookieNameColumnWidth * tableScale),
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
			modifier = Modifier.width(StudioDimens.tableActionColumnWidth * tableScale),
		)
		Text(
			CookiesTabStrings.CONDITION,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.width(cookieConditionColumnWidth * tableScale),
		)
		Text(
			CookiesTabStrings.MATCH_VALUE,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.width(cookieMatchValueColumnWidth * tableScale),
		)
		Spacer(Modifier.width(StudioDimens.tableDeleteButtonSize * tableScale))
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
	onUpdate: (RequestRules) -> Unit,
) {
	val tableScale = LocalDensity.current.fontScale.coerceAtLeast(1f)

	Row(
		modifier = Modifier.fillMaxWidth().padding(horizontal = StudioDimens.xs, vertical = StudioDimens.m),
		horizontalArrangement = Arrangement.spacedBy(StudioDimens.xs),
		verticalAlignment = Alignment.CenterVertically,
	) {
		FormTextField(
			value = cookie.name,
			onValueChange = { newName ->
				onUpdate(requestRules.copy(cookies = cookies.updated(index, normalizedCookie(cookie.copy(name = newName)))))
			},
			placeholder = CookiesTabStrings.COOKIE_NAME_PLACEHOLDER,
			singleLine = true,
			modifier = Modifier.width(cookieNameColumnWidth * tableScale),
			label = CookiesTabStrings.COOKIE_NAME,
		)
		FormTextField(
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
			placeholder = CookiesTabStrings.VALUE_PLACEHOLDER,
			singleLine = true,
			modifier = Modifier.weight(2f),
			label = CookiesTabStrings.COOKIE_VALUE,
		)
		Box(
			modifier = Modifier.width(StudioDimens.tableActionColumnWidth * tableScale),
			contentAlignment = Alignment.Center,
		) {
			ThemeCheckbox(
				checked = cookie.required == true,
				onCheckedChange = { checked ->
					val updated = if (checked) {
						normalizedCookie(cookie.copy(required = true, matchType = cookie.matchType ?: MatchType.EQUAL_TO))
					} else {
						cookie.copy(required = null, matchType = null)
					}
					onUpdate(requestRules.copy(cookies = cookies.updated(index, updated)))
				},
				label = CookiesTabStrings.REQUIRED,
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
			dropdownWidth = cookieConditionColumnWidth * tableScale,
			valueWidth = cookieMatchValueColumnWidth * tableScale,
			showValuePlaceholder = true,
		)
		ThemeTextButton(
			text = CookiesTabStrings.DELETE_COOKIE,
			onClick = {
				val updated = cookies.toMutableList().also { it.removeAt(index) }
				onUpdate(requestRules.copy(cookies = updated.ifEmpty { null }))
			},
			destructive = true,
			modifier = Modifier.width(StudioDimens.tableDeleteButtonSize * tableScale),
		)
	}
}
