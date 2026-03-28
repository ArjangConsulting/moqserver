package com.moqserver.studio.data

import com.moqserver.studio.projectformat.MatchType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        assertEquals("List Users", endpoint.alias)
        assertEquals(null, endpoint.description)
        assertEquals(null, endpoint.referenceName)
        assertEquals(2, endpoint.responses.size)

        val jsonResponse = endpoint.responses.first { it.statusCode == 200 }
        assertNotNull(jsonResponse.body)
        assertTrue(jsonResponse.body!!.contains("\"users\""))

        val invalidBase64Response = endpoint.responses.first { it.statusCode == 500 }
        assertEquals("@@@", invalidBase64Response.body)
    }

    @Test
    fun `parses request cookies from har cookies and cookie header`() {
        val har = """
            {
              "log": {
                "version": "1.2",
                "entries": [
                  {
                    "request": {
                      "method": "GET",
                      "url": "https://api.test/users",
                      "headers": [],
                      "cookies": [
                        { "name": "session_id", "value": "abc123" }
                      ]
                    },
                    "response": {
                      "status": 200,
                      "headers": [],
                      "content": { "mimeType": "application/json", "text": "{}" }
                    }
                  },
                  {
                    "request": {
                      "method": "GET",
                      "url": "https://api.test/profile",
                      "headers": [
                        { "name": "Cookie", "value": "theme=dark; locale=en-US" }
                      ]
                    },
                    "response": {
                      "status": 200,
                      "headers": [],
                      "content": { "mimeType": "application/json", "text": "{}" }
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val spec = parser.parse(har)

        val users = spec.endpoints.first { it.path == "/users" }
        assertEquals(1, users.cookies.size)
        assertEquals("session_id", users.cookies.single().name)
        assertEquals("abc123", users.cookies.single().match)

        val profile = spec.endpoints.first { it.path == "/profile" }
        assertEquals(2, profile.cookies.size)
        assertEquals("dark", profile.cookies.first { it.name == "theme" }.match)
        assertEquals("en-US", profile.cookies.first { it.name == "locale" }.match)
        assertEquals(MatchType.EQUAL_TO, profile.cookies.first { it.name == "theme" }.matchType)
    }

    @Test
    fun `parses request query params from har queryString and url`() {
        val har = """
            {
              "log": {
                "version": "1.2",
                "entries": [
                  {
                    "request": {
                      "method": "GET",
                      "url": "https://api.test/search?q=laptop&sort=popular",
                      "headers": [],
                      "queryString": [
                        { "name": "q", "value": "laptop" },
                        { "name": "sort", "value": "popular" }
                      ]
                    },
                    "response": {
                      "status": 200,
                      "headers": [],
                      "content": { "mimeType": "application/json", "text": "{}" }
                    }
                  },
                  {
                    "request": {
                      "method": "GET",
                      "url": "https://api.test/search?q=phone&page=2",
                      "headers": []
                    },
                    "response": {
                      "status": 200,
                      "headers": [],
                      "content": { "mimeType": "application/json", "text": "{}" }
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val spec = parser.parse(har)

        val endpoint = spec.endpoints.single()
        assertEquals(3, endpoint.queryParameters.size)
        assertEquals("popular", endpoint.queryParameters.first { it.name == "sort" }.match)
        assertEquals("2", endpoint.queryParameters.first { it.name == "page" }.match)
        assertEquals(null, endpoint.queryParameters.first { it.name == "q" }.match)
        assertEquals(true, endpoint.queryParameters.first { it.name == "q" }.required)
        assertEquals(MatchType.EQUAL_TO, endpoint.queryParameters.first { it.name == "sort" }.matchType)
    }

    @Test
    fun `skips malformed har entries and surfaces warnings instead of failing import`() {
        val har = """
            {
              "log": {
                "version": "1.2",
                "creator": { "name": "Proxy" },
                "entries": [
                  {
                    "request": {
                      "method": null,
                      "url": "https://api.test/ignored",
                      "headers": []
                    },
                    "response": {
                      "status": 200,
                      "headers": [],
                      "content": { "mimeType": "application/json", "text": "{}" }
                    }
                  },
                  {
                    "request": {
                      "method": "GET",
                      "url": "https://api.test/users",
                      "headers": []
                    },
                    "response": {
                      "status": 200,
                      "headers": [],
                      "content": { "mimeType": "application/json", "text": "{}" }
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val spec = parser.parse(har)

        assertEquals(1, spec.endpoints.size)
        assertEquals(1, spec.warnings.size)
        assertEquals("Skipped HAR entry 1: missing request method.", spec.warnings.single())
        assertEquals("/users", spec.endpoints.single().path)
    }

    @Test
    fun `reports useful error when har contains no importable entries`() {
        val har = """
            {
              "log": {
                "version": "1.2",
                "entries": [
                  {
                    "request": {
                      "method": null,
                      "url": "https://api.test/ignored",
                      "headers": []
                    },
                    "response": {
                      "status": 200,
                      "headers": [],
                      "content": { "mimeType": "application/json", "text": "{}" }
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val error = assertFailsWith<IllegalArgumentException> {
            parser.parse(har)
        }

        assertEquals(
            "HAR file does not contain any importable HTTP entries. Skipped HAR entry 1: missing request method.",
            error.message,
        )
    }
}
