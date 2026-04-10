package com.moqserver.studio.endpointdetail

import com.moqserver.studio.projectformat.MatchType
import com.moqserver.studio.projectformat.ProjectVariant
import com.moqserver.studio.projectformat.RequestRules
import com.moqserver.studio.projectformat.VariantRequestMatch
import com.moqserver.studio.projectformat.YamlValue
import kotlinx.serialization.json.*

/** Column widths shared across headers and cookies tables. */
internal val headerMatchTypes = listOf(
	MatchType.CONTAINS,
	MatchType.NOT_CONTAINS,
	MatchType.BEGINS_WITH,
	MatchType.ENDS_WITH,
	MatchType.MATCHES_REGEX,
	MatchType.IS_EMPTY,
	MatchType.NOT_EMPTY,
	MatchType.EQUAL_TO,
)

internal enum class VariantDetailTab(val title: String) {
	SUMMARY("Summary"),
	HEADERS("Headers"),
	COOKIES("Cookies"),
	BODY("Body"),
	CRITERIA("Criteria"),
}

internal enum class BodyFormat(val label: String) {
	RAW("Raw"),
	JSON("JSON"),
}

private val autoNameRegex = Regex("^(Variant|Success|Error)( \\d+)?$")

/** True when the variant was just created and carries no meaningful user edits. */
private fun ProjectVariant.isPristine(): Boolean =
	name.matches(autoNameRegex) &&
		body == null &&
		bodyFile == null &&
		(headers == null || headers!!.isEmpty()) &&
		requestMatch == null &&
		(delayMs == null || delayMs == 0) &&
		isDefault != true

internal fun ProjectVariant.hasSessionEdits(originalVariants: List<ProjectVariant>): Boolean {
	val originalVariant = originalVariants.firstOrNull { it.referenceName == referenceName }
		?: originalVariants.firstOrNull { it.name == name }
	return if (originalVariant != null) this != originalVariant else !isPristine()
}

fun hasHeaderEntries(headers: Map<String, String>, requestRules: RequestRules): Boolean {
	return headers.isNotEmpty() || !requestRules.headers.isNullOrEmpty()
}

fun deleteAllHeaders(requestRules: RequestRules): RequestRules {
	return requestRules.copy(headers = null)
}

fun deleteAllCookies(requestRules: RequestRules): RequestRules {
	return requestRules.copy(cookies = null)
}

internal fun defaultNewVariantStatus(variants: List<ProjectVariant>): Int {
	return when {
		variants.none { it.status in 200..299 } -> 200
		variants.none { it.status in 400..599 } -> 500
		else -> if (variants.last().status in 400..599) 200 else 500
	}
}

internal fun <T> List<T>.updated(index: Int, value: T): List<T> {
	return toMutableList().also { it[index] = value }
}

internal fun RequestRules.normalize(): RequestRules? {
	return takeIf {
		!it.headers.isNullOrEmpty() ||
			!it.queryParams.isNullOrEmpty() ||
			!it.cookies.isNullOrEmpty()
	}
}

internal fun VariantRequestMatch.normalize(): VariantRequestMatch? {
	val normalizedQuery = query?.filterKeys { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
	val normalizedHeaders = headers?.filterKeys { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
	val normalizedBodyContains = bodyContains?.takeIf { it.isNotBlank() }
	return if (normalizedQuery == null && normalizedHeaders == null && normalizedBodyContains == null) {
		null
	} else {
		copy(query = normalizedQuery, headers = normalizedHeaders, bodyContains = normalizedBodyContains)
	}
}
