package com.moqserver.studio.endpointdetail

import com.moqserver.studio.projectformat.EndpointDocument
import com.moqserver.studio.projectformat.ProjectVariant
import com.moqserver.studio.projectformat.VariantRequestMatch
import com.moqserver.studio.projectformat.YamlValue
import kotlinx.serialization.json.*

private val bodyEditorJson = Json { ignoreUnknownKeys = true }
private val bodyEditorPrettyJson = Json {
	ignoreUnknownKeys = true
	prettyPrint = true
	prettyPrintIndent = "  "
}

internal fun yamlValueToDisplayString(value: YamlValue, indent: Int = 0): String {
	val pad = " ".repeat(indent)
	return when (value) {
		is YamlValue.Null -> "null"
		is YamlValue.Bool -> if (value.value) "true" else "false"
		is YamlValue.Int -> "${value.value}"
		is YamlValue.Double -> "${value.value}"
		is YamlValue.Str -> "\"${value.value}\""
		is YamlValue.Array -> yamlValueToJsonString(value, indent)
		is YamlValue.Obj -> yamlObjectToDisplayString(value, indent)
	}
}

private fun yamlObjectToDisplayString(value: YamlValue.Obj, indent: Int): String {
	if (value.value.isEmpty()) return "{}"
	val pad = " ".repeat(indent)
	val childPad = " ".repeat(indent + 2)
	return value.value.entries.joinToString("\n") { (key, childValue) ->
		when (childValue) {
			is YamlValue.Obj -> {
				val rendered = yamlObjectToDisplayString(childValue, indent + 2)
				if (childValue.value.isEmpty()) "$pad$key: {}" else "$pad$key:\n$rendered"
			}

			is YamlValue.Array -> {
				val rendered = yamlValueToJsonString(childValue, indent + 2)
				if (childValue.value.isEmpty()) "$pad$key: []" else "$pad$key:\n$childPad$rendered"
			}

			else -> "$pad$key: ${yamlValueToDisplayString(childValue, indent + 2)}"
		}
	}
}

internal fun yamlValueToJsonString(value: YamlValue, indent: Int = 0): String {
	val pad = " ".repeat(indent)
	val childPad = " ".repeat(indent + 2)
	return when (value) {
		is YamlValue.Null -> "null"
		is YamlValue.Bool -> if (value.value) "true" else "false"
		is YamlValue.Int -> "${value.value}"
		is YamlValue.Double -> "${value.value}"
		is YamlValue.Str -> "\"${value.value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
		is YamlValue.Array -> {
			if (value.value.isEmpty()) {
			    "[]"
			} else {
			    "[\n${value.value.joinToString(",\n") { "$childPad${yamlValueToJsonString(it, indent + 2)}" }}\n$pad]"
			}
		}
		is YamlValue.Obj -> {
			if (value.value.isEmpty()) {
			    "{}"
			} else {
			    "{\n${value.value.entries.joinToString(",\n") { (k, v) ->
				"$childPad\"${k.replace("\"", "\\\"")}\": ${yamlValueToJsonString(v, indent + 2)}"
			}}\n$pad}"
			}
		}
	}
}

internal fun yamlValueToPlainText(value: YamlValue): String = when (value) {
	is YamlValue.Null -> ""
	is YamlValue.Bool -> if (value.value) "true" else "false"
	is YamlValue.Int -> "${value.value}"
	is YamlValue.Double -> "${value.value}"
	is YamlValue.Str -> value.value
	is YamlValue.Array -> value.value.joinToString("\n") { yamlValueToPlainText(it) }
	is YamlValue.Obj -> value.value.entries.joinToString("\n") { (k, v) -> "$k: ${yamlValueToPlainText(v)}" }
}

internal fun editableBodyText(variant: ProjectVariant, body: YamlValue): String {
	return if (variant.isJsonBody()) {
		yamlValueToJsonString(body)
	} else {
		when (body) {
			is YamlValue.Null -> "null"
			is YamlValue.Bool -> if (body.value) "true" else "false"
			is YamlValue.Int -> "${body.value}"
			is YamlValue.Double -> "${body.value}"
			is YamlValue.Str -> body.value
			is YamlValue.Array -> yamlValueToJsonString(body)
			is YamlValue.Obj -> yamlValueToJsonString(body)
		}
	}
}

