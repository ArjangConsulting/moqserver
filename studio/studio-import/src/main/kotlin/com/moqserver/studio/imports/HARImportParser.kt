package com.moqserver.studio.imports

import com.moqserver.studio.domain.ParsedEndpoint
import com.moqserver.studio.domain.ParsedResponse
import com.moqserver.studio.domain.ParsedSpec
import com.moqserver.studio.logging.loggerFor
import com.moqserver.studio.projectformat.MatchType
import com.moqserver.studio.projectformat.RuleMatcher
import com.moqserver.studio.projectformat.defaultAliasForEndpoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.util.*

/**
 * Parses HAR 1.2 files into ParsedSpec for import into Studio.
	 * Mirrors the Swift HARParser logic: groups entries by method+path
	 * and generates variant names.
 *
 * Security: all sensitive values (auth headers, cookies, tokens, JWTs) are
 * redacted at parse time following the same approach as Cloudflare's HAR Sanitizer
 * (https://github.com/cloudflare/har-sanitizer). Values are replaced with
 * `[redacted]` so they never reach the parsed spec or the persisted .moqproj.
 */
class HARImportParser {

	private val json = Json { ignoreUnknownKeys = true }
	private val logger = loggerFor<HARImportParser>()

	companion object {
		private const val CONTENT_TYPE_HEADER = "Content-Type"
		private const val COOKIE_HEADER = "Cookie"
		private const val HAR_IMPORT_SUFFIX = "HAR Import"
		private const val DEFAULT_VERSION = "1.0"
		private const val DEFAULT_STATUS_CODE = 200
		private val VALID_STATUS_RANGE = 100..599
		private const val DEFAULT_VARIANT_NAME = "default"
		private const val SUCCESS_VARIANT_PREFIX = "success"
		private const val REDIRECT_VARIANT_PREFIX = "redirect"
		private const val ERROR_VARIANT_PREFIX = "error"
		private const val REDACTED = "[redacted]"
		private const val REDACTION_WARNING =
			"HAR sensitive fields were redacted heuristically. Review imported response bodies because arbitrary secret names may not be detected."

		/**
		 * Header names (case-insensitive) whose values are always redacted.
		 * Covers auth credentials, session tokens, and CSRF tokens.
		 * Mirrors the Cloudflare HAR Sanitizer default word list.
		 */
		private val SENSITIVE_HEADER_PATTERNS = setOf(
			"authorization",
			"cookie",
			"set-cookie",
			"x-api-key",
			"x-auth-token",
			"x-csrf-token",
			"x-forwarded-for",
			"proxy-authorization",
			"www-authenticate",
		)

		/**
		 * Query parameter / post-data parameter names (case-insensitive) whose
		 * values are redacted.  Based on the Cloudflare default word list.
		 */
		private val SENSITIVE_PARAM_NAMES = setOf(
			"access_token",
			"assertion",
			"auth",
			"authenticity_token",
			"challenge",
			"client_id",
			"client_secret",
			"code",
			"code_challenge",
			"code_verifier",
			"email",
			"id_token",
			"password",
			"refresh_token",
			"samlrequest",
			"samlresponse",
			"state",
			"token",
		)

		/**
		 * Regex that matches a full JWT (header.payload.signature).
		 * Captures header and payload in groups 1 and 2 so the signature can
		 * be replaced while leaving the decodable parts intact.
		 */
		private val JWT_REGEX = Regex("""(ey[A-Za-z0-9\-_=]+)\.(ey[A-Za-z0-9\-_=]+)\.[A-Za-z0-9\-_.+/=]+""")
	}

