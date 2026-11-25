package com.moqserver.testsupport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The [MoqClient] calls as `suspend` functions, each run on [Dispatchers.IO] so a coroutine test (or
 * Android's main dispatcher) never blocks. Obtain one with [MoqClient.suspending]. A cancelled
 * coroutine stops waiting immediately; the underlying request still ends within the client's timeout.
 */
public class SuspendingMoqClient(public val client: MoqClient) {
    private suspend fun <T> io(block: MoqClient.() -> T): T = withContext(Dispatchers.IO) { client.block() }

    public suspend fun createSession(): MoqClient = io { createSession() }

    public suspend fun closeSession(): Unit = io { closeSession() }

    public suspend fun sessions(): List<MoqSession> = io { sessions() }

    public suspend fun activateScenario(name: String): Unit = io { activateScenario(name) }

    public suspend fun scenarios(): List<MoqScenario> = io { scenarios() }

    public suspend fun defineScenario(name: String, overrides: Map<String, String>): Unit =
        io { defineScenario(name, overrides) }

    public suspend fun removeScenario(name: String): Unit = io { removeScenario(name) }

    public suspend fun resetAll(): Unit = io { resetAll() }

    public suspend fun selectVariant(variant: String, method: String, path: String, operation: String? = null): Unit =
        io { selectVariant(variant, method, path, operation) }

    public suspend fun resetVariant(method: String, path: String, operation: String? = null): Unit =
        io { resetVariant(method, path, operation) }

    public suspend fun resetCallCount(method: String, path: String, operation: String? = null): Unit =
        io { resetCallCount(method, path, operation) }

    public suspend fun resetAll(method: String, path: String, operation: String? = null): Unit =
        io { resetAll(method, path, operation) }

    public suspend fun endpoints(): List<MoqEndpoint> = io { endpoints() }

    public suspend fun callCount(method: String, path: String, operation: String? = null): Int =
        io { callCount(method, path, operation) }

    public suspend fun reload(): MoqReloadSummary = io { reload() }

    public suspend fun requests(): List<MoqRequestRecord> = io { requests() }

    public suspend fun unmatchedRequests(): List<MoqRequestRecord> = io { unmatchedRequests() }

    public suspend fun clearRequests(): Unit = io { clearRequests() }

    public suspend fun assertNoUnmatchedRequests(): Unit = io { assertNoUnmatchedRequests() }

    public suspend fun query(query: MoqRequestQuery): MoqRequestQueryResult = io { query(query) }

    public suspend fun mark(): Double = io { mark() }

    public suspend fun serverInfo(): MoqServerInfo = io { serverInfo() }

    public suspend fun requireCapabilities(vararg capabilities: String): Unit =
        io { requireCapabilities(*capabilities) }

    public suspend fun selectVariant(
        variant: String,
        method: String,
        path: String,
        times: Int,
        operation: String? = null,
    ): Unit = io { selectVariant(variant, method, path, times, operation) }

    /** [MoqClient.assertRequest] without blocking; polls with [delay] while waiting for calls. */
    public suspend fun assertRequest(
        endpoint: String? = null,
        times: Int? = null,
        headers: List<MoqHeaderExpectation> = emptyList(),
        claims: Map<String, String> = emptyMap(),
        since: Double? = null,
        timeoutMillis: Long = 0,
        query: List<MoqQueryExpectation> = emptyList(),
    ): List<MoqRequestRecord> {
        val request = MoqRequestQuery(
            endpoint = endpoint, since = since, headers = headers, claims = claims, times = times, query = query,
        )
        return assertQuery(request, timeoutMillis).matched
    }

    public suspend fun rejectedRequests(since: Double? = null): List<MoqRequestRecord> = io { rejectedRequests(since) }

    public suspend fun assertNoRejectedRequests(since: Double? = null): Unit = io { assertNoRejectedRequests(since) }

    public suspend fun assertAllRequestsCarrySession(
        claims: Map<String, String> = emptyMap(),
        includeUnattributed: Boolean = false,
        since: Double? = null,
    ): List<MoqRequestRecord> = io { assertAllRequestsCarrySession(claims, includeUnattributed, since) }

    private suspend fun assertQuery(query: MoqRequestQuery, timeoutMillis: Long): MoqRequestQueryResult {
        val deadline = System.nanoTime() + timeoutMillis.coerceAtLeast(0) * NANOS_PER_MILLI
        while (true) {
            val result = io { query(query) }
            if (result.ok) return result
            val remaining = (deadline - System.nanoTime()) / NANOS_PER_MILLI
            if (remaining <= 0) throw MoqRequestAssertionException(result)
            delay(minOf(ASSERT_RETRY_MILLIS, remaining))
        }
    }

    /** Polls the admin API until the server answers or [timeoutMillis] elapses, without blocking. */
    public suspend fun waitUntilReady(timeoutMillis: Long = 10_000): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
        while (System.nanoTime() < deadline) {
            val remaining = (deadline - System.nanoTime()) / NANOS_PER_MILLI
            if (io { waitUntilReady(minOf(remaining, READY_PROBE_MILLIS)) }) return true
            delay(minOf(READY_RETRY_MILLIS, ((deadline - System.nanoTime()) / NANOS_PER_MILLI).coerceAtLeast(0)))
        }
        return false
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val READY_PROBE_MILLIS = 2_000L
        const val READY_RETRY_MILLIS = 200L
        const val ASSERT_RETRY_MILLIS = 100L
    }
}
