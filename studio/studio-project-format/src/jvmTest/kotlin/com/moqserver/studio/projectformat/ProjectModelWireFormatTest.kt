package com.moqserver.studio.projectformat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** The generated models must read and write the same JSON shapes Swift's `MoqCore` does. */
class ProjectModelWireFormatTest {
	private val json = Json { ignoreUnknownKeys = true }

	@Test
	fun `variant stream settings survive a round trip with integer fields`() {
		val wire = """{"name":"slow","status":200,"stream":{"chunk_bytes":5,"interval_ms":50,"disconnect_after_bytes":10}}"""

		val variant = json.decodeFromString<ProjectVariant>(wire)
		val reencoded = json.parseToJsonElement(json.encodeToString(variant)).jsonObject

		assertEquals(ResponseStream(chunkBytes = 5, intervalMs = 50, disconnectAfterBytes = 10), variant.stream)
		assertEquals(json.parseToJsonElement(wire).jsonObject["stream"], reencoded["stream"])
	}

	@Test
	fun `a stream with only chunk_bytes writes no other stream fields`() {
		val variant = ProjectVariant(name = "s", status = 200, stream = ResponseStream(chunkBytes = 3))

		val stream = json.parseToJsonElement(json.encodeToString(variant)).jsonObject["stream"]

		assertEquals(json.parseToJsonElement("""{"chunk_bytes":3}"""), stream)
	}
}
