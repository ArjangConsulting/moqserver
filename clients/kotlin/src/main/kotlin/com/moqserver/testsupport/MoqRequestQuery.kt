package com.moqserver.testsupport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One condition on a request header, evaluated by the server (`POST /_admin/requests/query`).
 * Header predicates need the server to run with `--capture-request-headers`.
 */
public sealed interface MoqHeaderExpectation {
    public val name: String

    /** The header was sent. */
    public data class Present(override val name: String) : MoqHeaderExpectation

    /** The header was not sent. */
    public data class Absent(override val name: String) : MoqHeaderExpectation

    /** The header was sent with this auth scheme (case-insensitive), e.g. `Scheme("Authorization", "Bearer")`. */
    public data class Scheme(override val name: String, val scheme: String) : MoqHeaderExpectation

    /**
     * The header carried this credential (the token alone, without `Bearer `). The server compares
     * fingerprints; the value is never stored.
     */
    public data class Fingerprint(override val name: String, val credential: String) : MoqHeaderExpectation

    /** The header had exactly this value. Refused for credential headers, which store no value. */
    public data class Equals(override val name: String, val value: String) : MoqHeaderExpectation
}

/**
 * A question about request history, answered by the server. Every field is optional; see
 * `docs/ADMIN_API.md` ("Request queries") for the exact semantics.
 */
public data class MoqRequestQuery(
    /** A matched endpoint key, e.g. `GET /v1/users/{id}`. */
    val endpoint: String? = null,
    val method: String? = null,
    /** A concrete request path, e.g. `/v1/users/42`. */
    val path: String? = null,
    /** Unix seconds from [MoqClient.mark]; only rows at or after it. */
    val since: Double? = null,
    val rejected: Boolean? = null,
    val headers: List<MoqHeaderExpectation> = emptyList(),
    val claims: Map<String, String> = emptyMap(),
    /** Exact match count expected; `null` means at least one. */
    val times: Int? = null,
    val scope: Scope? = null,
    val includeUnattributed: Boolean? = null,
) {
    public enum class Scope {
        /** This client's history: its session's, or the global one without a session. */
        @SerialName("session")
        SESSION,

        /**
         * Global rows recorded since this client's session was created: requests that reached the
         * server without a valid `X-Mock-Session` while the test ran.
         */
        @SerialName("sessionless")
        SESSIONLESS,
    }

    internal fun wire(): RequestQueryWire =
        RequestQueryWire(
            endpoint = endpoint, method = method, path = path, since = since, rejected = rejected,
            headers = headers.takeIf { it.isNotEmpty() }?.map(::wire),
            claims = claims.takeIf { it.isNotEmpty() }, times = times, scope = scope,
            includeUnattributed = includeUnattributed,
        )

    private fun wire(expectation: MoqHeaderExpectation): HeaderPredicateWire =
        when (expectation) {
            is MoqHeaderExpectation.Present -> HeaderPredicateWire(expectation.name, present = true)
            is MoqHeaderExpectation.Absent -> HeaderPredicateWire(expectation.name, present = false)
            is MoqHeaderExpectation.Scheme -> HeaderPredicateWire(expectation.name, scheme = expectation.scheme)
            is MoqHeaderExpectation.Fingerprint ->
                HeaderPredicateWire(expectation.name, fingerprintOf = expectation.credential)
            is MoqHeaderExpectation.Equals -> HeaderPredicateWire(expectation.name, equals = expectation.value)
        }
}

@Serializable
internal data class RequestQueryWire(
    val endpoint: String? = null,
    val method: String? = null,
    val path: String? = null,
    val since: Double? = null,
    val rejected: Boolean? = null,
    val headers: List<HeaderPredicateWire>? = null,
    val claims: Map<String, String>? = null,
    val times: Int? = null,
    val scope: MoqRequestQuery.Scope? = null,
    val includeUnattributed: Boolean? = null,
)

@Serializable
internal data class HeaderPredicateWire(
    val name: String,
    val present: Boolean? = null,
    val scheme: String? = null,
    val fingerprintOf: String? = null,
    val equals: String? = null,
)

@Serializable
internal data class VariantSelectionWire(val variant: String, val times: Int? = null)

@Serializable
public data class MoqRequestNearMiss(
    val request: MoqRequestRecord,
    /** The predicates this request failed, e.g. `authorization scheme Bearer`. */
    val failed: List<String>,
)

/** The server's verdict on a [MoqRequestQuery]. */
@Serializable
public data class MoqRequestQueryResult(
    val ok: Boolean,
    val count: Int,
    val expected: Int? = null,
    /** Matching requests, oldest first, so a test can compare a retry with the first call. */
    val matched: List<MoqRequestRecord> = emptyList(),
    val nearMisses: List<MoqRequestNearMiss> = emptyList(),
    /** Session-less requests with no token claims, when a session check filtered by claims. */
    val unattributed: List<MoqRequestRecord> = emptyList(),
    /** A readable verdict, used as the assertion failure message. */
    val message: String,
)

/** Thrown by the `assert…` request checks; the message is the server's verdict. */
public class MoqRequestAssertionException(public val result: MoqRequestQueryResult) : AssertionError(result.message)
