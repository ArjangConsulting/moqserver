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
    }
}