internal fun parseEditableText(text: String, originalBody: YamlValue?): YamlValue {
	return when (originalBody) {
		null -> YamlValue.Str(text)
		is YamlValue.Null -> if (text.trim() == "null") YamlValue.Null else YamlValue.Str(text)
		is YamlValue.Bool -> text.toBooleanStrictOrNull()?.let(YamlValue::Bool) ?: YamlValue.Str(text)
		is YamlValue.Int -> text.toIntOrNull()?.let(YamlValue::Int) ?: YamlValue.Str(text)
		is YamlValue.Double -> text.toDoubleOrNull()?.let(YamlValue::Double) ?: YamlValue.Str(text)
		is YamlValue.Str -> YamlValue.Str(text)
		is YamlValue.Array,
		is YamlValue.Obj,
		-> YamlValue.Str(text)
	}
}

internal fun validateJsonBodyText(text: String): String? {
	return parseJsonBodyText(text).exceptionOrNull()?.let(::invalidJsonMessage)
}

internal fun parseJsonBodyText(text: String): Result<YamlValue> {
	return runCatching {
		jsonElementToYamlValue(bodyEditorJson.parseToJsonElement(text))
	}
}

internal fun formatJsonBodyText(text: String): Result<String> {
	return runCatching {
		val element = bodyEditorJson.parseToJsonElement(text)
		bodyEditorPrettyJson.encodeToString(JsonElement.serializer(), element)
	}
}

internal fun invalidJsonMessage(error: Throwable): String {
	return error.message ?: error.toString()
}

private fun jsonElementToYamlValue(element: JsonElement): YamlValue = when (element) {
	is JsonNull -> YamlValue.Null
	is JsonPrimitive -> when {
		element.isString -> YamlValue.Str(element.content)
		element.booleanOrNull != null -> YamlValue.Bool(element.booleanOrNull!!)
		element.intOrNull != null -> YamlValue.Int(element.intOrNull!!)
		element.longOrNull != null -> YamlValue.Double(element.longOrNull!!.toDouble())
		element.doubleOrNull != null -> YamlValue.Double(element.doubleOrNull!!)
		else -> YamlValue.Str(element.content)
	}
	is JsonArray -> YamlValue.Array(element.map(::jsonElementToYamlValue))
	is JsonObject -> YamlValue.Obj(element.mapValues { (_, value) -> jsonElementToYamlValue(value) })
}

internal fun ProjectVariant.isJsonBody(text: String? = null): Boolean {
	val contentType = headers
		?.entries
		?.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
		?.value
		?.substringBefore(';')
		?.trim()
		?.lowercase()

	if (contentType == "application/json" ||
		contentType == "application/graphql-response+json" ||
		contentType?.endsWith("+json") == true
	) {
		return true
	}

	if (body is YamlValue.Array || body is YamlValue.Obj) {
		return true
	}

	return text?.let { parseJsonBodyText(it).isSuccess } == true
}

internal fun EndpointDocument.updateVariant(index: Int, variant: ProjectVariant): EndpointDocument {
	val updatedVariants = variants.toMutableList()
	updatedVariants[index] = variant
	return copy(variants = updatedVariants)
}

/**
 * Removes the variant at [index]. If that variant was marked as default, the variant that
 * lands at the same index after removal (or the last one if [index] was the last) is promoted
 * to default so the endpoint always has exactly one default.
 */
internal fun EndpointDocument.removeVariantAt(index: Int): EndpointDocument {
	val removedVariant = variants.getOrNull(index) ?: return this
	val mutableVariants = variants.toMutableList().also { it.removeAt(index) }
	val finalVariants = if (removedVariant.isDefault == true && mutableVariants.isNotEmpty()) {
		val newDefaultIndex = index.coerceAtMost(mutableVariants.lastIndex)
		mutableVariants[newDefaultIndex] = mutableVariants[newDefaultIndex].copy(isDefault = true)
		mutableVariants
	} else {
		mutableVariants
	}
	return copy(variants = finalVariants)
}

internal fun EndpointDocument.updateHeadersState(
	index: Int,
	variant: ProjectVariant,
	headers: Map<String, String>,
	requestRules: com.moqserver.studio.projectformat.RequestRules,
): EndpointDocument {
	return updateVariant(index, variant.copy(headers = headers.ifEmpty { null }))
		.copy(requestRules = requestRules.normalize())
}

internal fun ProjectVariant.withNormalizedRequestMatch(requestMatch: VariantRequestMatch?): ProjectVariant {
	return copy(requestMatch = requestMatch?.normalize())
}
