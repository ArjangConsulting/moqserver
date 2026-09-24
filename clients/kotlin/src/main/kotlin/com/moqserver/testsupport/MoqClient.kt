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
 * Calls block the calling thread (never call from Android's main thread). One immutable client per
 * suite; [createSession] returns a client scoped to a new isolated session, whose id the app under
 * test must send as `X-Mock-Session` on its mock requests.
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
        send("PUT", "/_admin/scenario", mapOf("name" to name))
    }

    /** Clears runtime overrides and call counters. */
    public fun resetAll() {
        send("DELETE", "/_admin/state")
    }

    public fun selectVariant(variant: String, method: String, path: String) {
        send("PUT", endpointPath(method, path, "variant"), mapOf("variant" to variant))
    }

    public fun resetVariant(method: String, path: String) {
        send("DELETE", endpointPath(method, path, "variant"))
    }

    public fun resetCallCount(method: String, path: String) {
        send("DELETE", endpointPath(method, path, "call-count"))
    }

    public fun resetAll(method: String, path: String) {
        send("DELETE", endpointPath(method, path, "state"))
    }

    // MARK: Request history

    /** Request history, newest first (at most 500 rows), scoped to this client's session. */
    public fun requests(): List<MoqRequestRecord> =
        json.decodeFromString(ListSerializer(MoqRequestRecord.serializer()), send("GET", "/_admin/requests"))

    /** Requests that matched no endpoint, oldest first. */
    public fun unmatchedRequests(): List<MoqRequestRecord> = requests().filter { it.isUnmatched }.reversed()

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

    internal fun endpointPath(method: String, path: String, subresource: String): String {
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/") { encodeSegment(it) }
        val templated = if (segments.isEmpty()) "" else "/$segments"
        return "/_admin/endpoints/${method.uppercase()}$templated/$subresource"
    }

    private fun send(method: String, path: String, body: Map<String, String>? = null, timeout: Int = timeoutMillis): String {
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
                val payload = json.encodeToString(MapSerializer(String.serializer(), String.serializer()), body)
                connection.outputStream.use { it.write(payload.toByteArray()) }
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
