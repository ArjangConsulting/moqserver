package com.moqserver.studio.imports

import kotlinx.coroutines.CancellationException

internal fun Throwable.rethrowIfCancellation() {
	if (this is CancellationException) throw this
}
