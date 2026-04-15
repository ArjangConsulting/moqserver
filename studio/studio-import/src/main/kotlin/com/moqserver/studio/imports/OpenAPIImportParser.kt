package com.moqserver.studio.imports

import com.moqserver.studio.domain.ParsedEndpoint
import com.moqserver.studio.domain.ParsedResponse
import com.moqserver.studio.domain.ParsedSpec
import com.moqserver.studio.logging.loggerFor
import com.moqserver.studio.projectformat.AuthType
import com.moqserver.studio.projectformat.MatchType
import com.moqserver.studio.projectformat.RuleMatcher
import com.moqserver.studio.projectformat.defaultAliasForEndpoint
import com.moqserver.studio.projectformat.humanizeAliasSource
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.converter.SwaggerConverter
import io.swagger.v3.parser.core.models.ParseOptions
import io.swagger.v3.parser.core.models.SwaggerParseResult
import java.util.*

	/**
	 * Parses OpenAPI 3.x and Swagger 2.0 specs (YAML/JSON) into ParsedSpec.
	 * Swagger 2.0 payloads are converted to OpenAPI 3 models before the rest of the import flow.
	 */
class OpenAPIImportParser {
	private val logger = loggerFor<OpenAPIImportParser>()

	companion object {
		private const val MAX_STUB_DEPTH = 32
		private const val DEFAULT_TITLE = "Untitled API"
		private const val DEFAULT_VERSION = "1.0"
		private const val DEFAULT_STATUS_CODE = 200
		private const val DEFAULT_VARIANT_NAME = "default"
		private const val CONTENT_TYPE_HEADER = "Content-Type"
		private const val APPLICATION_JSON = "application/json"
		private const val DEFAULT_JSON_BODY = "{}"
	}

	fun parse(content: String): ParsedSpec {
		logger.info("Parsing API spec ({} bytes)", content.length)
		val result = parseWithFallback(content)
		val openAPI = result.openAPI
			?: throw IllegalArgumentException(
				"Unable to parse API spec: ${result.messages.joinToString("; ")}",
			)

		val warnings = result.messages.orEmpty().toMutableList()
		if (warnings.isNotEmpty()) {
			logger.warn("API parser warnings ({}): {}", warnings.size, warnings.joinToString("; "))
		}

		val endpoints = buildParsedEndpoints(openAPI)

		return ParsedSpec(
			title = openAPI.info?.title ?: DEFAULT_TITLE,
			version = openAPI.info?.version ?: DEFAULT_VERSION,
			endpoints = endpoints,
			warnings = warnings,
		).also {
			logger.info(
				"API parse complete: '{}' v{} — {} endpoint(s), {} warning(s)",
				it.title,
				it.version,
				endpoints.size,
				warnings.size,
			)
		}
	}

	private fun buildParsedEndpoints(openAPI: OpenAPI): List<ParsedEndpoint> {
		val securitySchemes = openAPI.components?.securitySchemes.orEmpty()
		val globalSecurity = openAPI.security.orEmpty()
		val endpoints = mutableListOf<ParsedEndpoint>()
		for ((pathStr, pathItem) in openAPI.paths.orEmpty()) {
			val pathParameters = pathItem.parameters.orEmpty()
			for ((method, operation) in operationsOf(pathItem)) {
				endpoints += buildParsedEndpoint(
					path = pathStr,
					method = method,
					operation = operation,
					pathParameters = pathParameters,
					globalSecurity = globalSecurity,
					securitySchemes = securitySchemes,
				)
			}
		}
		return endpoints
	}

	private fun buildParsedEndpoint(
		path: String,
		method: String,
		operation: Operation,
		pathParameters: List<Parameter>,
		globalSecurity: List<SecurityRequirement>,
		securitySchemes: Map<String, SecurityScheme>,
	): ParsedEndpoint {
		val responses = buildResponses(operation)
		val auth = resolveAuth(operation, globalSecurity, securitySchemes)
		val requestRules = extractRequestRules(operation, pathParameters)
		logger.debug(
			"OpenAPI endpoint: {} {} → {} variant(s), auth={}",
			method,
			path,
			responses.size,
			auth.type,
		)
		return ParsedEndpoint(
			method = method,
			path = path,
			alias = resolveAlias(operation, method, path),
			description = operation.description?.trim()?.takeIf { it.isNotEmpty() },
			referenceName = operation.operationId?.trim()?.takeIf { it.isNotEmpty() },
			tags = operation.tags.orEmpty(),
			responses = responses,
			authType = auth.type,
			authHeaderName = auth.headerName,
			queryParameters = requestRules.query,
			requiredQueryParameters = requestRules.requiredQueryParameters,
			requiredHeaders = requestRules.requiredHeaders,
			cookies = requestRules.cookies,
			requiresBody = requestRules.requiresBody,
			acceptedContentTypes = requestRules.acceptedContentTypes,
		)
	}

