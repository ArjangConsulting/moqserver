package com.moqserver.studio.logging

import org.slf4j.Logger

/**
 * Runs [block], logs elapsed time at INFO, and re-throws on failure.
 *
 * Use for synchronous (blocking) operations where caller already holds an appropriate dispatcher.
 * For suspend functions, call [timedSuspend].
 */
inline fun <T> Logger.timed(operation: String, block: () -> T): T {
    val start = System.currentTimeMillis()
    return try {
        block().also { info("{} completed in {}ms", operation, System.currentTimeMillis() - start) }
    } catch (e: Exception) {
        error("{} failed after {}ms: {}", operation, System.currentTimeMillis() - start, e.message)
        throw e
    }
}

/**
 * Suspend-safe variant of [timed] for use with `suspend` lambdas.
 */
suspend inline fun <T> Logger.timedSuspend(operation: String, crossinline block: suspend () -> T): T {
    val start = System.currentTimeMillis()
    return try {
        block().also { info("{} completed in {}ms", operation, System.currentTimeMillis() - start) }
    } catch (e: Exception) {
        error("{} failed after {}ms: {}", operation, System.currentTimeMillis() - start, e.message)
        throw e
    }
}