	fun parse(content: String): ParsedSpec {
		logger.info("Parsing HAR file ({} bytes)", content.length)
		val har = runCatching { json.decodeFromString<HarFile>(content) }
			.getOrElse { error ->
				throw IllegalArgumentException("Unable to parse HAR file: ${error.message}", error)
			}
		val log = har.log
		val entries = log?.entries.orEmpty()
		logger.debug("HAR: creator={}, version={}, entries={}", log?.creator?.name, log?.version, entries.size)
		require(entries.isNotEmpty()) { "HAR file does not contain any importable HTTP entries." }

		val grouped = mutableMapOf<GroupKey, MutableList<CapturedExchange>>()
		val warnings = mutableListOf<String>()
		var redactedResponseBody = false

		for ((index, entry) in entries.withIndex()) {
			parseEntry(entry, index + 1, warnings)?.let { parsedEntry ->
				redactedResponseBody = redactedResponseBody || parsedEntry.responseBodyRedacted
				val existing = grouped.getOrPut(parsedEntry.key) { mutableListOf() }
				existing.add(parsedEntry.exchange)
			}
		}
		if (redactedResponseBody) warnings += REDACTION_WARNING

		val endpoints = grouped.entries
			.sortedWith(compareBy({ it.key.path }, { it.key.method }))
			.map { (key, exchanges) ->
				val responses = makeResponses(exchanges)
				logger.debug("HAR endpoint: {} {} → {} variant(s)", key.method, key.path, responses.size)
				ParsedEndpoint(
					method = key.method,
					path = key.path,
					alias = defaultAliasForEndpoint(method = key.method, path = key.path),
					responses = responses,
					queryParameters = requestQueryParameters(exchanges),
					cookies = collectCookieRules(exchanges),
				)
			}

		require(endpoints.isNotEmpty()) { noImportableEntriesMessage(warnings) }

		if (warnings.isNotEmpty()) {
			logger.warn("HAR parser skipped {} malformed entries", warnings.size)
		}

		val title = log?.creator?.name?.let { "$it $HAR_IMPORT_SUFFIX" } ?: HAR_IMPORT_SUFFIX
		val version = log?.creator?.version ?: log?.version ?: DEFAULT_VERSION

		return ParsedSpec(
			title = title,
			version = version,
			endpoints = endpoints,
			warnings = warnings,
		).also {
			logger.info(
				"HAR parse complete: '{}' — {} endpoint(s) from {} raw entries",
				title,
				endpoints.size,
				entries.size,
			)
		}
	}

	private fun normalizedPath(uri: URI): String {
		val path = uri.path ?: "/"
		return path.ifEmpty { "/" }.let { if (it.startsWith("/")) it else "/$it" }
	}

	private fun normalizedStatusCode(status: Int?): Int {
		return status?.takeIf { it in VALID_STATUS_RANGE } ?: DEFAULT_STATUS_CODE
	}

	/**
	 * Extracts response headers, redacting values for any header whose name
	 * matches a known-sensitive pattern.  JWT signatures in non-redacted
	 * header values are also stripped.
	 */
	private fun responseHeaders(response: HarResponse): Map<String, String> {
		val headers = mutableMapOf<String, String>()
		for (header in response.headers) {
			val name = header.name.orEmpty()
			if (name.isNotEmpty()) {
				headers[name] = sanitizeHeaderValue(name, header.value.orEmpty())
			}
		}
		val mimeType = response.content.mimeType
		if (!mimeType.isNullOrEmpty() &&
			headers.keys.none { it.equals(CONTENT_TYPE_HEADER, ignoreCase = true) }
		) {
			headers[CONTENT_TYPE_HEADER] = mimeType
		}
		return headers
	}

	private fun responseBody(response: HarResponse): SanitizedBody {
		val text = response.content.text ?: return SanitizedBody(null, false)

		if (response.content.encoding?.lowercase() == "base64") {
			if (!response.content.mimeType.isLikelyTextualMimeType()) {
				// Binary payload: keep the base64 as-is and mark it, so the variant declares
				// body_encoding instead of leaving a writer to infer it from the Content-Type.
				return SanitizedBody(text, false, isBase64 = true)
			}
			val decoded = try {
				String(Base64.getDecoder().decode(text), Charsets.UTF_8)
			} catch (_: Exception) {
				text
			}
			return sanitizeStructuredBody(decoded, response.content.mimeType)
		}
		return sanitizeStructuredBody(text, response.content.mimeType)
	}

