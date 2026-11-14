package com.moqserver.studio

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Launches a user-initiated operation whose failure is recoverable: the error is logged, written to
 * the crash log and shown through [onUserMessage] (cancellation still propagates). Anything else
 * escaping [block] reaches [handler]. This is the one place the shell catches broad exceptions for
 * menu and dialog actions.
 */
internal fun CoroutineScope.launchReporting(
	handler: CoroutineExceptionHandler,
	failureContext: String,
	onUserMessage: (String) -> Unit,
	block: suspend CoroutineScope.() -> Unit,
): Job = launch(handler) {
	runReporting(failureContext, onUserMessage) { block() }
}

/** [launchReporting] for code already running in a coroutine. */
internal suspend fun runReporting(
	failureContext: String,
	onUserMessage: (String) -> Unit,
	block: suspend () -> Unit,
) {
	try {
		block()
	} catch (e: Exception) {
		reportRecoverable(context = failureContext, throwable = e, onUserMessage = onUserMessage)
	}
}

/** Runs [block] on [ioDispatcher], which callers inject rather than naming `Dispatchers.IO`. */
internal suspend fun <T> runOnIo(
	ioDispatcher: CoroutineDispatcher,
	block: suspend () -> T,
): T = withContext(ioDispatcher) { block() }
