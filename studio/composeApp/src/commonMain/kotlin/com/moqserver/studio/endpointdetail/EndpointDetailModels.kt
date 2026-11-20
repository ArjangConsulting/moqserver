package com.moqserver.studio.endpointdetail

import com.moqserver.studio.projectformat.MatchType
import com.moqserver.studio.projectformat.ProjectVariant
import com.moqserver.studio.projectformat.RequestRules
import com.moqserver.studio.projectformat.VariantRequestMatch
import kotlinx.serialization.json.*

/** Every comparison the server evaluates for header, query and cookie rules. */
internal val requestRuleMatchTypes: List<MatchType> = MatchType.entries

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

/**
 * Drops empty rule lists, and the whole block when nothing is left. Every field counts: an
 * endpoint whose only rules are `requires_body` or `accepted_content_types` keeps them, and an
 * explicit `verify_cookies: false` stays because it overrides a project-wide `true`.
 */
internal fun RequestRules.normalize(): RequestRules? {
	val normalized = copy(
		headers = headers?.ifEmpty { null },
		queryParams = queryParams?.ifEmpty { null },
		cookies = cookies?.ifEmpty { null },
		acceptedContentTypes = acceptedContentTypes?.ifEmpty { null },
	)
	return normalized.takeIf { it != RequestRules() }
}

internal fun VariantRequestMatch.normalize(): VariantRequestMatch? {
	val normalizedQuery = query?.filterKeys { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
	val normalizedHeaders = headers?.filterKeys { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
	val normalizedBodyContains = bodyContains?.takeIf { it.isNotBlank() }
	// Add-on predicates are opaque to Studio: keep them untouched so a match that only has add-on
	// predicates survives editing.
	val nonEmptyAddons = addons?.takeIf { it.isNotEmpty() }
	val hasPredicates = listOfNotNull(
		normalizedQuery,
		normalizedHeaders,
		normalizedBodyContains,
		nonEmptyAddons,
	).isNotEmpty()
	return if (!hasPredicates) {
		null
	} else {
		copy(query = normalizedQuery, headers = normalizedHeaders, bodyContains = normalizedBodyContains)
	}
}