	/**
	 * Extracts request cookies, replacing every value with `[redacted]`.
	 * Cookie values are session credentials and must never appear in the
	 * persisted project.
	 */
	private fun parseEntry(
		entry: HarEntry,
		entryNumber: Int,
		warnings: MutableList<String>,
	): ParsedHarEntry? {
		val request = entry.request ?: return warnAndSkip(warnings, entryNumber, "missing request payload")
		val response = entry.response ?: return warnAndSkip(warnings, entryNumber, "missing response payload")
		val method = request.method.orEmpty().trim().uppercase()
		if (method.isBlank()) return warnAndSkip(warnings, entryNumber, "missing request method")
		val rawUrl = request.url.orEmpty().trim()
		if (rawUrl.isBlank()) return warnAndSkip(warnings, entryNumber, "missing request URL")
		val uri = parseRequestUri(rawUrl, entryNumber, warnings) ?: return null

		val sanitizedBody = responseBody(response)
		return ParsedHarEntry(
			key = GroupKey(method, normalizedPath(uri)),
			exchange = CapturedExchange(
				statusCode = normalizedStatusCode(response.status),
				headers = responseHeaders(response),
				body = sanitizedBody.value,
				bodyIsBase64 = sanitizedBody.isBase64,
				cookies = extractRequestCookies(request),
				queryParameters = requestQueryParameters(request, uri),
			),
			responseBodyRedacted = sanitizedBody.redacted,
		)
	}

	private fun parseRequestUri(
		rawUrl: String,
		entryNumber: Int,
		warnings: MutableList<String>,
	): URI? {
		return try {
			URI(rawUrl)
		} catch (_: Exception) {
			warnAndSkip(warnings, entryNumber, "invalid request URL '$rawUrl'")
		}
	}

	private fun warnAndSkip(
		warnings: MutableList<String>,
		entryNumber: Int,
		reason: String,
	): Nothing? {
		warnings += "Skipped HAR entry $entryNumber: $reason."
		return null
	}

	private fun extractRequestCookies(request: HarRequest): Map<String, String> {
		if (request.cookies.isNotEmpty()) {
			return request.cookies
				.asSequence()
				.map { it.name.orEmpty().trim() to REDACTED }
				.filter { (name, _) -> name.isNotEmpty() }
				.associate { it }
		}

		val headerValue = request.headers.firstOrNull { it.name.orEmpty().equals(COOKIE_HEADER, ignoreCase = true) }?.value
			?: return emptyMap()

		return headerValue
			.split(';')
			.mapNotNull { pair ->
				val separatorIndex = pair.indexOf('=')
				if (separatorIndex <= 0) return@mapNotNull null
				val name = pair.substring(0, separatorIndex).trim()
				if (name.isEmpty()) return@mapNotNull null
				// Always redact cookie values regardless of name
				name to REDACTED
			}
			.toMap()
	}

	private fun makeResponses(exchanges: List<CapturedExchange>): List<ParsedResponse> {
		val usedNames = mutableSetOf<String>()
		var didAssignDefault = false

		return exchanges.map { exchange ->
			val baseName = baseVariantName(exchange.statusCode, allowDefault = !didAssignDefault)
			val name = uniqueImportName(baseName, usedNames)
			usedNames.add(name)
			if (name == DEFAULT_VARIANT_NAME) didAssignDefault = true

			ParsedResponse(
				name = name,
				statusCode = exchange.statusCode,
				headers = exchange.headers,
				body = exchange.body,
				bodyIsBase64 = exchange.bodyIsBase64,
			)
		}
	}

	private fun collectCookieRules(exchanges: List<CapturedExchange>): List<RuleMatcher> =
		collectRuleMatchers(exchanges) { it.cookies }

