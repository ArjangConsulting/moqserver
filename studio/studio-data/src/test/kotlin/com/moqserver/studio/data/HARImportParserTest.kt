package com.moqserver.studio.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HARImportParserTest {
    private val parser = HARImportParser()

    @Test
    fun `parses entries, skips malformed urls, and survives invalid base64 bodies`() {
        val har = """
            {
              "log": {
                "version": "1.2",
                "creator": { "name": "Browser", "version": "1.0" },
                "entries": [
                  {
                    "request": {
                      "method": "GET",
                      "url": "https://api.test/users",
                      "headers": [],
                      "queryString": []
                    },
                    "response": {
                      "status": 200,
                      "headers": [
                        { "name": "Content-Type", "value": "application/json" }
                      ],
                      "content": {
                        "mimeType": "application/json",
                        "text": "{\"users\":[{\"id\":1}]}"
                      }
                    }
                  },
                  {
                    "request": {
                      "method": "GET",
                      "url": "not a url",
                      "headers": [],
                      "queryString": []
                    },
                    "response": {
                      "status": 200,
                      "headers": [],
                      "content": {
                        "mimeType": "text/plain",
                        "text": "ignored"
                      }
                    }
                  },
                  {
                    "request": {
                      "method": "GET",
                      "url": "https://api.test/users",
                      "headers": [],
                      "queryString": []
                    },
                    "response": {
                      "status": 500,
                      "headers": [
                        { "name": "Content-Type", "value": "text/plain" }
                      ],
                      "content": {
                        "mimeType": "text/plain",
                        "encoding": "base64",
                        "text": "@@@"
                      }
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val spec = parser.parse(har)

        assertEquals("Browser HAR Import", spec.title)
        assertEquals("1.0", spec.version)
        assertEquals(1, spec.endpoints.size)

        val endpoint = spec.endpoints.single()
        assertEquals("GET", endpoint.method)
        assertEquals("/users", endpoint.path)
        assertEquals(2, endpoint.responses.size)

        val jsonResponse = endpoint.responses.first { it.statusCode == 200 }
        assertNotNull(jsonResponse.body)
        assertTrue(jsonResponse.body!!.contains("\"users\""))

        val invalidBase64Response = endpoint.responses.first { it.statusCode == 500 }
        assertEquals("@@@", invalidBase64Response.body)
    }
}
