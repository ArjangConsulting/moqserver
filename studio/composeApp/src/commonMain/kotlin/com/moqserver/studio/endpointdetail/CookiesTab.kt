package com.moqserver.studio.endpointdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.moqserver.studio.designsystem.StudioDimens
import com.moqserver.studio.projectformat.RequestRules
import com.moqserver.studio.ui.RuleMatcherTableEditor
import com.moqserver.studio.ui.newRequiredRule
import io.github.maniramezan.kmpcomponents.ThemeSwitch

private object CookiesTabStrings {
	const val DELETE_ALL_TITLE = "Delete all cookies?"
	const val DELETE_ALL_MESSAGE = "This removes every cookie rule from this endpoint. This cannot be undone."
	const val NO_COOKIES = "No cookie rules. Add one to require a cookie, or to check its value when it is sent."
	const val REQUEST_COOKIES = "Request Cookies"
	const val COOKIE_NAME = "Cookie Name"
	const val DELETE_COOKIE = "Delete cookie"
	const val VERIFY_COOKIES = "Require at least one cookie"
	const val VERIFY_COOKIES_TOOLTIP =
		"Rejects requests that send no cookies at all (400 missing_required_cookie). " +
			"Cookie rules below are checked either way."
	const val INHERITED = "Inherited from project settings"
}

/**
 * Cookie rules for the endpoint. A rule that is not required is written as `required: false`:
 * the cookie may be absent, but when it is sent it must satisfy the condition.
 */
@Composable
internal fun CookiesTab(
	requestRules: RequestRules,
	inheritedVerifyCookies: Boolean = false,
	onUpdate: (RequestRules) -> Unit,
) {
	val cookies = requestRules.cookies.orEmpty()
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

	Column(verticalArrangement = Arrangement.spacedBy(StudioDimens.l)) {
		ThemeSwitch(
			checked = requestRules.verifyCookies ?: inheritedVerifyCookies,
			onCheckedChange = { onUpdate(requestRules.withVerifyCookies(it, inheritedVerifyCookies)) },
			label = CookiesTabStrings.VERIFY_COOKIES,
			tooltip = CookiesTabStrings.VERIFY_COOKIES_TOOLTIP,
			supportingText = CookiesTabStrings.INHERITED.takeIf { requestRules.verifyCookies == null && inheritedVerifyCookies },
		)
		RuleMatcherTableEditor(
			title = CookiesTabStrings.REQUEST_COOKIES,
			nameColumnLabel = CookiesTabStrings.COOKIE_NAME,
			emptyText = CookiesTabStrings.NO_COOKIES,
			items = cookies,
			onUpdate = { onUpdate(requestRules.copy(cookies = it.ifEmpty { null })) },
			onAdd = { onUpdate(requestRules.copy(cookies = cookies + newRequiredRule())) },
			onClear = { showDeleteAllConfirm = true },
			deleteContentDescription = CookiesTabStrings.DELETE_COOKIE,
			availableMatchTypes = requestRuleMatchTypes,
			showRequiredColumn = true,
		)
	}
}

/**
 * Sets the endpoint's `verify_cookies`. A value equal to the project's is written as absent, so
 * the endpoint keeps following the project setting if that changes later.
 */
internal fun RequestRules.withVerifyCookies(verify: Boolean, inherited: Boolean): RequestRules =
	copy(verifyCookies = verify.takeIf { it != inherited })
