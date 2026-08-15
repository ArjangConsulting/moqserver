package com.moqserver.studio.projectformat.format

import com.moqserver.studio.logging.loggerFor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** State of the supervised `moq-format` child process, for Studio's UI to reflect honestly. */
sealed interface FormatServiceState {
    data object Starting : FormatServiceState
    data object Ready : FormatServiceState
    data class Unavailable(val reason: String) : FormatServiceState
}

/**
 * A `moq-format` call failed. `code` is `MoqServiceError`'s stable string
 * (`server/Sources/MoqService/MoqServiceError.swift`) — branch on it, not on `message`, which is
 * meant for a human.
 */
class FormatServiceException(val code: String, message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Supervises one `moq-format` subprocess: spawns it, frames requests/responses over its stdio
 * (`ContentLengthFraming`, matching `MoqFormatServiceRun`'s Swift side), correlates responses to
 * calls by JSON-RPC id, and restarts the process with backoff if it exits unexpectedly.
 *
 * A crash in Swift's code is process-fatal — see `MoqServiceError` — so this is deliberately a
 * subprocess rather than an in-process call: the failure mode here is "a `FormatServiceException`
 * on the in-flight calls, `Unavailable` until respawned," never "Studio itself goes down."
 */
class FormatProcess(
    private val locateBinary: () -> String,
    /**
     * Sets `MOQ_FORMAT_ALLOW_NETWORK=1` on the spawned process, enabling `import.parseOpenapi`/
     * `import.openapi` to fetch an `http(s)://` source. Off by default, matching `moq-mcp`'s
     * default-closed posture for the same setting — but Studio's own "Import from URL" is a
     * first-party desktop feature that worked unconditionally before this cutover (its own
     * `OpenAPIURLFetcher` had no such gate), so Studio's composition root passes `true` here
     * rather than requiring end users to set an environment variable to use it.
     */
    private val allowNetworkImport: Boolean = false,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val callTimeoutMs: Long = 30_000,
    private val maxRestartAttempts: Int = 5,
) {
    private val logger = loggerFor<FormatProcess>()
    private val json = Json { ignoreUnknownKeys = true }
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val writeLock = Mutex()

    private val _state = MutableStateFlow<FormatServiceState>(FormatServiceState.Starting)
    val state: StateFlow<FormatServiceState> = _state.asStateFlow()

    @Volatile private var process: Process? = null

    @Volatile private var stopped = false
    private var restartAttempts = 0

    fun start() {
        stopped = false
        scope.launch { supervise() }
    }

    fun stop() {
        stopped = true
        process?.destroy()
        failAllPending("moq-format process stopped")
    }

    /**
     * Calls `method` with `params` and returns its JSON-RPC `result`, or throws. Waits for the
     * process to reach a settled state first (`Ready` or a terminal `Unavailable`) rather than
     * failing immediately if called right after [start] — `start` returns before the child
     * process has actually spawned.
     */
    suspend fun call(method: String, params: JsonElement): JsonElement {
        val current = awaitReadyProcess()
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred

        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        writeRequest(current, id, request)

        return try {
            withTimeout(callTimeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    /** Waits for the process to reach a settled state, then returns it — or throws if that
     * settled state is "not running". */
    // detekt false positive: withTimeout/state.first below are genuine suspension points;
    // dropping `suspend` doesn't compile.
    @Suppress("RedundantSuspendModifier")
    private suspend fun awaitReadyProcess(): Process {
        val settled = withTimeout(callTimeoutMs) {
            state.first { it is FormatServiceState.Ready || it is FormatServiceState.Unavailable }
        }
        if (settled is FormatServiceState.Unavailable) {
            throw FormatServiceException("E_FORMAT_UNAVAILABLE", settled.reason)
        }
        return process ?: throw FormatServiceException("E_FORMAT_UNAVAILABLE", "moq-format is not running")
    }

    // detekt false positive: writeLock.withLock below is a genuine suspension point; dropping
    // `suspend` doesn't compile.
    @Suppress("RedundantSuspendModifier")
    private suspend fun writeRequest(current: Process, id: Long, request: JsonObject) {
        try {
            writeLock.withLock {
                val stdin = current.outputStream
                ContentLengthFraming.writeMessage(
                    json.encodeToString(JsonObject.serializer(), request).toByteArray(),
                    stdin,
                )
            }
        } catch (e: Exception) {
            pending.remove(id)
            throw FormatServiceException(
                "E_FORMAT_UNAVAILABLE",
                "Failed to write to moq-format: ${e.message}",
                cause = e,
            )
        }
    }

    // MARK: - Supervision

    /** Outcome of one attempt to spawn the child process, for [supervise] to act on. */
    private sealed interface SpawnOutcome {
        data class Started(val process: Process) : SpawnOutcome
        data object Retry : SpawnOutcome
        data object GiveUp : SpawnOutcome
    }

    private suspend fun spawnProcess(): SpawnOutcome {
        val binaryPath = try {
            locateBinary()
        } catch (e: Exception) {
            _state.value = FormatServiceState.Unavailable("Could not locate moq-format: ${e.message}")
            return SpawnOutcome.GiveUp
        }
        return try {
            val proc = ProcessBuilder(binaryPath)
                .redirectErrorStream(false)
                .apply { if (allowNetworkImport) environment()["MOQ_FORMAT_ALLOW_NETWORK"] = "1" }
                .start()
            SpawnOutcome.Started(proc)
        } catch (e: Exception) {
            onProcessGone("Failed to start moq-format: ${e.message}")
            if (scheduleRestart()) SpawnOutcome.Retry else SpawnOutcome.GiveUp
        }
    }

    private suspend fun supervise() {
        while (!stopped) {
            _state.value = FormatServiceState.Starting
            val proc = when (val outcome = spawnProcess()) {
                is SpawnOutcome.Started -> outcome.process
                SpawnOutcome.Retry -> continue
                SpawnOutcome.GiveUp -> return
            }

            process = proc
            restartAttempts = 0
            _state.value = FormatServiceState.Ready
            logger.info("moq-format started (pid={})", proc.pid())

            readLoop(BufferedInputStream(proc.inputStream))

            // readLoop returned: the process's stdout closed (crash, or a clean `stop()`).
            process = null
            if (stopped) return
            onProcessGone("moq-format exited unexpectedly")
            if (!scheduleRestart()) return
        }
    }

    private fun readNextMessage(input: BufferedInputStream): ByteArray? = try {
        ContentLengthFraming.readMessage(input)
    } catch (e: Exception) {
        logger.warn("moq-format framing error: {}", e.message)
        null
    }

    private fun parseResponse(messageBytes: ByteArray): JsonObject? = try {
        json.parseToJsonElement(String(messageBytes)).jsonObject
    } catch (e: Exception) {
        logger.warn("moq-format sent malformed JSON-RPC: {}", e.message)
        null
    }

    private fun completePending(response: JsonObject) {
        val id = (response["id"] as? JsonPrimitive)?.long ?: return
        val deferred = pending[id] ?: return

        val error = response["error"]?.jsonObject
        if (error != null) {
            val code = error["data"]?.jsonObject?.get("code")?.jsonPrimitive?.content ?: "E_INTERNAL"
            val message = error["message"]?.jsonPrimitive?.content ?: "moq-format error"
            deferred.completeExceptionally(FormatServiceException(code, message))
        } else {
            deferred.complete(response["result"] ?: JsonNull)
        }
    }

    private fun readLoop(input: BufferedInputStream) {
        while (true) {
            val messageBytes = readNextMessage(input) ?: break
            parseResponse(messageBytes)?.let { completePending(it) }
        }
    }

    private fun onProcessGone(reason: String) {
        _state.value = FormatServiceState.Unavailable(reason)
        failAllPending(reason)
    }

    private fun failAllPending(reason: String) {
        val ids = pending.keys.toList()
        for (id in ids) {
            pending.remove(id)?.completeExceptionally(FormatServiceException("E_FORMAT_UNAVAILABLE", reason))
        }
    }

    /** Exponential backoff, capped, up to `maxRestartAttempts`. Returns false when exhausted. */
    // detekt false positive: delay() below is a genuine suspension point; dropping `suspend`
    // doesn't compile.
    @Suppress("RedundantSuspendModifier")
    private suspend fun scheduleRestart(): Boolean {
        restartAttempts += 1
        if (restartAttempts > maxRestartAttempts) {
            _state.value = FormatServiceState.Unavailable(
                "moq-format crashed $maxRestartAttempts times; giving up. Restart Studio to retry.",
            )
            return false
        }
        val backoffMs = minOf(500L * (1L shl (restartAttempts - 1)), 10_000L)
        logger.warn("Restarting moq-format in {}ms (attempt {}/{})", backoffMs, restartAttempts, maxRestartAttempts)
        delay(backoffMs)
        return true
    }
}
