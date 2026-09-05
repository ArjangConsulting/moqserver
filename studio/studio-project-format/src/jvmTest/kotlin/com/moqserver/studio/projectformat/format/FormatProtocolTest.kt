package com.moqserver.studio.projectformat.format

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FormatProtocolTest {
	@Test
	fun `incompatible version is rejected before opening a session`() {
		val info = Json.parseToJsonElement(
			"""{"protocolVersion":2,"capabilities":["project-revision","session-recovery"]}""",
		).jsonObject
		val error = assertFailsWith<FormatServiceException> { requireFormatCompatibility(info) }
		assertEquals("E_FORMAT_INCOMPATIBLE", error.code)
	}

	@Test
	fun `missing revision capability is rejected`() {
		val info = Json.parseToJsonElement("""{"protocolVersion":1,"capabilities":["session-recovery"]}""").jsonObject
		assertFailsWith<FormatServiceException> { requireFormatCompatibility(info) }
	}
}
