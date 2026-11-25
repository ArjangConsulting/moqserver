package com.moqserver.studio.projectsettings

import com.moqserver.studio.endpointdetail.invalidJsonMessage
import com.moqserver.studio.endpointdetail.parseJsonBodyText
import com.moqserver.studio.endpointdetail.yamlValueToJsonString
import com.moqserver.studio.projectformat.ProjectManifest
import com.moqserver.studio.projectformat.ProjectRedaction
import com.moqserver.studio.projectformat.RedactionNames
import com.moqserver.studio.projectformat.YamlValue

/** Splits a comma- or whitespace-separated list of names, dropping blanks and duplicates. */
internal fun parseNameList(text: String): List<String> =
	text.split(',', ' ', '\n', '\t').map(String::trim).filter(String::isNotEmpty).distinctBy(String::lowercase)

/** Which list of the `redact:` section a field edits. */
internal enum class RedactList { HEADERS, QUERY_PARAMETERS, EXCEPT_HEADERS, EXCEPT_QUERY_PARAMETERS }

internal fun ProjectRedaction?.names(list: RedactList): List<String> = when (list) {
	RedactList.HEADERS -> this?.headers
	RedactList.QUERY_PARAMETERS -> this?.queryParameters
	RedactList.EXCEPT_HEADERS -> this?.except?.headers
	RedactList.EXCEPT_QUERY_PARAMETERS -> this?.except?.queryParameters
}.orEmpty()

/** The manifest with [list] set to [names], writing no `redact:` key when every list is empty. */
internal fun ProjectManifest.withRedactNames(list: RedactList, names: List<String>): ProjectManifest {
	val current = redact ?: ProjectRedaction()
	val value = names.ifEmpty { null }
	val except = current.except ?: RedactionNames()
	val updated = when (list) {
		RedactList.HEADERS -> current.copy(headers = value)
		RedactList.QUERY_PARAMETERS -> current.copy(queryParameters = value)
		RedactList.EXCEPT_HEADERS -> current.copy(except = except.copy(headers = value))
		RedactList.EXCEPT_QUERY_PARAMETERS -> current.copy(except = except.copy(queryParameters = value))
	}
	val normalized = updated.copy(except = updated.except?.takeIf { it != RedactionNames() })
	return copy(redact = normalized.takeIf { it != ProjectRedaction() })
}

/** The manifest with [addons], writing no `addons:` key when none is enabled. */
internal fun ProjectManifest.withAddons(addons: Map<String, YamlValue>): ProjectManifest =
	copy(addons = addons.ifEmpty { null })

/** The `addons:` mapping as the JSON the Add-ons editor shows; `{}` when none is enabled. */
internal fun addonsJsonText(addons: Map<String, YamlValue>?): String =
	yamlValueToJsonString(YamlValue.Obj(addons.orEmpty()))

/**
 * Parses the Add-ons editor's text: a JSON object of add-on id to that add-on's config. Fails with a
 * message for anything else; which ids and keys are valid is left to validation, since each add-on
 * owns its config.
 */
internal fun parseAddonsJsonText(text: String): Result<Map<String, YamlValue>> {
	val parsed = parseJsonBodyText(text.ifBlank { "{}" })
	val value = parsed.getOrElse { return Result.failure(IllegalArgumentException(invalidJsonMessage(it))) }
	if (value !is YamlValue.Obj) return Result.failure(IllegalArgumentException(ADDONS_MUST_BE_OBJECT))
	return Result.success(value.value)
}

internal const val ADDONS_MUST_BE_OBJECT = "Must be a JSON object of add-on id to its config, e.g. {\"jwt-claims\": {}}"
