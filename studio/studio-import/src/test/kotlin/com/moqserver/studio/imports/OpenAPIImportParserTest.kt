package com.moqserver.studio.imports

import com.moqserver.studio.projectformat.MatchType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenAPIImportParserTest {
	private val parser = OpenAPIImportParser()

	@Test
	fun `parses Swagger 2 specs via conversion fallback`() {
		val spec = """
			{
			  "swagger": "2.0",
			  "info": {
			    "title": "Legacy API",
			    "version": "v1"
			  },
			  "host": "example.com",
			  "basePath": "/api",
			  "schemes": ["https"],
			  "paths": {
			    "/pets": {
			      "get": {
			        "summary": "List Pets",
			        "responses": {
			          "200": {
			            "description": "OK",
			            "schema": {
			              "type": "array",
			              "items": {
			                "type": "object",
			                "properties": {
			                  "id": { "type": "integer" },
			                  "name": { "type": "string" }
			                }
			              }
			            }
			          }
			        }
			      }
			    }
			  }
			}
		""".trimIndent()

		val parsed = parser.parse(spec)

		assertEquals("Legacy API", parsed.title)
		assertEquals("v1", parsed.version)
		assertEquals(1, parsed.endpoints.size)

		val endpoint = parsed.endpoints.single()
		assertEquals("GET", endpoint.method)
		assertEquals("/pets", endpoint.path)
		assertEquals("List Pets", endpoint.alias)
		assertEquals(emptyList(), endpoint.tags)
		assertEquals(1, endpoint.responses.size)
		assertEquals(200, endpoint.responses.single().statusCode)
		assertEquals("OK", endpoint.responses.single().name)
	}

	@Test
	fun `preserves endpoint tags from OpenAPI specs`() {
		val spec = """
			openapi: 3.0.3
			info:
			  title: Tagged API
			  version: 1.0.0
			paths:
			  /videos:
			    get:
			      summary: List videos
			      tags:
			        - youtube
			        - catalog
			      responses:
			        "200":
			          description: OK
		""".trimIndent()

		val parsed = parser.parse(spec)

		assertEquals(listOf("youtube", "catalog"), parsed.endpoints.single().tags)
	}

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

	@Test
	fun `uses summary or operation id for aliases`() {
		val spec = """
            openapi: 3.0.3
            info:
              title: Alias API
              version: 1.0.0
            paths:
              /pets:
                get:
                  summary: Browse Pets
                  description: Browse all pets
                  operationId: listPets
                  responses:
                    "200":
                      description: OK
              /pets/{petId}:
                get:
                  operationId: getPetById
                  responses:
                    "200":
                      description: OK
        """.trimIndent()

		val parsed = parser.parse(spec)

		val listPets = parsed.endpoints.first { it.path == "/pets" }
		assertEquals("Browse Pets", listPets.alias)
		assertEquals("Browse all pets", listPets.description)
		assertEquals("listPets", listPets.referenceName)

		val getPet = parsed.endpoints.first { it.path == "/pets/{petId}" }
		assertEquals("Get Pet By Id", getPet.alias)
		assertEquals("getPetById", getPet.referenceName)
	}

	@Test
	fun `uses response description as variant name`() {
		val spec = """
            openapi: 3.0.3
            info:
              title: Named Responses API
              version: 1.0.0
            paths:
              /orders:
                post:
                  responses:
                    "201":
                      description: Order Created
                    "422":
                      description: Validation Failed
                    "500":
                      description: Internal Server Error
        """.trimIndent()

		val parsed = parser.parse(spec)
		val responses = parsed.endpoints.single().responses

		assertEquals("Order Created", responses.first { it.statusCode == 201 }.name)
		assertEquals("Validation Failed", responses.first { it.statusCode == 422 }.name)
		assertEquals("Internal Server Error", responses.first { it.statusCode == 500 }.name)
	}

	@Test
	fun `falls back to status-based variant name when description is absent`() {
		val spec = """
            openapi: 3.0.3
            info:
              title: No Description API
              version: 1.0.0
            paths:
              /items:
                get:
                  responses:
                    "200":
                      content:
                        application/json:
                          schema:
                            type: object
                    "404":
                      content:
                        application/json:
                          schema:
                            type: object
        """.trimIndent()

		val parsed = parser.parse(spec)
		val responses = parsed.endpoints.single().responses

		assertEquals("default", responses.first { it.statusCode == 200 }.name)
		assertEquals("error-404", responses.first { it.statusCode == 404 }.name)
	}

	@Test
	fun `parses required cookie parameters with example values`() {
		val spec = """
            openapi: 3.0.3
            info:
              title: Cookie API
              version: 1.0.0
            paths:
              /profile:
                get:
                  parameters:
                    - in: cookie
                      name: session_id
                      required: true
                      schema:
                        type: string
                        example: abc123
                    - in: cookie
                      name: theme
                      required: true
                      example: dark
                      schema:
                        type: string
                  responses:
                    "200":
                      description: OK
        """.trimIndent()

		val parsed = parser.parse(spec)

		val endpoint = parsed.endpoints.single()
		assertEquals(2, endpoint.cookies.size)
		assertEquals("abc123", endpoint.cookies.first { it.name == "session_id" }.match)
		assertEquals("dark", endpoint.cookies.first { it.name == "theme" }.match)
		assertEquals(MatchType.EQUAL_TO, endpoint.cookies.first { it.name == "theme" }.matchType)
	}

	@Test
	fun `parses query parameters with required flags and example values`() {
		val spec = """
            openapi: 3.0.3
            info:
              title: Search API
              version: 1.0.0
            paths:
              /search:
                get:
                  parameters:
                    - in: query
                      name: q
                      required: true
                      schema:
                        type: string
                        example: laptop
                    - in: query
                      name: page
                      schema:
                        type: integer
                        default: 1
                    - in: query
                      name: sort
                      required: true
                      example: popular
                      schema:
                        type: string
                  responses:
                    "200":
                      description: OK
        """.trimIndent()

		val parsed = parser.parse(spec)

		val endpoint = parsed.endpoints.single()
		assertEquals(listOf("q", "sort"), endpoint.requiredQueryParameters)
		assertEquals(3, endpoint.queryParameters.size)
		assertEquals("laptop", endpoint.queryParameters.first { it.name == "q" }.match)
		assertEquals(true, endpoint.queryParameters.first { it.name == "q" }.required)
		assertEquals("1", endpoint.queryParameters.first { it.name == "page" }.match)
		assertEquals(null, endpoint.queryParameters.first { it.name == "page" }.required)
		assertEquals(MatchType.EQUAL_TO, endpoint.queryParameters.first { it.name == "sort" }.matchType)
	}
}
