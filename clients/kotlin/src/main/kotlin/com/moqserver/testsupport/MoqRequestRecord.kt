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
) {
    /** Whether the request hit a path no endpoint in the bundle serves. */
    val isUnmatched: Boolean get() = endpoint == null
}

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