	private fun parseWithFallback(content: String): SwaggerParseResult {
		val options = ParseOptions().apply {
			isResolve = true
			isResolveFully = true
		}
		val openApiResult = OpenAPIV3Parser().readContents(content, null, options)
		if (openApiResult.openAPI != null) {
			return openApiResult
		}

		if (!looksLikeSwagger2(content)) {
			return openApiResult
		}

		logger.info("OpenAPI 3 parse failed; attempting Swagger 2 conversion")
		val swaggerResult = SwaggerConverter().readContents(content, null, options)
		if (swaggerResult.openAPI != null) {
			return swaggerResult.apply {
				messages = messages
					.orEmpty()
					.filterNot { it.contains("attribute openapi is missing", ignoreCase = true) }
					.distinct()
			}
		}

		return SwaggerParseResult().apply {
			openAPI = null
			messages = (openApiResult.messages.orEmpty() + swaggerResult.messages.orEmpty()).distinct()
		}
	}

	private fun looksLikeSwagger2(content: String): Boolean {
		val trimmed = content.trimStart()
		return trimmed.contains(""""swagger"\s*:\s*"2.0""".toRegex()) ||
			trimmed.contains("""^swagger\s*:\s*["']?2.0["']?""".toRegex(RegexOption.MULTILINE))
	}

	private fun resolveAlias(operation: Operation, method: String, path: String): String {
		operation.summary
			?.trim()
			?.takeIf { it.isNotEmpty() }
			?.let { return it }

		operation.operationId
			?.trim()
			?.takeIf { it.isNotEmpty() }
			?.let(::humanizeAliasSource)
			?.let { return it }

		return defaultAliasForEndpoint(method = method, path = path)
	}

	private fun operationsOf(pathItem: PathItem): List<Pair<String, Operation>> {
		return listOfNotNull(
			pathItem.get?.let { "GET" to it },
			pathItem.post?.let { "POST" to it },
			pathItem.put?.let { "PUT" to it },
			pathItem.patch?.let { "PATCH" to it },
			pathItem.delete?.let { "DELETE" to it },
			pathItem.head?.let { "HEAD" to it },
			pathItem.options?.let { "OPTIONS" to it },
		)
	}

	// -- Response building --

	private fun buildResponses(operation: Operation): List<ParsedResponse> {
		val responses = mutableListOf<ParsedResponse>()
		val usedNames = mutableSetOf<String>()

		for ((statusStr, apiResponse) in operation.responses.orEmpty()) {
			val code = parseStatusCode(statusStr)
			val baseName = apiResponse.description
				?.trim()
				?.takeIf { it.isNotEmpty() }
				?: statusCodeToVariantName(statusStr, code)
			val responseHeaders = extractResponseHeaders(apiResponse)
			val content = apiResponse.content

			if (content.isNullOrEmpty()) {
				val name = uniqueImportName(baseName, usedNames)
				usedNames.add(name)
				responses.add(
					ParsedResponse(name = name, statusCode = code, headers = responseHeaders),
				)
			} else {
				val sorted = sortContentTypes(content)
				for ((index, entry) in sorted.withIndex()) {
					val (contentType, mediaType) = entry
					val suffix = if (index == 0) "" else "-${contentTypeSuffix(contentType)}"
					val name = uniqueImportName(baseName + suffix, usedNames)
					usedNames.add(name)

					val headers = responseHeaders.toMutableMap()
					headers.keys.removeAll { it.equals(CONTENT_TYPE_HEADER, ignoreCase = true) }
					headers[CONTENT_TYPE_HEADER] = contentType

					val body = extractBody(mediaType, contentType)

					responses.add(
						ParsedResponse(
							name = name,
							statusCode = code,
							headers = headers,
							body = body,
						),
					)
				}
			}
		}

		if (responses.isEmpty()) {
			responses.add(
				ParsedResponse(
					name = DEFAULT_VARIANT_NAME,
					statusCode = DEFAULT_STATUS_CODE,
					headers = mapOf(CONTENT_TYPE_HEADER to APPLICATION_JSON),
					body = DEFAULT_JSON_BODY,
				),
			)
		}

		return responses
	}

