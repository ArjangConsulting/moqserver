package com.moqserver.testsupport

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** One row of the server's request history (`GET /_admin/requests`). */
@Serializable
public data class MoqRequestRecord(
    val id: String,
    /** Unix seconds. */
    val timestamp: Double,
    val method: String,
    val path: String,
    /** The matched endpoint (`"GET /users/{id}"`), or `null` when no endpoint matched. */
    val endpoint: String? = null,
    val status: Int,
    val variant: String? = null,
    /** Why this variant was chosen, or why the request was rejected. */
    val reason: String,
    val callNumber: Int? = null,
    /** Add-on annotations keyed by add-on id, e.g. `{"jwt-claims": {"sub": "user-1"}}`. */
    val addons: Map<String, Map<String, String>>? = null,
    /** The request body, when the server runs with `--capture-request-bodies`. */
    val requestBody: MoqCapturedBody? = null,
    /** The request headers, when the server runs with `--capture-request-headers`. */
    val requestHeaders: List<MoqCapturedHeader>? = null,
    /** Set when the server refused the request itself. */
    val rejection: MoqRequestRejection? = null,
    /** The query parameters in the order sent, when the server runs with `--capture-request-query`. */
    val requestQuery: List<MoqCapturedQueryParameter>? = null,
) {
    /** Whether the request hit a path no endpoint in the bundle serves. */
    val isUnmatched: Boolean get() = endpoint == null && rejection == null

    /** Whether the server refused the request itself (see [rejection]). */
    val isRejected: Boolean get() = rejection != null

    /** Token claims recorded by the `jwt-claims` add-on, e.g. `{"sub": "u1", "iat": "1732000000"}`. */
    val claims: Map<String, String> get() = addons?.get("jwt-claims").orEmpty()

    /** The first captured header named [name] (any case). */
    public fun header(name: String): MoqCapturedHeader? = requestHeaders?.firstOrNull { it.name == name.lowercase() }

    /** Every captured value of the query parameter [name] (case-sensitive), in the order sent. Redacted values are skipped. */
    public fun queryValues(name: String): List<String> =
        requestQuery.orEmpty().filter { it.name == name }.mapNotNull { it.value }
}

/**
 * One query parameter recorded by `serve --capture-request-query`. Credential parameters carry
 * no [value], only [fingerprint].
 */
@Serializable
public data class MoqCapturedQueryParameter(
    /** The name exactly as sent (case-sensitive). */
    val name: String,
    /** The percent-decoded value, or `null` when redacted. */
    val value: String? = null,
    val truncated: Boolean = false,
    val redacted: Boolean = false,
    /** First 16 hex characters of the SHA-256 of a redacted value. */
    val fingerprint: String? = null,
)

/**
 * One request header recorded by `serve --capture-request-headers`. Credential headers carry no
 * [value]: only [scheme], [fingerprint] and, for `cookie`, [cookieNames].
 */
@Serializable
public data class MoqCapturedHeader(
    /** Lowercased header name. */
    val name: String,
    val value: String? = null,
    val truncated: Boolean = false,
    val redacted: Boolean = false,
    /** Auth scheme of a redacted credential, e.g. `Bearer`. */
    val scheme: String? = null,
    /** First 16 hex characters of the SHA-256 of the credential (after the scheme). */
    val fingerprint: String? = null,
    val cookieNames: List<String>? = null,
)

/** Why the server refused a matched request itself. A mocked 4xx variant is not a rejection. */
@Serializable
public data class MoqRequestRejection(
    /** `auth`, `request_rule`, `session`, `call_count` or `no_variant`. */
    val kind: String,
    /** The error code, e.g. `missing_required_header` or `unknown_session`. */
    val code: String,
    /** What failed, e.g. `Header 'Authorization' is required`. Never contains a credential. */
    val message: String,
)

/** A request body recorded by `serve --capture-request-bodies <bytes>`. */
@Serializable
public data class MoqCapturedBody(
    /** Text for UTF-8 bodies, base64 otherwise (see [encoding]). */
    val value: String,
    /** `"utf8"` or `"base64"`. */
    val encoding: String,
    /** Full body size in bytes, before truncation. */
    val size: Int,
    val truncated: Boolean,
) {
    /** The body parsed as a JSON object, when it is complete UTF-8 JSON. */
    public fun jsonObject(): JsonObject? {
        if (encoding != "utf8" || truncated) return null
        return runCatching { Json.parseToJsonElement(value).jsonObject }.getOrNull()
    }
}
