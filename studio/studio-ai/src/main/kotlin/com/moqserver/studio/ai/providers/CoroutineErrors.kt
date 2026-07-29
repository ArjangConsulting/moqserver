package com.moqserver.studio.ai.providers

import kotlinx.coroutines.CancellationException

internal fun Throwable.rethrowIfCancellation() {
	if (this is CancellationException) throw this
}
