package com.moqserver.studio

import com.moqserver.studio.endpointdetail.copyTextToClipboard
import com.moqserver.studio.endpointdetail.readTextFromClipboard
import kotlin.test.Test
import kotlin.test.assertEquals

class ClipboardSupportTest {

	@Test
	fun `copyTextToClipboard writes plain text to system clipboard`() {
		val expected = "{\"ok\":true}"

		copyTextToClipboard(expected)

		assertEquals(expected, readTextFromClipboard())
	}
}