	private fun requestQueryParameters(request: HarRequest, uri: URI): Map<String, String> {
		val queryItems = request.queryString.ifEmpty {
			uri.rawQuery
				?.split('&')
				?.mapNotNull { pair ->
					if (pair.isEmpty()) return@mapNotNull null
					val separatorIndex = pair.indexOf('=')
					val rawName = if (separatorIndex >= 0) pair.substring(0, separatorIndex) else pair
					val rawValue = if (separatorIndex >= 0) pair.substring(separatorIndex + 1) else ""
					val name = java.net.URLDecoder.decode(rawName, Charsets.UTF_8)
					val value = java.net.URLDecoder.decode(rawValue, Charsets.UTF_8)
					HarQuery(name = name, value = value)
				}
				.orEmpty()
		}

		return queryItems
			.asSequence()
			.map { it.name.orEmpty().trim() to sanitizeParamValue(it.name.orEmpty().trim(), it.value.orEmpty()) }
			.filter { (name, _) -> name.isNotEmpty() }
			.associate { it }
	}

	private fun requestQueryParameters(exchanges: List<CapturedExchange>): List<RuleMatcher> =
		collectRuleMatchers(exchanges) { it.queryParameters }

	private fun collectRuleMatchers(
		exchanges: List<CapturedExchange>,
		selector: (CapturedExchange) -> Map<String, String>,
	): List<RuleMatcher> {
		val valuesByName = linkedMapOf<String, MutableSet<String>>()
		for (exchange in exchanges) {
			for ((name, value) in selector(exchange)) {
				valuesByName.getOrPut(name) { linkedSetOf() }.add(value)
			}
		}
		return valuesByName.entries.map { (name, values) ->
			RuleMatcher(
				name = name,
				match = values.singleOrNull(),
				required = true,
				matchType = MatchType.EQUAL_TO,
			)
		}
	}

	private fun baseVariantName(statusCode: Int, allowDefault: Boolean): String {
		return when (statusCode) {
			in 200..299 -> if (allowDefault) DEFAULT_VARIANT_NAME else "$SUCCESS_VARIANT_PREFIX-$statusCode"
			in 300..399 -> "$REDIRECT_VARIANT_PREFIX-$statusCode"
			else -> "$ERROR_VARIANT_PREFIX-$statusCode"
		}
	}

	private fun noImportableEntriesMessage(warnings: List<String>): String {
		val prefix = "HAR file does not contain any importable HTTP entries."
		val firstWarning = warnings.firstOrNull() ?: return prefix
		return "$prefix $firstWarning"
	}

	// -- Sanitization helpers --

	/**
	 * Returns [REDACTED] if the header name is sensitive, otherwise strips any
	 * JWT signatures from the value and returns the result.
	 */
	private fun sanitizeHeaderValue(name: String, value: String): String {
		if (name.lowercase() in SENSITIVE_HEADER_PATTERNS) {
			logger.debug("Redacting sensitive header: {}", name)
			return REDACTED
		}
		return redactJwt(value)
	}

	/**
	 * Returns [REDACTED] if the param name matches a known-sensitive token
	 * name, otherwise strips JWT signatures from the value.
	 */
	private fun sanitizeParamValue(name: String, value: String): String {
		if (SENSITIVE_PARAM_NAMES.contains(name.lowercase())) {
			logger.debug("Redacting sensitive query/post param: {}", name)
			return REDACTED
		}
		return redactJwt(value)
	}

	/**
	 * Replaces the cryptographic signature in any JWT found in [value] with
	 * "redacted", leaving the header and payload decodable for debugging.
	 * If no JWT is present the value is returned unchanged.
	 */
	private fun redactJwt(value: String): String =
		JWT_REGEX.replace(value) { match -> "${match.groupValues[1]}.${match.groupValues[2]}.redacted" }

