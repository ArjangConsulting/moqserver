package com.moqserver.testsupport

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Drives a running moqserver's admin API from JVM or Android instrumentation tests: sessions,
 * variant selection, scenarios, and request history. Mirrors the Swift `MoqClient` in
 * `server/MoqTestSupport`.
 *
 * Calls block the calling thread (never call from Android's main thread); [suspending] offers the
 * same calls as `suspend` functions for coroutine tests. One immutable client per suite;
 * [createSession] returns a client scoped to a new isolated session, whose id the app under test must
 * send as `X-Mock-Session` on its mock requests.
 */
public class MoqClient(
    baseUrl: String,
    private val auth: Auth? = null,
    private val timeoutMillis: Int = 10_000,
    /** The `X-Mock-Session` id this client sends, or `null` for global state. */
    public val sessionId: String? = null,
) {
    /** Credentials for a server whose config has an `admin` section. */
    public sealed interface Auth {
        public data class Bearer(val token: String) : Auth

        public data class ApiKey(val header: String, val value: String) : Auth
    }

    public val baseUrl: String = baseUrl.trimEnd('/')

    /** The same calls as `suspend` functions, run on [kotlinx.coroutines.Dispatchers.IO]. */
    public val suspending: SuspendingMoqClient get() = SuspendingMoqClient(this)

    // MARK: Sessions

    /** Creates an isolated session and returns a client scoped to it. */
    public fun createSession(): MoqClient {
        val body = send("POST", "/_admin/sessions")
        val result = json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), body)
        val id = result["id"] ?: throw MoqControlException.Transport("Missing session ID")
        return MoqClient(baseUrl, auth, timeoutMillis, sessionId = id)
    }

    /** Releases this client's session; a no-op for a global client. */
    public fun closeSession() {
        val id = sessionId ?: return
        send("DELETE", "/_admin/sessions/${encodeSegment(id)}")
    }

    // MARK: Variants and scenarios

    /** Activates a scenario declared in the bundle (`scenarios:`) or defined at runtime. */
    public fun activateScenario(name: String) {
        send("PUT", "/_admin/scenario", encode(mapOf("name" to name)))
    }

    /** Scenarios defined in the bundle or at runtime. */
    public fun scenarios(): List<MoqScenario> =
        json.decodeFromString(ListSerializer(MoqScenario.serializer()), send("GET", "/_admin/scenarios"))

    /** Defines (or replaces) a runtime scenario; keys are endpoint keys as listed by [endpoints]. */
    public fun defineScenario(name: String, overrides: Map<String, String>) {
        send("PUT", "/_admin/scenarios", json.encodeToString(MoqScenario.serializer(), MoqScenario(name, overrides)))
    }

    public fun removeScenario(name: String) {
        send("DELETE", "/_admin/scenarios", encode(mapOf("name" to name)))
    }

    /** Clears runtime overrides and call counters. */
    public fun resetAll() {
        send("DELETE", "/_admin/state")
    }

    /**
     * Makes [variant] the active response. For a GraphQL operation on a shared route, pass its
     * [operation] (the name, or the selector listed by [endpoints]).
     */
    @JvmOverloads
    public fun selectVariant(variant: String, method: String, path: String, operation: String? = null) {
        send("PUT", endpointPath(method, path, "variant", operation), encode(mapOf("variant" to variant)))
    }

    /**
     * Makes [variant] the active response for the next [times] requests that reach variant
     * selection, then returns the endpoint to normal selection. Use it to fail one call and let the
     * app's retry succeed.
     */
    @JvmOverloads
    public fun selectVariant(variant: String, method: String, path: String, times: Int, operation: String? = null) {
        val body = json.encodeToString(VariantSelectionWire.serializer(), VariantSelectionWire(variant, times))
        send("PUT", endpointPath(method, path, "variant", operation), body)
    }

    @JvmOverloads
    public fun resetVariant(method: String, path: String, operation: String? = null) {
        send("DELETE", endpointPath(method, path, "variant", operation))
    }

    @JvmOverloads
    public fun resetCallCount(method: String, path: String, operation: String? = null) {
        send("DELETE", endpointPath(method, path, "call-count", operation))
    }

    @JvmOverloads
    public fun resetAll(method: String, path: String, operation: String? = null) {
        send("DELETE", endpointPath(method, path, "state", operation))
    }

    // MARK: Inspection

    /** Every endpoint the server serves (this session's view when the client has one). */
    public fun endpoints(): List<MoqEndpoint> =
        json.decodeFromString(ListSerializer(MoqEndpoint.serializer()), send("GET", "/_admin/endpoints"))

    /** How many times `method path` (or one GraphQL [operation]) has been called since the last reset. */
    @JvmOverloads
    public fun callCount(method: String, path: String, operation: String? = null): Int =
        json.decodeFromString(EndpointCallCount.serializer(), send("GET", endpointPath(method, path, "", operation)))
            .currentCallCount

    /** Live sessions on the server, oldest first. */
    public fun sessions(): List<MoqSession> =
        json.decodeFromString(ListSerializer(MoqSession.serializer()), send("GET", "/_admin/sessions"))

    /** Re-reads the bundle from disk; the server keeps the previous version if the new one is invalid. */
    public fun reload(): MoqReloadSummary = json.decodeFromString(MoqReloadSummary.serializer(), send("POST", "/_admin/reload"))

    // MARK: Request history

    /** Request history, newest first (at most 500 rows), scoped to this client's session. */
    public fun requests(): List<MoqRequestRecord> =
        json.decodeFromString(ListSerializer(MoqRequestRecord.serializer()), send("GET", "/_admin/requests"))

    /**
     * Requests to paths no endpoint serves, oldest first. The server decides what counts
     * (`POST /_admin/requests/query` with `unmatched: true`), so every client agrees.
     */
    public fun unmatchedRequests(): List<MoqRequestRecord> = query(MoqRequestQuery(unmatched = true)).matched

    public fun clearRequests() {
        send("DELETE", "/_admin/requests")
    }

    /**
     * Throws [MoqUnmatchedRequestsException] when the app called any path the bundle doesn't mock.
     * Call at the end of a test, before [closeSession].
     */
    public fun assertNoUnmatchedRequests() {
        val unmatched = unmatchedRequests()
        if (unmatched.isNotEmpty()) throw MoqUnmatchedRequestsException(unmatched)
    }

    // MARK: Request assertions

    /** Asks the server to evaluate [query] over this client's request history. */
    public fun query(query: MoqRequestQuery): MoqRequestQueryResult =
        json.decodeFromString(
            MoqRequestQueryResult.serializer(),
            send("POST", "/_admin/requests/query", json.encodeToString(RequestQueryWire.serializer(), query.wire())),
        )

    /** The server's current time, to pass as `since` so a check only sees later requests. */
    public fun mark(): Double {
        val body = send("POST", "/_admin/requests/mark")
        val result = json.decodeFromString(MapSerializer(String.serializer(), Double.serializer()), body)
        return result["timestamp"] ?: throw MoqControlException.Transport("Missing timestamp")
    }

    /**
     * Re-runs [query] until it passes or [timeoutMillis] elapses (`0` checks once), then throws
     * [MoqRequestAssertionException] with the server's verdict.
     */
    internal fun assertQuery(query: MoqRequestQuery, timeoutMillis: Long): MoqRequestQueryResult {
        val deadline = System.nanoTime() + timeoutMillis.coerceAtLeast(0) * NANOS_PER_MILLI
        while (true) {
            val result = query(query)
            if (result.ok) return result
            val remaining = (deadline - System.nanoTime()) / NANOS_PER_MILLI
            if (remaining <= 0) throw MoqRequestAssertionException(result)
            Thread.sleep(minOf(ASSERT_RETRY_MILLIS, remaining))
        }
    }

    /**
     * Asserts that [endpoint] (e.g. `GET /v1/me`) was called [times] times (at least once when
     * `null`) with every header expectation and claim holding, and returns those calls oldest first.
     * [timeoutMillis] waits for calls the app hasn't made yet.
     *
     * ```kotlin
     * val calls = client.assertRequest(
     *     "GET /v1/me", times = 2, headers = listOf(MoqHeaderExpectation.Scheme("Authorization", "Bearer")),
     *     claims = mapOf("sub" to uid))
     * assertTrue(calls[0].claims.getValue("iat").toLong() < calls[1].claims.getValue("iat").toLong())
     * ```
     */
    @JvmOverloads
    public fun assertRequest(
        endpoint: String,
        times: Int? = null,
        headers: List<MoqHeaderExpectation> = emptyList(),
        claims: Map<String, String> = emptyMap(),
        since: Double? = null,
        timeoutMillis: Long = 0,
    ): List<MoqRequestRecord> {
        val query = MoqRequestQuery(endpoint = endpoint, since = since, headers = headers, claims = claims, times = times)
        return assertQuery(query, timeoutMillis).matched
    }

    /** Requests the server refused itself (auth, request rules, session, call count), oldest first. */
    @JvmOverloads
    public fun rejectedRequests(since: Double? = null): List<MoqRequestRecord> =
        query(MoqRequestQuery(since = since, rejected = true)).matched

    /** Throws [MoqRequestAssertionException] naming each rejected request and the rule it broke. */
    @JvmOverloads
    public fun assertNoRejectedRequests(since: Double? = null) {
        assertQuery(MoqRequestQuery(since = since, rejected = true, times = 0), 0)
    }

    /**
     * Throws when a request reached the server without a valid `X-Mock-Session` since this client's
     * session was created. Needs a session-scoped client ([createSession]).
     *
     * With [claims] (e.g. `mapOf("sub" to uid)`), only session-less requests carrying this test's
     * token count, so parallel workers sharing one server don't fail each other. Requests with no
     * token can't be attributed: they count only with [includeUnattributed], and are returned
     * either way.
     */
    @JvmOverloads
    public fun assertAllRequestsCarrySession(
        claims: Map<String, String> = emptyMap(),
        includeUnattributed: Boolean = false,
        since: Double? = null,
    ): List<MoqRequestRecord> = assertQuery(sessionQuery(claims, includeUnattributed, since), 0).unattributed

    internal fun sessionQuery(
        claims: Map<String, String>,
        includeUnattributed: Boolean,
        since: Double?,
    ): MoqRequestQuery =
        MoqRequestQuery(
            since = since, claims = claims, times = 0, scope = MoqRequestQuery.Scope.SESSIONLESS,
            includeUnattributed = includeUnattributed.takeIf { it },
        )

    // MARK: Readiness

    /** Polls the admin API until the server answers or [timeoutMillis] elapses. */
    public fun waitUntilReady(timeoutMillis: Long = 10_000): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
        while (System.nanoTime() < deadline) {
            val remaining = ((deadline - System.nanoTime()) / NANOS_PER_MILLI).toInt()
            try {
                send("GET", "/_admin/endpoints", timeout = minOf(READY_PROBE_MILLIS, remaining.coerceAtLeast(1)))
                return true
            } catch (_: MoqControlException) {
                val left = (deadline - System.nanoTime()) / NANOS_PER_MILLI
                if (left > 0) Thread.sleep(minOf(READY_RETRY_MILLIS, left))
            }
        }
        return false
    }

    // MARK: Transport

    /** [subresource] is empty for the endpoint itself (its detail). */
    internal fun endpointPath(method: String, path: String, subresource: String, operation: String? = null): String {
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/") { encodeSegment(it) }
        val templated = if (segments.isEmpty()) "" else "/$segments"
        val suffix = if (subresource.isEmpty()) "" else "/$subresource"
        val query = operation?.let { "?operation=${encodeSegment(it)}" }.orEmpty()
        return "/_admin/endpoints/${method.uppercase()}$templated$suffix$query"
    }

    private fun encode(body: Map<String, String>): String =
        json.encodeToString(MapSerializer(String.serializer(), String.serializer()), body)

    private fun send(method: String, path: String, body: String? = null, timeout: Int = timeoutMillis): String {
        val connection =
            try {
                URI.create(baseUrl + path).toURL().openConnection() as HttpURLConnection
            } catch (e: IOException) {
                throw MoqControlException.Transport(e.message ?: e.toString())
            }
        try {
            connection.requestMethod = method
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            sessionId?.let { connection.setRequestProperty("X-Mock-Session", it) }
            when (auth) {
                is Auth.Bearer -> connection.setRequestProperty("Authorization", "Bearer ${auth.token}")
                is Auth.ApiKey -> connection.setRequestProperty(auth.header, auth.value)
                null -> Unit
            }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().decodeToString() }.orEmpty()
            if (status !in 200..299) throw MoqControlException.Rejected(status, text)
            return text
        } catch (_: SocketTimeoutException) {
            throw MoqControlException.TimedOut()
        } catch (e: IOException) {
            throw MoqControlException.Transport(e.message ?: e.toString())
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val READY_PROBE_MILLIS = 2_000
        const val READY_RETRY_MILLIS = 200L
        const val ASSERT_RETRY_MILLIS = 100L

        val json = Json { ignoreUnknownKeys = true }

        /** Percent-encodes one path segment, leaving RFC 3986 unreserved characters as-is. */
        fun encodeSegment(segment: String): String =
            buildString {
                for (byte in segment.toByteArray()) {
                    val char = byte.toInt().toChar()
                    if (char.isLetterOrDigit() && byte >= 0 || char in "-._~") {
                        append(char)
                    } else {
                        append('%').append("%02X".format(byte.toInt() and 0xFF))
                    }
                }
            }
    }
}

/** A failed admin call. */
public sealed class MoqControlException(message: String) : Exception(message) {
    public class TimedOut : MoqControlException("Request to moqserver timed out")

    public class Transport(public val reason: String) : MoqControlException(reason)

    public class Rejected(public val status: Int, public val body: String) :
        MoqControlException("moqserver rejected the request ($status): $body")
}

/** Thrown by [MoqClient.assertNoUnmatchedRequests]: the app called paths the bundle doesn't mock. */
public class MoqUnmatchedRequestsException(public val requests: List<MoqRequestRecord>) :
    AssertionError(
        "App made ${requests.size} request(s) with no matching mock endpoint:\n" +
            requests.joinToString("\n") { "  ${it.method} ${it.path}" },
    )
