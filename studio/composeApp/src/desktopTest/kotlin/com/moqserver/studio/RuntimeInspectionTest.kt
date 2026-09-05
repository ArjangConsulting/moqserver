package com.moqserver.studio

import com.moqserver.studio.projectformat.format.FormatServiceException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeInspectionTest {
	@Test
	fun `request history retains the selection explanation`() {
		val value = Json.parseToJsonElement(
			"""{"id":"1","method":"GET","path":"/users","status":500,"variant":"error","reason":"runtime override","callNumber":2}""",
		).jsonObject
		val request = decodeRuntimeRequest(value)
		assertEquals("runtime override", request.reason)
		assertEquals("error", request.variant)
		assertEquals(2, request.callNumber)
	}

	@Test
	fun `disk conflict recovery preserves local edits through save as`() {
		val message = requireNotNull(recoveryMessage(FormatServiceException("E_PROJECT_CHANGED", "conflict")))
		assertTrue(message.contains("Save As"))
		assertTrue(message.contains("Reload Project"))
	}
}
