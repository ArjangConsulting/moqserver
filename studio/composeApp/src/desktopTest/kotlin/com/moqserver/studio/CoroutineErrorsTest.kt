package com.moqserver.studio

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CoroutineErrorsTest {

	@Test
	fun `recoverable error reporting preserves coroutine cancellation`() {
		assertFailsWith<CancellationException> {
			reportRecoverable(
				context = "cancelled operation",
				throwable = CancellationException("cancelled"),
				onUserMessage = { error("Cancellation must not be shown as a recoverable error") },
			)
		}
	}
}
