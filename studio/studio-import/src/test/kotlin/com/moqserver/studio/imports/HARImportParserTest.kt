package com.moqserver.studio.imports

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
		val spec = parser.parse(sampleHarWithInvalidUrlAndBase64())
		val endpoint = assertUsersEndpoint(spec)
		val jsonResponse = endpoint.responses.first { it.statusCode == 200 }
		assertNotNull(jsonResponse.body)
		assertTrue(jsonResponse.body!!.contains("\"users\""))
		assertEquals("@@@", endpoint.responses.first { it.statusCode == 500 }.body)
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
		// Cookie values are always redacted to prevent session token leakage
		assertEquals("[redacted]", users.cookies.single().match)

		val profile = spec.endpoints.first { it.path == "/profile" }
		assertEquals(2, profile.cookies.size)
		assertEquals("[redacted]", profile.cookies.first { it.name == "theme" }.match)
		assertEquals("[redacted]", profile.cookies.first { it.name == "locale" }.match)
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
	fun `preserves duplicate exchanges with same status and body as separate responses`() {
		val har = """
            {
              "log": {
                "version": "1.2",
                "entries": [
                  {
                    "request": {
                      "method": "GET",
                      "url": "https://api.test/items",
                      "headers": []
                    },
                    "response": {
                      "status": 200,
                      "headers": [
                        { "name": "Content-Type", "value": "application/json" }
                      ],
                      "content": { "mimeType": "application/json", "text": "{\"ok\":true}" }
                    }
                  },
                  {
                    "request": {
                      "method": "GET",
                      "url": "https://api.test/items",
                      "headers": []
                    },
                    "response": {
                      "status": 200,
                      "headers": [
                        { "name": "Content-Type", "value": "application/json" }
                      ],
                      "content": { "mimeType": "application/json", "text": "{\"ok\":true}" }
                    }
                  }
                ]
              }
            }
        """.trimIndent()

		val spec = parser.parse(har)

		val endpoint = spec.endpoints.single()
		assertEquals(2, endpoint.responses.size)
		assertEquals(listOf("default", "success-200"), endpoint.responses.map { it.name })
		assertEquals(listOf(200, 200), endpoint.responses.map { it.statusCode })
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

	@Test
	fun `redacts authorization and cookie request headers`() {
		val har = """
            {
              "log": {
                "version": "1.2",
                "entries": [
                  {
                    "request": {
                      "method": "GET",
                      "url": "https://api.test/secure",
                      "headers": [
                        { "name": "Authorization", "value": "Bearer super-secret-token" },
                        { "name": "X-API-Key", "value": "key-abc-123" },
                        { "name": "X-CSRF-Token", "value": "csrf-xyz" },
                        { "name": "Accept", "value": "application/json" }
                      ]
                    },
                    "response": {
                      "status": 200,
                      "headers": [
                        { "name": "Set-Cookie", "value": "session=s3cr3t; HttpOnly" },
                        { "name": "Content-Type", "value": "application/json" }
                      ],
                      "content": { "mimeType": "application/json", "text": "{\"ok\":true}" }
                    }
                  }
                ]
              }
            }
        """.trimIndent()

		val spec = parser.parse(har)
		val response = spec.endpoints.single().responses.single()

		// Sensitive response headers must be redacted
		assertEquals("[redacted]", response.headers["Set-Cookie"])
		// Non-sensitive headers must pass through unchanged
		assertEquals("application/json", response.headers["Content-Type"])
	}

	@Test
	fun `redacts sensitive query parameters`() {
		val har = """
            {
              "log": {
                "version": "1.2",
                "entries": [
                  {
                    "request": {
                      "method": "GET",
                      "url": "https://api.test/callback?code=auth-code-xyz&state=csrf-state&redirect_uri=/home",
                      "headers": [],
                      "queryString": [
                        { "name": "code", "value": "auth-code-xyz" },
                        { "name": "state", "value": "csrf-state" },
                        { "name": "redirect_uri", "value": "/home" }
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
		val endpoint = spec.endpoints.single()

		// Sensitive OAuth params must be redacted
		val codeRule = endpoint.queryParameters.first { it.name == "code" }
		assertEquals("[redacted]", codeRule.match)
		val stateRule = endpoint.queryParameters.first { it.name == "state" }
		assertEquals("[redacted]", stateRule.match)
		// Non-sensitive param must pass through
		val redirectRule = endpoint.queryParameters.first { it.name == "redirect_uri" }
		assertEquals("/home", redirectRule.match)
	}

	@Test
	fun `redacts jwt signature in response header values while preserving header and payload`() {
		val jwtHeader = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9"
		val jwtPayload = "eyJzdWIiOiJ1c2VyMTIzIiwiZXhwIjoxNjAwMDAwMDAwfQ"
		val jwtSignature = "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
		val fullJwt = "$jwtHeader.$jwtPayload.$jwtSignature"

		val har = """
            {
              "log": {
                "version": "1.2",
                "entries": [
                  {
                    "request": {
                      "method": "GET",
                      "url": "https://api.test/resource",
                      "headers": []
                    },
                    "response": {
                      "status": 200,
                      "headers": [
                        { "name": "X-Correlation-Token", "value": "$fullJwt" }
                      ],
                      "content": { "mimeType": "application/json", "text": "{}" }
                    }
                  }
                ]
              }
            }
        """.trimIndent()

		val spec = parser.parse(har)
		val response = spec.endpoints.single().responses.single()

		// JWT header and payload are preserved, only signature is stripped
		val customToken = response.headers["X-Correlation-Token"]
		assertEquals("$jwtHeader.$jwtPayload.redacted", customToken)
	}

	private fun harDocument(vararg entries: String, creator: String = "", version: String = "1.2"): String {
		val metadataLines = buildList {
			add("\"version\": \"$version\"")
			creator.takeIf { it.isNotBlank() }?.let { add("\"creator\": $it") }
		}.joinToString(",\n    ")
		return """
			{
			  "log": {
			    $metadataLines,
			    "entries": [
			      ${entries.joinToString(",\n      ")}
			    ]
			  }
			}
		""".trimIndent()
	}

	private fun entry(request: String, response: String): String = """
		{
		  "request": {
		    $request
		  },
		  "response": {
		    $response
		  }
		}
	""".trimIndent()

	private fun sampleHarWithInvalidUrlAndBase64(): String = harDocument(
		entry(
			request = """
				"method": "GET",
				"url": "https://api.test/users",
				"headers": [],
				"queryString": []
			""".trimIndent(),
			response = """
				"status": 200,
				"headers": [
				  { "name": "Content-Type", "value": "application/json" }
				],
				"content": {
				  "mimeType": "application/json",
				  "text": "{\"users\":[{\"id\":1}]}"
				}
			""".trimIndent(),
		),
		entry(
			request = """
				"method": "GET",
				"url": "not a url",
				"headers": [],
				"queryString": []
			""".trimIndent(),
			response = """
				"status": 200,
				"headers": [],
				"content": {
				  "mimeType": "text/plain",
				  "text": "ignored"
				}
			""".trimIndent(),
		),
		entry(
			request = """
				"method": "GET",
				"url": "https://api.test/users",
				"headers": [],
				"queryString": []
			""".trimIndent(),
			response = """
				"status": 500,
				"headers": [
				  { "name": "Content-Type", "value": "text/plain" }
				],
				"content": {
				  "mimeType": "text/plain",
				  "encoding": "base64",
				  "text": "@@@"
				}
			""".trimIndent(),
		),
		creator = "{ \"name\": \"Browser\", \"version\": \"1.0\" }",
	)

	private fun assertUsersEndpoint(spec: com.moqserver.studio.domain.ParsedSpec): com.moqserver.studio.domain.ParsedEndpoint {
		assertEquals("Browser HAR Import", spec.title)
		assertEquals("1.0", spec.version)
		assertEquals(1, spec.endpoints.size)
		return spec.endpoints.single().also { endpoint ->
			assertEquals("GET", endpoint.method)
			assertEquals("/users", endpoint.path)
			assertEquals("List Users", endpoint.alias)
			assertEquals(null, endpoint.description)
			assertEquals(null, endpoint.referenceName)
			assertEquals(2, endpoint.responses.size)
		}
	}
}