	private fun sanitizeStructuredBody(text: String, mimeType: String?): SanitizedBody {
		if (mimeType.orEmpty().contains("json", ignoreCase = true)) {
			val element = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return SanitizedBody(text, false)
			val sanitized = sanitizeJson(element)
			return SanitizedBody(sanitized.first.toString(), sanitized.second)
		}
		if (mimeType.orEmpty().contains("x-www-form-urlencoded", ignoreCase = true)) {
			var redacted = false
			val value = text.split('&').joinToString("&") { pair ->
				val separator = pair.indexOf('=')
				if (separator < 0) return@joinToString pair
				val name = pair.substring(0, separator)
				val decodedName = java.net.URLDecoder.decode(name, Charsets.UTF_8)
				if (decodedName.lowercase() in SENSITIVE_PARAM_NAMES) {
					redacted = true
					"$name=${java.net.URLEncoder.encode(REDACTED, Charsets.UTF_8)}"
				} else {
					pair
				}
			}
			return SanitizedBody(value, redacted)
		}
		val sanitized = redactJwt(text)
		return SanitizedBody(sanitized, sanitized != text)
	}

	private fun sanitizeJson(element: JsonElement): Pair<JsonElement, Boolean> = when (element) {
		is JsonObject -> {
			var redacted = false
			val values = element.mapValues { (key, value) ->
				if (key.lowercase() in SENSITIVE_PARAM_NAMES) {
					redacted = true
					JsonPrimitive(REDACTED)
				} else {
					val child = sanitizeJson(value)
					redacted = redacted || child.second
					child.first
				}
			}
			JsonObject(values) to redacted
		}
		is JsonArray -> {
			val children = element.map(::sanitizeJson)
			JsonArray(children.map { it.first }) to children.any { it.second }
		}
		is JsonPrimitive -> {
			val original = element.content
			val sanitized = redactJwt(original)
			if (sanitized == original) element to false else JsonPrimitive(sanitized) to true
		}
	}

	// -- HAR data model --

	private data class GroupKey(val method: String, val path: String)

	private data class ParsedHarEntry(
		val key: GroupKey,
		val exchange: CapturedExchange,
		val responseBodyRedacted: Boolean,
	)

	private data class SanitizedBody(val value: String?, val redacted: Boolean, val isBase64: Boolean = false)

	private data class CapturedExchange(
		val statusCode: Int,
		val headers: Map<String, String>,
		val body: String?,
		val bodyIsBase64: Boolean,
		val cookies: Map<String, String>,
		val queryParameters: Map<String, String>,
	)
}

private fun String?.isLikelyTextualMimeType(): Boolean {
	val mime = this?.lowercase().orEmpty()
	if (mime.isEmpty()) return true
	return mime.startsWith("text/") ||
		"json" in mime ||
		"xml" in mime ||
		"javascript" in mime ||
		"graphql" in mime ||
		"x-www-form-urlencoded" in mime ||
		"svg" in mime
}

// -- HAR JSON model (kotlinx.serialization) --

@Serializable
internal data class HarFile(val log: HarLog? = null)

@Serializable
internal data class HarLog(
	val version: String? = null,
	val creator: HarCreator? = null,
	val entries: List<HarEntry> = emptyList(),
)

@Serializable
internal data class HarCreator(
	val name: String? = null,
	val version: String? = null,
)

@Serializable
internal data class HarEntry(
	val request: HarRequest? = null,
	val response: HarResponse? = null,
)

@Serializable
internal data class HarRequest(
	val method: String? = null,
	val url: String? = null,
	val headers: List<HarHeader> = emptyList(),
	val cookies: List<HarCookie> = emptyList(),
	val queryString: List<HarQuery> = emptyList(),
	val postData: HarPostData? = null,
)

@Serializable
internal data class HarResponse(
	val status: Int? = null,
	val headers: List<HarHeader> = emptyList(),
	val content: HarContent = HarContent(),
)

@Serializable
internal data class HarHeader(
	val name: String? = null,
	val value: String? = null,
)

@Serializable
internal data class HarQuery(
	val name: String? = null,
	val value: String? = null,
)

@Serializable
internal data class HarCookie(
	val name: String? = null,
	val value: String? = null,
)

@Serializable
internal data class HarPostData(
	val mimeType: String? = null,
	val text: String? = null,
)

@Serializable
internal data class HarContent(
	val mimeType: String? = null,
	val text: String? = null,
	val encoding: String? = null,
)
