package com.moqserver.testsupport

import kotlinx.serialization.Serializable

/** One endpoint as listed by `GET /_admin/endpoints`. */
@Serializable
public data class MoqEndpoint(
    val method: String,
    val path: String,
    /** The key overrides, call counts and scenarios use: `GET /users/{id}`, or `POST /graphql#GetUser`. */
    val key: String,
    /** GraphQL operation selector to pass as `operation`; `null` for REST endpoints. */
    val operation: String? = null,
    val variants: List<String>,
    val activeVariant: String? = null,
    val currentCallCount: Int,
)

/** A runtime scenario: endpoint key → variant name. */
@Serializable
public data class MoqScenario(
    val name: String,
    val overrides: Map<String, String>,
)

/** A live runtime session. Times are Unix seconds; [expiresAt] is `null` when sessions never expire. */
@Serializable
public data class MoqSession(
    val id: String,
    val createdAt: Double,
    val lastUsedAt: Double,
    val expiresAt: Double? = null,
)

/** The result of reloading the bundle from disk. */
@Serializable
public data class MoqReloadSummary(
    val endpoints: Int,
    val scenarios: Int,
    val sessions: Int,
    /** Runtime state the new bundle could not keep. */
    val warnings: List<String>,
)

@Serializable
internal data class EndpointCallCount(val currentCallCount: Int)

/** What `GET /_admin/version` reports. [version] and [commit] are `null` for a server built from source. */
@Serializable
public data class MoqServerInfo(
    val version: String? = null,
    val commit: String? = null,
    /** Admin-API behaviours the server supports, such as `request-query`. */
    val capabilities: List<String> = emptyList(),
) {
    public fun supports(capability: String): Boolean = capability in capabilities
}