	private fun sortContentTypes(
		content: Content,
	): List<Pair<String, io.swagger.v3.oas.models.media.MediaType>> {
		val sorted = mutableListOf<Pair<String, io.swagger.v3.oas.models.media.MediaType>>()
		// JSON first
		content[APPLICATION_JSON]?.let { sorted.add(APPLICATION_JSON to it) }
		for ((type, mediaType) in content.entries.sortedBy { it.key }) {
			if (type == APPLICATION_JSON) continue
			sorted.add(type to mediaType)
		}
		return sorted
	}

	private fun extractBody(
		mediaType: io.swagger.v3.oas.models.media.MediaType,
		contentType: String,
	): String? {
		// Try example first
		mediaType.example?.let { example ->
			return formatExample(example, contentType)
		}

		// Try examples map
		mediaType.examples?.values?.firstOrNull()?.value?.let { example ->
			return formatExample(example, contentType)
		}

		// Try schema stub generation
		mediaType.schema?.let { schema ->
			return generateStubFromSchema(schema, contentType)
		}

		return defaultBody(contentType)
	}

	private fun formatExample(example: Any, contentType: String): String {
		if (isJsonMediaType(contentType)) {
			return when (example) {
				is Map<*, *>, is List<*> -> {
					try {
						val json = kotlinx.serialization.json.Json { prettyPrint = false }
						val element = anyToJsonElement(example)
						json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), element)
					} catch (_: Exception) {
						example.toString()
					}
				}
				is String -> "\"$example\""
				else -> example.toString()
			}
		}
		return example.toString()
	}

	private fun anyToJsonElement(value: Any?): kotlinx.serialization.json.JsonElement {
		return when (value) {
			null -> kotlinx.serialization.json.JsonNull
			is Boolean -> kotlinx.serialization.json.JsonPrimitive(value)
			is Number -> kotlinx.serialization.json.JsonPrimitive(value)
			is String -> kotlinx.serialization.json.JsonPrimitive(value)
			is Map<*, *> -> kotlinx.serialization.json.JsonObject(
				value.entries.associate { (k, v) -> k.toString() to anyToJsonElement(v) },
			)
			is List<*> -> kotlinx.serialization.json.JsonArray(value.map { anyToJsonElement(it) })
			else -> kotlinx.serialization.json.JsonPrimitive(value.toString())
		}
	}

	private fun generateStubFromSchema(schema: Schema<*>, mediaType: String): String? {
		if (isJsonMediaType(mediaType)) {
			return generateJsonStub(schema)
		}
		if (isXmlMediaType(mediaType)) {
			return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root/>"
		}
		if (isHtmlMediaType(mediaType)) {
			return "<!DOCTYPE html>\n<html><head><title>Mock Response</title></head>" +
				"<body><p>mock-response</p></body></html>"
		}
		return defaultBody(mediaType)
	}

	private fun generateJsonStub(
		schema: Schema<*>,
		depth: Int = 0,
		visited: MutableSet<Schema<*>> =
			Collections.newSetFromMap(IdentityHashMap()),
	): String {
		if (depth >= MAX_STUB_DEPTH) return DEFAULT_JSON_BODY
		if (!visited.add(schema)) return DEFAULT_JSON_BODY

		return try {
			schema.example?.let { example ->
				return formatExample(example, APPLICATION_JSON)
			}

			when (schema.type) {
				"string" -> "\"string\""
				"integer" -> "0"
				"number" -> "0.0"
				"boolean" -> "false"
				"array" -> {
					val itemStub = schema.items?.let { generateJsonStub(it, depth + 1, visited) } ?: DEFAULT_JSON_BODY
					"[$itemStub]"
				}
				"object" -> {
					val props = schemaProperties(schema)
					if (props.isEmpty()) return DEFAULT_JSON_BODY
					val entries = props.entries.sortedBy { it.key }.joinToString(",") { (k, v) ->
						"\"$k\":${generateJsonStub(v, depth + 1, visited)}"
					}
					"{$entries}"
				}
				else -> DEFAULT_JSON_BODY
			}
		} finally {
			visited.remove(schema)
		}
	}

	private fun defaultBody(mediaType: String): String? {
		val lower = mediaType.lowercase()
		return when {
			isJsonMediaType(lower) -> DEFAULT_JSON_BODY
			isXmlMediaType(lower) -> "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root/>"
			isHtmlMediaType(lower) ->
				"<!DOCTYPE html>\n<html><head><title>Mock Response</title></head>" +
					"<body><p>mock-response</p></body></html>"
			lower == "text/csv" -> "column1,column2\nvalue1,value2"
			lower.startsWith("text/") -> "mock-response"
			lower.startsWith("image/svg") ->
				"<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\">" +
					"<rect width=\"100\" height=\"100\" fill=\"#ccc\"/>" +
					"<text x=\"10\" y=\"55\" font-size=\"12\">mock</text></svg>"
			else -> null
		}
	}

	// -- Response headers --

	private fun extractResponseHeaders(response: ApiResponse): Map<String, String> {
		val headers = mutableMapOf<String, String>()
		for ((name, header) in response.headers.orEmpty()) {
			val example = header.example?.toString()
				?: header.schema?.example?.toString()
			if (example != null) {
				headers[name] = example
			}
		}
		return headers
	}

	// -- Auth resolution --

	private fun resolveAuth(
		operation: Operation,
		globalSecurity: List<SecurityRequirement>,
		securitySchemes: Map<String, SecurityScheme>,
	): ResolvedAuth {
		val security = operation.security ?: globalSecurity
		if (security.isEmpty()) return ResolvedAuth.none()

		for (req in security) {
			for (schemeName in req.keys) {
				val scheme = securitySchemes[schemeName] ?: continue
				resolveAuth(scheme)?.let { return it }
			}
		}
		return ResolvedAuth.none()
	}

	private fun resolveAuth(scheme: SecurityScheme): ResolvedAuth? {
		return when (scheme.type) {
			SecurityScheme.Type.HTTP -> resolveHttpAuth(scheme)
			SecurityScheme.Type.APIKEY -> ResolvedAuth(AuthType.API_KEY, scheme.name)
			SecurityScheme.Type.OAUTH2,
			SecurityScheme.Type.OPENIDCONNECT,
			-> ResolvedAuth(AuthType.BEARER, null)
			else -> null
		}
	}

	private fun resolveHttpAuth(scheme: SecurityScheme): ResolvedAuth? {
		return when (scheme.scheme?.lowercase()) {
			"bearer" -> ResolvedAuth(AuthType.BEARER, null)
			"basic" -> ResolvedAuth(AuthType.BASIC, null)
			else -> null
		}
	}

	// -- Request rules --

	private data class RequestRules(
		val query: List<RuleMatcher>,
		val requiredQueryParameters: List<String>,
		val requiredHeaders: List<String>,
		val cookies: List<RuleMatcher>,
		val requiresBody: Boolean,
		val acceptedContentTypes: List<String>,
	)

	private fun extractRequestRules(
		operation: Operation,
		pathParameters: List<Parameter>,
	): RequestRules {
		val allParams = pathParameters + operation.parameters.orEmpty()
		val query = linkedMapOf<String, RuleMatcher>()
		val requiredHeaders = mutableListOf<String>()
		val cookies = mutableListOf<RuleMatcher>()

		for (param in allParams) {
			when (param.`in`) {
				"query" -> {
					val rule = parseQueryRule(param) ?: continue
					val existing = query[param.name]
					query[param.name] = mergeQueryRules(existing, rule)
				}
				"header" -> if (param.required == true) requiredHeaders.add(param.name)
				"cookie" -> if (param.required == true) cookies.add(parseCookieRule(param))
			}
		}

		val requestBody = operation.requestBody
		val requiresBody = requestBody?.required == true
		val acceptedContentTypes = requestBody?.content?.keys?.sorted().orEmpty()

		return RequestRules(
			query = query.values.sortedBy { it.name },
			requiredQueryParameters = query.values.filter { it.required == true }.map { it.name },
			requiredHeaders = requiredHeaders.sorted(),
			cookies = cookies.sortedBy { it.name },
			requiresBody = requiresBody,
			acceptedContentTypes = acceptedContentTypes,
		)
	}

	private fun parseQueryRule(param: Parameter): RuleMatcher? {
		val isRequired = param.required == true
		val matchValue = param.example?.toString()
			?: param.examples?.values?.firstOrNull()?.value?.toString()
			?: param.schema?.example?.toString()
			?: param.schema?.default?.toString()

		if (!isRequired && matchValue == null) return null

		return RuleMatcher(
			name = param.name,
			match = matchValue,
			required = if (isRequired) true else null,
			matchType = if (matchValue != null) MatchType.EQUAL_TO else null,
		)
	}

	private fun mergeQueryRules(existing: RuleMatcher?, incoming: RuleMatcher): RuleMatcher {
		if (existing == null) return incoming

		val matchValue = existing.match ?: incoming.match
		return RuleMatcher(
			name = incoming.name,
			match = matchValue,
			required = if (existing.required == true || incoming.required == true) true else null,
			matchType = if (matchValue != null) MatchType.EQUAL_TO else null,
		)
	}

	private fun parseCookieRule(param: Parameter): RuleMatcher {
		val matchValue = param.example?.toString()
			?: param.examples?.values?.firstOrNull()?.value?.toString()
			?: param.schema?.example?.toString()

		return RuleMatcher(
			name = param.name,
			match = matchValue,
			required = true,
			matchType = if (matchValue != null) MatchType.EQUAL_TO else null,
		)
	}

	private fun schemaProperties(schema: Schema<*>): Map<String, Schema<*>> {
		return schema.properties.orEmpty().mapNotNull { (key, value) ->
			key to value
		}.toMap()
	}

	private data class ResolvedAuth(
		val type: AuthType,
		val headerName: String?,
	) {
		companion object {
			fun none(): ResolvedAuth = ResolvedAuth(AuthType.NONE, null)
		}
	}

	// -- Helpers --

	private fun parseStatusCode(statusStr: String): Int {
		return statusStr.toIntOrNull() ?: DEFAULT_STATUS_CODE
	}

	private fun statusCodeToVariantName(statusStr: String, code: Int): String {
		if (statusStr == DEFAULT_VARIANT_NAME) return DEFAULT_VARIANT_NAME
		return when (code) {
			in 200..299 -> DEFAULT_VARIANT_NAME
			in 400..599 -> "error-$code"
			else -> "$code"
		}
	}

	private fun contentTypeSuffix(contentType: String): String {
		val lower = contentType.lowercase()
		return when {
			"xml" in lower -> "xml"
			"html" in lower -> "html"
			"plain" in lower -> "text"
			"csv" in lower -> "csv"
			"pdf" in lower -> "pdf"
			"png" in lower -> "png"
			"svg" in lower -> "svg"
			"jpeg" in lower || "jpg" in lower -> "jpeg"
			"gif" in lower -> "gif"
			"octet-stream" in lower -> "binary"
			"form-urlencoded" in lower -> "form"
			else -> lower.substringAfter("/", lower)
		}
	}

	private fun isJsonMediaType(type: String): Boolean {
		val lower = type.lowercase()
		return lower == "application/json" || lower.endsWith("+json") || "/json" in lower
	}

	private fun isXmlMediaType(type: String): Boolean {
		val lower = type.lowercase()
		if (lower.startsWith("image/svg")) return false
		return lower == "application/xml" || lower == "text/xml" || lower.endsWith("+xml")
	}

	private fun isHtmlMediaType(type: String): Boolean {
		val lower = type.lowercase()
		return lower == "text/html" || lower == "application/xhtml+xml"
	}
}

internal fun uniqueImportName(baseName: String, usedNames: Set<String>): String {
	if (baseName !in usedNames) return baseName
	var suffix = 2
	var candidate = "$baseName-$suffix"
	while (candidate in usedNames) {
		suffix++
		candidate = "$baseName-$suffix"
	}
	return candidate
}
