package com.moqserver.studio

import com.moqserver.studio.endpointdetail.formatJsonBodyText
import com.moqserver.studio.endpointdetail.parseJsonBodyText
import com.moqserver.studio.endpointdetail.validateJsonBodyText
import com.moqserver.studio.projectformat.YamlValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BodyEditorSupportTest {

    @Test
    fun `validateJsonBodyText accepts valid JSON`() {
        val error = validateJsonBodyText("""{"ok":true,"count":2}""")
        val parsed = parseJsonBodyText("""{"ok":true,"count":2}""").getOrThrow()

        assertNull(error)
        assertIs<YamlValue.Obj>(parsed)
        assertEquals(YamlValue.Bool(true), parsed.value.getValue("ok"))
        assertEquals(YamlValue.Int(2), parsed.value.getValue("count"))
    }

    @Test
    fun `validateJsonBodyText reports parser errors`() {
        val error = validateJsonBodyText("""{"ok": true,,}""")

        assertNotNull(error)
        assertTrue(error.contains(",,"))
    }

    @Test
    fun `formatJsonBodyText pretty prints valid JSON`() {
        val formatted = formatJsonBodyText("""{"ok":true,"nested":{"count":2}}""").getOrThrow()

        assertEquals(
            """
            {
              "ok": true,
              "nested": {
                "count": 2
              }
            }
            """.trimIndent(),
            formatted,
        )
    }

    @Test
    fun `formatJsonBodyText returns failure for invalid JSON`() {
        val result = formatJsonBodyText("""{"ok":true,,}""")

        assertTrue(result.isFailure)
    }
}
