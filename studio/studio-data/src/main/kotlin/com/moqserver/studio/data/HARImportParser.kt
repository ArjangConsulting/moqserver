package com.moqserver.studio.data

import com.moqserver.studio.domain.ParsedEndpoint
import com.moqserver.studio.domain.ParsedResponse
import com.moqserver.studio.domain.ParsedSpec
import com.moqserver.studio.logging.loggerFor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.Base64

/**
 * Parses HAR 1.2 files into ParsedSpec for import into Studio.
 * Mirrors the Swift HARParser logic: groups entries by method+path,
 * deduplicates, and generates variant names.
 */
class HARImportParser {

    private val json = Json { ignoreUnknownKeys = true }
    private val logger = loggerFor<HARImportParser>()

    fun parse(content: String): ParsedSpec {
        logger.info("Parsing HAR file ({} bytes)", content.length)
        val har = runCatching { json.decodeFromString<HarFile>(content) }
            .getOrElse { error ->
                throw IllegalArgumentException("Unable to parse HAR file: ${error.message}", error)
            }
        val entries = har.log.entries
        logger.debug("HAR: creator={}, version={}, entries={}", har.log.creator?.name, har.log.version, entries.size)
        require(entries.isNotEmpty()) { "HAR file does not contain any importable HTTP entries." }

        val grouped = mutableMapOf<GroupKey, MutableList<CapturedExchange>>()

        for (entry in entries) {
            val uri = try { URI(entry.request.url) } catch (_: Exception) { continue }
            val method = entry.request.method.trim().uppercase()
            if (method.isBlank()) continue
            val path = normalizedPath(uri)
            val key = GroupKey(method, path)

            val exchange = CapturedExchange(
                statusCode = normalizedStatusCode(entry.response.status),
                headers = responseHeaders(entry.response),
                body = responseBody(entry.response),
            )

            val existing = grouped.getOrPut(key) { mutableListOf() }
            if (exchange !in existing) {
                existing.add(exchange)
            }
        }

        val endpoints = grouped.entries
            .sortedWith(compareBy({ it.key.path }, { it.key.method }))
            .map { (key, exchanges) ->
                val responses = makeResponses(exchanges)
                logger.debug("HAR endpoint: {} {} → {} variant(s)", key.method, key.path, responses.size)
                ParsedEndpoint(
                    method = key.method,
                    path = key.path,
                    responses = responses,
                )
            }

        require(endpoints.isNotEmpty()) {
            "HAR file does not contain any importable HTTP entries."
        }

        val title = har.log.creator?.name?.let { "$it HAR Import" } ?: "HAR Import"
        val version = har.log.creator?.version ?: har.log.version

        return ParsedSpec(
            title = title,
            version = version,
            endpoints = endpoints,
        ).also {
            logger.info(
                "HAR parse complete: '{}' — {} endpoint(s) from {} raw entries",
                title,
                endpoints.size,
                entries.size,
            )
        }
    }

    /** Detect potentially sensitive headers in HAR entries. */
    fun detectSensitiveHeaders(content: String): List<String> {
        val har = json.decodeFromString<HarFile>(content)
        val sensitivePatterns = listOf(
            "authorization", "cookie", "set-cookie",
            "x-api-key", "x-auth-token", "x-csrf-token",
        )
        val found = mutableSetOf<String>()
        for (entry in har.log.entries) {
            for (header in entry.request.headers) {
                if (sensitivePatterns.any { header.name.lowercase().contains(it) }) {
                    found.add(header.name)
                }
            }
            for (header in entry.response.headers) {
                if (sensitivePatterns.any { header.name.lowercase().contains(it) }) {
                    found.add(header.name)
                }
            }
        }
        if (found.isNotEmpty()) {
            logger.warn("Detected {} sensitive header(s) in HAR: {}", found.size, found.sorted())
        }
        return found.sorted()
    }

    private fun normalizedPath(uri: URI): String {
        val path = uri.path ?: "/"
        return if (path.isEmpty()) "/" else if (path.startsWith("/")) path else "/$path"
    }

    private fun normalizedStatusCode(status: Int): Int {
        return if (status in 100..599) status else 200
    }

    private fun responseHeaders(response: HarResponse): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        for (header in response.headers) {
            if (header.name.isNotEmpty()) {
                headers[header.name] = header.value.orEmpty()
            }
        }
        val mimeType = response.content.mimeType
        if (!mimeType.isNullOrEmpty() &&
            headers.keys.none { it.equals("Content-Type", ignoreCase = true) }
        ) {
            headers["Content-Type"] = mimeType
        }
        return headers
    }

    private fun responseBody(response: HarResponse): String? {
        val text = response.content.text ?: return null

        if (response.content.encoding?.lowercase() == "base64") {
            return try {
                String(Base64.getDecoder().decode(text), Charsets.UTF_8)
            } catch (_: Exception) {
                text
            }
        }
        return text
    }

    private fun makeResponses(exchanges: List<CapturedExchange>): List<ParsedResponse> {
        val usedNames = mutableSetOf<String>()
        var didAssignDefault = false

        return exchanges.map { exchange ->
            val baseName = baseVariantName(exchange.statusCode, allowDefault = !didAssignDefault)
            val name = uniqueName(baseName, usedNames)
            usedNames.add(name)
            if (name == "default") didAssignDefault = true

            ParsedResponse(
                name = name,
                statusCode = exchange.statusCode,
                headers = exchange.headers,
                body = exchange.body,
            )
        }
    }

    private fun baseVariantName(statusCode: Int, allowDefault: Boolean): String {
        return when {
            statusCode in 200..299 -> if (allowDefault) "default" else "success-$statusCode"
            statusCode in 300..399 -> "redirect-$statusCode"
            else -> "error-$statusCode"
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

    // -- HAR data model --

    private data class GroupKey(val method: String, val path: String)

    private data class CapturedExchange(
        val statusCode: Int,
        val headers: Map<String, String>,
        val body: String?,
    )
}

// -- HAR JSON model (kotlinx.serialization) --

@Serializable
internal data class HarFile(val log: HarLog)

@Serializable
internal data class HarLog(
    val version: String,
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
    val request: HarRequest,
    val response: HarResponse,
)

@Serializable
internal data class HarRequest(
    val method: String,
    val url: String,
    val headers: List<HarHeader> = emptyList(),
    val queryString: List<HarQuery> = emptyList(),
    val postData: HarPostData? = null,
)

@Serializable
internal data class HarResponse(
    val status: Int,
    val headers: List<HarHeader> = emptyList(),
    val content: HarContent = HarContent(),
)

@Serializable
internal data class HarHeader(
    val name: String,
    val value: String? = null,
)

@Serializable
internal data class HarQuery(
    val name: String,
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
