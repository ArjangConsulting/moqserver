package com.moqserver.studio.data

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
import io.swagger.v3.parser.core.models.ParseOptions
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Parses OpenAPI 3.0.x and 3.1.x specs (YAML/JSON) into ParsedSpec.
 * Uses swagger-parser for OpenAPI handling, mirroring the Swift OpenAPIParser logic.
 */
class OpenAPIImportParser {
    private val maxStubDepth = 32
    private val logger = loggerFor<OpenAPIImportParser>()

    fun parse(content: String): ParsedSpec {
        logger.info("Parsing OpenAPI spec ({} bytes)", content.length)
        val options = ParseOptions().apply {
            isResolve = true
            isResolveFully = true
        }
        val result = OpenAPIV3Parser().readContents(content, null, options)
        val openAPI = result.openAPI
            ?: throw IllegalArgumentException(
                "Unable to parse OpenAPI spec: ${result.messages.joinToString("; ")}"
            )

        val warnings = result.messages.orEmpty().toMutableList()
        if (warnings.isNotEmpty()) {
            logger.warn("OpenAPI parser warnings ({}): {}", warnings.size, warnings.joinToString("; "))
        }

        val securitySchemes = openAPI.components?.securitySchemes.orEmpty()
        val endpoints = mutableListOf<ParsedEndpoint>()

        for ((pathStr, pathItem) in openAPI.paths.orEmpty()) {
            for ((method, operation) in operationsOf(pathItem)) {
                val responses = buildResponses(operation)
                val (authType, authHeaderName) = resolveAuth(
                    operation, openAPI.security.orEmpty(), securitySchemes
                )
                val (reqQuery, reqHeaders, reqCookies, requiresBody, acceptedContentTypes) =
                    extractRequestRules(operation, pathItem.parameters.orEmpty())

                logger.debug(
                    "OpenAPI endpoint: {} {} → {} variant(s), auth={}",
                    method, pathStr, responses.size, authType,
                )
                endpoints.add(
                    ParsedEndpoint(
                        method = method,
                        path = pathStr,
                        alias = resolveAlias(operation, method, pathStr),
                        description = operation.description?.trim()?.takeIf { it.isNotEmpty() },
                        referenceName = operation.operationId?.trim()?.takeIf { it.isNotEmpty() },
                        responses = responses,
                        authType = authType,
                        authHeaderName = authHeaderName,
                        queryParameters = reqQuery,
                        requiredQueryParameters = reqQuery.filter { it.required == true }.map { it.name },
                        requiredHeaders = reqHeaders,
                        cookies = reqCookies,
                        requiresBody = requiresBody,
                        acceptedContentTypes = acceptedContentTypes,
                    )
                )
            }
        }

        return ParsedSpec(
            title = openAPI.info?.title ?: "Untitled API",
            version = openAPI.info?.version ?: "1.0",
            endpoints = endpoints,
            warnings = warnings,
        ).also {
            logger.info(
                "OpenAPI parse complete: '{}' v{} — {} endpoint(s), {} warning(s)",
                it.title,
                it.version,
                endpoints.size,
                warnings.size,
            )
        }
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
                val name = uniqueName(baseName, usedNames)
                usedNames.add(name)
                responses.add(
                    ParsedResponse(name = name, statusCode = code, headers = responseHeaders)
                )
            } else {
                val sorted = sortContentTypes(content)
                for ((index, entry) in sorted.withIndex()) {
                    val (contentType, mediaType) = entry
                    val suffix = if (index == 0) "" else "-${contentTypeSuffix(contentType)}"
                    val name = uniqueName(baseName + suffix, usedNames)
                    usedNames.add(name)

                    val headers = responseHeaders.toMutableMap()
                    headers.keys.removeAll { it.equals("Content-Type", ignoreCase = true) }
                    headers["Content-Type"] = contentType

                    val body = extractBody(mediaType, contentType)

                    responses.add(
                        ParsedResponse(
                            name = name,
                            statusCode = code,
                            headers = headers,
                            body = body,
                        )
                    )
                }
            }
        }

        if (responses.isEmpty()) {
            responses.add(
                ParsedResponse(
                    name = "default",
                    statusCode = 200,
                    headers = mapOf("Content-Type" to "application/json"),
                    body = "{}",
                )
            )
        }

        return responses
    }

    private fun sortContentTypes(
        content: Content,
    ): List<Pair<String, io.swagger.v3.oas.models.media.MediaType>> {
        val sorted = mutableListOf<Pair<String, io.swagger.v3.oas.models.media.MediaType>>()
        // JSON first
        content["application/json"]?.let { sorted.add("application/json" to it) }
        for ((type, mediaType) in content.entries.sortedBy { it.key }) {
            if (type == "application/json") continue
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
                value.entries.associate { (k, v) -> k.toString() to anyToJsonElement(v) }
            )
            is List<*> -> kotlinx.serialization.json.JsonArray(value.map { anyToJsonElement(it) })
            else -> kotlinx.serialization.json.JsonPrimitive(value.toString())
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun generateStubFromSchema(schema: Schema<*>, mediaType: String): String? {
        if (isJsonMediaType(mediaType)) {
            return generateJsonStub(schema)
        }
        if (isXmlMediaType(mediaType)) {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root/>"
        }
        if (isHtmlMediaType(mediaType)) {
            return "<!DOCTYPE html>\n<html><head><title>Mock Response</title></head><body><p>mock-response</p></body></html>"
        }
        return defaultBody(mediaType)
    }

    private fun generateJsonStub(
        schema: Schema<*>,
        depth: Int = 0,
        visited: MutableSet<Schema<*>> =
            Collections.newSetFromMap(IdentityHashMap<Schema<*>, Boolean>()),
    ): String {
        if (depth >= maxStubDepth) return "{}"
        if (!visited.add(schema)) return "{}"

        return try {
            schema.example?.let { example ->
                return formatExample(example, "application/json")
            }

            when (schema.type) {
                "string" -> "\"string\""
                "integer" -> "0"
                "number" -> "0.0"
                "boolean" -> "false"
                "array" -> {
                    val itemStub = schema.items?.let { generateJsonStub(it, depth + 1, visited) } ?: "{}"
                    "[$itemStub]"
                }
                "object" -> {
                    val props = schema.properties.orEmpty()
                    if (props.isEmpty()) return "{}"
                    val entries = props.entries.sortedBy { it.key }.joinToString(",") { (k, v) ->
                        "\"$k\":${generateJsonStub(v, depth + 1, visited)}"
                    }
                    "{$entries}"
                }
                else -> "{}"
            }
        } finally {
            visited.remove(schema)
        }
    }

    private fun defaultBody(mediaType: String): String? {
        val lower = mediaType.lowercase()
        return when {
            isJsonMediaType(lower) -> "{}"
            isXmlMediaType(lower) -> "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root/>"
            isHtmlMediaType(lower) -> "<!DOCTYPE html>\n<html><head><title>Mock Response</title></head><body><p>mock-response</p></body></html>"
            lower == "text/csv" -> "column1,column2\nvalue1,value2"
            lower.startsWith("text/") -> "mock-response"
            lower.startsWith("image/svg") -> "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100\" height=\"100\"><rect width=\"100\" height=\"100\" fill=\"#ccc\"/><text x=\"10\" y=\"55\" font-size=\"12\">mock</text></svg>"
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
    ): Pair<AuthType, String?> {
        val security = operation.security ?: globalSecurity
        if (security.isEmpty()) return AuthType.NONE to null

        // Take the first security requirement
        for (req in security) {
            for ((schemeName, _) in req) {
                val scheme = securitySchemes[schemeName] ?: continue
                return when (scheme.type) {
                    SecurityScheme.Type.HTTP -> when (scheme.scheme?.lowercase()) {
                        "bearer" -> AuthType.BEARER to null
                        "basic" -> AuthType.BASIC to null
                        else -> AuthType.NONE to null
                    }
                    SecurityScheme.Type.APIKEY -> AuthType.API_KEY to scheme.name
                    SecurityScheme.Type.OAUTH2 -> AuthType.BEARER to null
                    SecurityScheme.Type.OPENIDCONNECT -> AuthType.BEARER to null
                    else -> AuthType.NONE to null
                }
            }
        }
        return AuthType.NONE to null
    }

    // -- Request rules --

    private data class RequestRules(
        val query: List<RuleMatcher>,
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

    // -- Helpers --

    private fun parseStatusCode(statusStr: String): Int {
        return statusStr.toIntOrNull() ?: 200
    }

    private fun statusCodeToVariantName(statusStr: String, code: Int): String {
        if (statusStr == "default") return "default"
        return when {
            code in 200..299 -> "default"
            code in 400..599 -> "error-$code"
            else -> "$code"
        }
    }

    private fun uniqueName(baseName: String, usedNames: Set<String>): String {
        if (baseName !in usedNames) return baseName
        var suffix = 2
        var candidate = "$baseName-$suffix"
        while (candidate in usedNames) {
            suffix++
            candidate = "$baseName-$suffix"
        }
        return candidate
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
