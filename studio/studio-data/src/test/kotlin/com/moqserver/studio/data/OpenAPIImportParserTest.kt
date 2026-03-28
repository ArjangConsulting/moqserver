package com.moqserver.studio.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenAPIImportParserTest {
    private val parser = OpenAPIImportParser()

    @Test
    fun `parses recursive schemas without stack overflow`() {
        val spec = """
            openapi: 3.0.3
            info:
              title: Recursive API
              version: 1.0.0
            paths:
              /nodes:
                get:
                  responses:
                    "200":
                      description: OK
                      content:
                        application/json:
                          schema:
                            ${'$'}ref: '#/components/schemas/Node'
            components:
              schemas:
                Node:
                  type: object
                  properties:
                    name:
                      type: string
                    children:
                      type: array
                      items:
                        ${'$'}ref: '#/components/schemas/Node'
        """.trimIndent()

        val parsed = parser.parse(spec)

        assertEquals("Recursive API", parsed.title)
        assertEquals("1.0.0", parsed.version)
        assertEquals(1, parsed.endpoints.size)

        val endpoint = parsed.endpoints.single()
        assertEquals("GET", endpoint.method)
        assertEquals("/nodes", endpoint.path)

        val body = endpoint.responses.firstOrNull()?.body
        assertNotNull(body)
        assertTrue(body.contains("\"children\""))
    }
}
