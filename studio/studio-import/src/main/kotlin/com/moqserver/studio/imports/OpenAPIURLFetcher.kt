package com.moqserver.studio.imports

import com.moqserver.studio.logging.loggerFor
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.isSuccess
import java.net.URI
import java.util.Base64
import javax.net.ssl.SSLException

/**
 * Authentication configuration for URL-based OpenAPI import.
 */
sealed class URLImportAuth {
	data class Bearer(val token: String) : URLImportAuth()
	data class Basic(val username: String, val password: String) : URLImportAuth()
}

/**
 * Result of a successful spec fetch from a URL.
 */
data class FetchedSpec(
	/** The raw spec content (JSON or YAML). */
	val content: String,
	/** The URL from which the spec was actually fetched (may differ from input if auto-discovered). */
	val resolvedUrl: String,
	/** A human-readable source name for display (e.g. the host + path). */
	val sourceName: String,
)

/**
 * Exception thrown when a URL import fails, with a user-friendly message.
 */
class URLFetchException(
	/** User-facing error message describing what went wrong. */
	message: String,
	/** Technical detail for logging (not shown to user). */
	val technicalDetail: String? = null,
	cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Fetches OpenAPI specifications from URLs.
 *
 * Handles two scenarios:
 * 1. Direct spec URLs -- the URL points to a JSON/YAML OpenAPI spec file.
 * 2. Swagger UI / docs pages -- the URL points to an HTML page that hosts a Swagger UI
 *    or similar documentation tool. The fetcher auto-discovers the spec URL from the HTML
 *    and fetches the actual spec.
 *
 * Supports optional Bearer or Basic authentication for protected endpoints.
 * Large responses are streamed to a temp file to avoid excessive memory usage.
 */
class OpenAPIURLFetcher(
	private val client: HttpClient = defaultClient(),
) {
	private val logger = loggerFor<OpenAPIURLFetcher>()
	private val readResponseBody: suspend (io.ktor.client.statement.HttpResponse) -> String = { response ->
		val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L
		if (contentLength > MAX_RESPONSE_BYTES) {
			raise(responseTooLargeException(contentLength))
		}
		val body = response.bodyAsText()
		if (body.length > MAX_RESPONSE_BYTES) {
			raise(responseTooLargeException(body.length.toLong()))
		}
		body
	}
	private val fetchDiscoveredSpecAtUrl: suspend (String, URLImportAuth?) -> FetchedSpec = { specUrl, auth ->
		val response = try {
			client.get(specUrl) {
				header(HttpHeaders.Accept, "$ACCEPT_JSON, $ACCEPT_YAML, $ACCEPT_HTML, $ACCEPT_WILDCARD")
				header(HttpHeaders.UserAgent, USER_AGENT)
				applyAuth(auth)
			}
		} catch (e: Exception) {
			raise(mapNetworkException(e, specUrl))
		}
		if (!response.status.isSuccess()) {
			raise(httpStatusException(response.status, specUrl))
		}
		val body = readResponseBody(response)
		asFetchedSpec(body, response.headers[HttpHeaders.ContentType].orEmpty(), specUrl)
			?: raise(invalidDiscoveredSpecException(specUrl))
	}
	private val tryFetchWellKnownSpecAtUrl: suspend (String, URLImportAuth?) -> FetchedSpec? = { candidateUrl, auth ->
		try {
			logger.debug("Trying well-known spec path: {}", candidateUrl)
			val response = client.get(candidateUrl) {
				header(HttpHeaders.Accept, "$ACCEPT_JSON, $ACCEPT_YAML, $ACCEPT_WILDCARD")
				header(HttpHeaders.UserAgent, USER_AGENT)
				applyAuth(auth)
			}
			if (!response.status.isSuccess()) {
				null
			} else {
				val body = response.bodyAsText()
				asFetchedSpec(body, response.headers[HttpHeaders.ContentType].orEmpty(), candidateUrl)
			}
		} catch (e: Exception) {
			logger.debug("Well-known path {} failed: {}", candidateUrl, e.message)
			null
		}
	}

	/**
	 * Fetch an OpenAPI spec from the given URL.
	 *
	 * @param url The URL to fetch from (direct spec or docs page).
	 * @param auth Optional authentication credentials.
	 * @return A [FetchedSpec] containing the raw spec content and metadata.
	 * @throws URLFetchException if the spec cannot be fetched or discovered.
	 */
	suspend fun fetchSpec(url: String, auth: URLImportAuth? = null): FetchedSpec {
		val normalizedUrl = normalizeUrl(url)
		logger.info("Fetching OpenAPI spec from URL: {}", normalizedUrl)

		val response = try {
			client.get(normalizedUrl) {
				header(HttpHeaders.Accept, "$ACCEPT_JSON, $ACCEPT_YAML, $ACCEPT_HTML, $ACCEPT_WILDCARD")
				header(HttpHeaders.UserAgent, USER_AGENT)
				applyAuth(auth)
			}
		} catch (e: Exception) {
			raise(mapNetworkException(e, normalizedUrl))
		}
		if (!response.status.isSuccess()) {
			raise(httpStatusException(response.status, normalizedUrl))
		}
		val contentType = response.headers[HttpHeaders.ContentType].orEmpty().lowercase()
		val body = readResponseBody(response)

		// If the response looks like a spec (JSON/YAML), return it directly.
		if (looksLikeSpec(body, contentType)) {
			logger.info("URL returned direct spec content ({} chars)", body.length)
			return FetchedSpec(
				content = body,
				resolvedUrl = normalizedUrl,
				sourceName = sourceNameFromUrl(normalizedUrl),
			)
		}

		// If the response is HTML, try to auto-discover the spec URL.
		if (looksLikeHtml(contentType, body)) {
			logger.info("URL returned HTML content, attempting auto-discovery of spec URL")
			return discoverSpecFromHtml(body, normalizedUrl, auth)
		}

		// Neither spec nor HTML -- unclear content.
		raise(
			URLFetchException(
				"The URL returned unexpected content (Content-Type: ${contentType.ifEmpty { "unknown" }}). " +
					"Please provide a direct link to an OpenAPI spec (.json or .yaml) or a Swagger UI / API docs page.",
				technicalDetail = "Content-Type=$contentType, body-prefix=${body.take(BODY_PREFIX_LENGTH)}",
			),
		)
	}

	/**
	 * Close the underlying HTTP client. Call when the fetcher is no longer needed.
	 */
	fun close() {
		client.close()
	}

	// -- Private helpers --

	private suspend fun discoverSpecFromHtml(
		html: String,
		baseUrl: String,
		auth: URLImportAuth?,
	): FetchedSpec {
		val discoveredUrl = discoverSpecUrlFromHtml(html, baseUrl)
		if (discoveredUrl != null) {
			logger.info("Discovered spec URL from HTML: {}", discoveredUrl)
			// Discovered URLs come from page content the user never typed. Only forward
			// the user's credentials when the target shares the original URL's origin,
			// so a hostile docs page cannot exfiltrate the token to another host.
			return fetchDiscoveredSpecAtUrl(discoveredUrl, authForTarget(discoveredUrl, baseUrl, auth))
		}

		val candidateUrls = buildCandidateUrls(baseUrl)
		if (candidateUrls != null) {
			for (candidateUrl in candidateUrls) {
				val result = tryFetchWellKnownSpecAtUrl(candidateUrl, authForTarget(candidateUrl, baseUrl, auth))
				if (result != null) {
					logger.info("Found spec at well-known path: {}", candidateUrl)
					return result
				}
			}
		}

		logger.warn("Could not auto-discover OpenAPI spec from HTML at: {}", baseUrl)
		raise(
			URLFetchException(
				"The page at this URL appears to be an HTML page, but we couldn't auto-discover an OpenAPI spec " +
					"from it. Try providing the direct URL to the .json or .yaml spec file. You can usually find " +
					"this in the page source (look for a URL ending in swagger.json, openapi.json, or similar).",
				technicalDetail = "HTML auto-discovery exhausted all strategies for $baseUrl",
			),
		)
	}

	private fun discoverSpecUrlFromHtml(html: String, baseUrl: String): String? {
		extractSwaggerBundleUrl(html, baseUrl)?.let { return it }
		return extractEmbeddedSpecUrl(html, baseUrl)
	}

	private fun asFetchedSpec(body: String, contentType: String, url: String): FetchedSpec? {
		if (!looksLikeSpec(body, contentType)) {
			return null
		}
		return FetchedSpec(
			content = body,
			resolvedUrl = url,
			sourceName = sourceNameFromUrl(url),
		)
	}

	private fun invalidDiscoveredSpecException(specUrl: String): URLFetchException {
		return URLFetchException(
			"Auto-discovered a potential spec URL ($specUrl) but its content doesn't appear to be a valid OpenAPI spec.",
			technicalDetail = "Discovered URL did not return JSON/YAML spec content",
		)
	}

	private fun raise(error: URLFetchException): Nothing = throw error

	private fun responseTooLargeException(size: Long): URLFetchException {
		return URLFetchException(
			"The response is too large to import (${size / BYTES_PER_MEGABYTE} MB, limit ${MAX_RESPONSE_BYTES / BYTES_PER_MEGABYTE} MB). " +
				"Provide a direct link to the OpenAPI spec file instead.",
			technicalDetail = "Response size $size exceeds MAX_RESPONSE_BYTES=$MAX_RESPONSE_BYTES",
		)
	}

	companion object {
		private const val BYTES_PER_MEGABYTE = 1_048_576L
		private const val MAX_RESPONSE_BYTES = 50 * BYTES_PER_MEGABYTE
		private const val BODY_PREFIX_LENGTH = 200
		private const val ACCEPT_YAML = "application/x-yaml"
		private const val ACCEPT_JSON = "application/json"
		private const val ACCEPT_HTML = "text/html"
		private const val ACCEPT_WILDCARD = "*/*"
		private const val USER_AGENT = "moqserver-studio/1.0"
		private const val CONNECT_TIMEOUT_MS = 15_000L
		private const val REQUEST_TIMEOUT_MS = 60_000L

		private val WELL_KNOWN_SPEC_PATHS = listOf(
			"openapi.json",
			"openapi.yaml",
			"openapi.yml",
			"swagger.json",
			"swagger.yaml",
			"swagger.yml",
			"v3/api-docs",
			"api-docs",
			"v2/api-docs",
			"swagger/v1/swagger.json",
			"api/swagger.json",
			"api/openapi.json",
			"docs/openapi.json",
			"api/v1/openapi.json",
			"api/v1/swagger.json",
		)

		/**
		 * Regex patterns for extracting spec URLs from Swagger UI HTML pages.
		 */
		private val SWAGGER_BUNDLE_URL_PATTERNS = listOf(
			Regex("""SwaggerUIBundle\s*\(\s*\{[^}]*?url\s*:\s*["']([^"']+)["']"""),
			Regex("""configUrl\s*:\s*["']([^"']+)["']"""),
			Regex(
				"""(?:swagger|openapi|spec)\s*[=:]\s*["']([^"']+\.(?:json|yaml|yml))["']""",
				RegexOption.IGNORE_CASE,
			),
		)

		/**
		 * Patterns for finding spec URLs embedded anywhere in HTML content.
		 */
		private val EMBEDDED_SPEC_URL_PATTERNS = listOf(
			Regex(
				"""["']((?:https?://)?[^"'\s]+?(?:/openapi|/swagger|/api-docs)[^"'\s]*\.(?:json|yaml|yml))["']""",
				RegexOption.IGNORE_CASE,
			),
			Regex(
				"""["'](/[^"'\s]*?(?:openapi|swagger)\.[^"'\s]*?(?:json|yaml|yml))["']""",
				RegexOption.IGNORE_CASE,
			),
			Regex(
				"""["']((?:https?://)?[^"'\s]*?/v[23]/api-docs[^"'\s]*)["']""",
				RegexOption.IGNORE_CASE,
			),
			Regex(
				"""["'](/api-docs[^"'\s]*)["']""",
				RegexOption.IGNORE_CASE,
			),
		)

		internal fun normalizeUrl(url: String): String {
			val trimmed = url.trim()
			return if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
				"https://$trimmed"
			} else {
				trimmed
			}
		}

		internal fun looksLikeSpec(content: String, contentType: String): Boolean {
			val ct = contentType.lowercase()
			val trimmed = content.trimStart()
			val isJsonSpec = trimmed.startsWith("{") &&
				(trimmed.contains("\"openapi\"") || trimmed.contains("\"swagger\""))
			val isYamlSpec = Regex("""^(openapi|swagger)\s*:""", RegexOption.MULTILINE)
				.containsMatchIn(trimmed)
			val isSpecContentType = (ct.contains("openapi") || ct.contains("x-yaml")) &&
				(trimmed.startsWith("{") || trimmed.startsWith("openapi") || trimmed.startsWith("swagger"))
			return isJsonSpec || isYamlSpec || isSpecContentType
		}

		internal fun extractSwaggerBundleUrl(html: String, baseUrl: String): String? {
			for (pattern in SWAGGER_BUNDLE_URL_PATTERNS) {
				val match = pattern.find(html)
				if (match != null) {
					return resolveRelativeUrl(match.groupValues[1], baseUrl)
				}
			}
			return null
		}

		internal fun extractEmbeddedSpecUrl(html: String, baseUrl: String): String? {
			for (pattern in EMBEDDED_SPEC_URL_PATTERNS) {
				val match = pattern.find(html)
				if (match != null) {
					return resolveRelativeUrl(match.groupValues[1], baseUrl)
				}
			}
			return null
		}

		/** Returns [auth] only when [targetUrl] shares scheme, host, and port with [baseUrl]. */
		internal fun authForTarget(targetUrl: String, baseUrl: String, auth: URLImportAuth?): URLImportAuth? {
			if (auth == null) return null
			return if (isSameOrigin(targetUrl, baseUrl)) auth else null
		}

		internal fun isSameOrigin(targetUrl: String, baseUrl: String): Boolean {
			val target = parseOrigin(targetUrl) ?: return false
			val base = parseOrigin(baseUrl) ?: return false
			return target == base
		}

		private data class UrlOrigin(val scheme: String, val host: String, val port: Int)

		private fun parseOrigin(url: String): UrlOrigin? {
			val uri = try {
				URI(url)
			} catch (_: Exception) {
				return null
			}
			val scheme = uri.scheme?.lowercase() ?: return null
			val host = uri.host?.lowercase() ?: return null
			val port = if (uri.port > 0) uri.port else defaultPortForScheme(scheme)
			return UrlOrigin(scheme = scheme, host = host, port = port)
		}

		internal fun resolveRelativeUrl(rawUrl: String, baseUrl: String): String {
			if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
				return rawUrl
			}
			val baseUri = try {
				URI(baseUrl)
			} catch (_: Exception) {
				return rawUrl
			}
			return try {
				baseUri.resolve(rawUrl).toString()
			} catch (_: Exception) {
				rawUrl
			}
		}

		internal fun sourceNameFromUrl(url: String): String {
			return try {
				val uri = URI(url)
				val host = uri.host ?: return url
				val path = uri.path?.trimEnd('/') ?: ""
				if (path.isNotEmpty()) "$host$path" else host
			} catch (_: Exception) {
				url
			}
		}

		private fun looksLikeHtml(contentType: String, body: String): Boolean {
			val trimmed = body.trimStart()
			return contentType.contains("html") ||
				trimmed.startsWith("<!") ||
				trimmed.startsWith("<html")
		}

		private fun io.ktor.client.request.HttpRequestBuilder.applyAuth(auth: URLImportAuth?) {
			when (auth) {
				is URLImportAuth.Bearer -> header(HttpHeaders.Authorization, "Bearer ${auth.token}")
				is URLImportAuth.Basic -> {
					val encoded = Base64.getEncoder()
						.encodeToString("${auth.username}:${auth.password}".toByteArray())
					header(HttpHeaders.Authorization, "Basic $encoded")
				}
				null -> {}
			}
		}

		private fun tryExtractHost(url: String): String {
			return try {
				Url(url).host
			} catch (_: Exception) {
				url
			}
		}

		private fun mapNetworkException(e: Exception, url: String): URLFetchException {
			val host = tryExtractHost(url)
			return when (e) {
				is SSLException -> URLFetchException(
					"Could not establish a secure connection to $host. " +
						"The server's TLS certificate may be self-signed, expired, or untrusted by your system.",
					technicalDetail = "SSLException: ${e.message}",
					cause = e,
				)
				is java.net.UnknownHostException -> URLFetchException(
					"Could not resolve host '$host'. Check the URL and your internet connection.",
					technicalDetail = "UnknownHostException: ${e.message}",
					cause = e,
				)
				is java.net.ConnectException -> URLFetchException(
					"Could not connect to $host. The server may be unreachable or the port may be blocked.",
					technicalDetail = "ConnectException: ${e.message}",
					cause = e,
				)
				else -> URLFetchException(
					"Failed to fetch the URL: ${e.message ?: "Unknown error"}. " +
						"Check the URL and your internet connection.",
					technicalDetail = "${e::class.simpleName}: ${e.message}",
					cause = e,
				)
			}
		}

		private fun httpStatusException(
			status: HttpStatusCode,
			url: String,
		): URLFetchException {
			val host = tryExtractHost(url)
			return when {
				status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden ->
					URLFetchException(
						"Access denied (HTTP ${status.value}). The server requires authentication. " +
							"Try adding credentials using the Bearer Token or Basic Auth options.",
						technicalDetail = "HTTP ${status.value} ${status.description} from $url",
					)
				status == HttpStatusCode.NotFound ->
					URLFetchException(
						"The URL returned 404 Not Found. Check that the URL is correct.",
						technicalDetail = "HTTP 404 from $url",
					)
				status == HttpStatusCode.NotAcceptable ->
					URLFetchException(
						"The server rejected our request (HTTP 406 Not Acceptable). " +
							"This usually means the server has strict content negotiation. " +
							"Try appending ?format=json or ?format=openapi to the URL.",
						technicalDetail = "HTTP 406 from $url",
					)
				status.value in HTTP_CLIENT_ERROR_RANGE ->
					URLFetchException(
						"The server returned an error (HTTP ${status.value}). " +
							"Check that the URL is correct and accessible.",
						technicalDetail = "HTTP ${status.value} ${status.description} from $url",
					)
				status.value in HTTP_SERVER_ERROR_RANGE ->
					URLFetchException(
						"The server at $host returned an error (HTTP ${status.value}). " +
							"The server may be temporarily unavailable.",
						technicalDetail = "HTTP ${status.value} ${status.description} from $url",
					)
				else ->
					URLFetchException(
						"Unexpected HTTP response (${status.value}) from $host.",
						technicalDetail = "HTTP ${status.value} ${status.description} from $url",
					)
			}
		}

		private val HTTP_CLIENT_ERROR_RANGE = 400..499
		private val HTTP_SERVER_ERROR_RANGE = 500..599

		private fun buildCandidateUrls(baseUrl: String): List<String>? {
			val baseUri = try {
				URI(baseUrl)
			} catch (_: Exception) {
				return null
			}
			val origin = buildOrigin(baseUri)
			val currentPath = baseUri.path.trimEnd('/')
			val parentPath = currentPath.substringBeforeLast('/', "")

			return buildList {
				WELL_KNOWN_SPEC_PATHS.forEach { add("$origin$currentPath/$it") }
				if (parentPath.isNotEmpty()) {
					WELL_KNOWN_SPEC_PATHS.forEach { add("$origin$parentPath/$it") }
				}
				WELL_KNOWN_SPEC_PATHS.forEach { add("$origin/$it") }
			}.distinct()
		}

		private fun buildOrigin(uri: URI): String = buildString {
			append(uri.scheme)
			append("://")
			append(uri.host)
			if (uri.port > 0 && uri.port != defaultPortForScheme(uri.scheme)) {
				append(":${uri.port}")
			}
		}

		private fun defaultPortForScheme(scheme: String): Int = when (scheme) {
			"https" -> 443
			"http" -> 80
			else -> -1
		}

		internal fun defaultClient(): HttpClient = HttpClient(CIO) {
			install(HttpTimeout) {
				connectTimeoutMillis = CONNECT_TIMEOUT_MS
				requestTimeoutMillis = REQUEST_TIMEOUT_MS
			}
			followRedirects = true
			expectSuccess = false
		}
	}
}
