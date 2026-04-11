package com.moqserver.studio.domain

import com.moqserver.studio.projectformat.MatchType
import com.moqserver.studio.projectformat.RuleMatcher
import com.moqserver.studio.projectformat.YamlValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ImportConverterTest {
	@Test
	fun `preserves imported endpoint tags`() {
		val parsed = ParsedSpec(
			title = "Imported API",
			version = "1.0.0",
			endpoints = listOf(
				ParsedEndpoint(
					method = "GET",
					path = "/videos",
					tags = listOf("youtube", "catalog"),
					responses = listOf(
						ParsedResponse(name = "default", statusCode = 200, body = "[]"),
					),
				),
			),
		)

		val project = ImportConverter.convert(
			spec = parsed,
			acceptedEntries = parsed.endpoints.map { ImportEndpointEntry(endpoint = it) },
			projectName = "Imported API",
			projectPath = "/tmp/imported-api",
		)

		assertEquals(listOf("youtube", "catalog"), project.endpoints.single().tags)
	}

	@Test
	fun `parses json bodies defensively and marks the correct default variant`() {
        val parsed = ParsedSpec(
            title = "Imported API",
            version = "1.0.0",
            endpoints = listOf(
                ParsedEndpoint(
                    method = "GET",
                    path = "/items",
                    responses = listOf(
                        ParsedResponse(
                            name = "error-500",
                            statusCode = 500,
                            body = "{not json}",
                        ),
                        ParsedResponse(
                            name = "default",
                            statusCode = 200,
                            body = "true",
                        ),
                        ParsedResponse(
                            name = "success-201",
                            statusCode = 201,
                            body = "{\"id\":1,\"tags\":[\"a\"]}",
                        ),
                    ),
                ),
            ),
        )

        val project = ImportConverter.convert(
            spec = parsed,
            acceptedEntries = parsed.endpoints.map { ImportEndpointEntry(endpoint = it) },
            projectName = "Imported API",
            projectPath = "/tmp/imported-api",
        )

        assertEquals("Imported API", project.manifest.name)
        assertEquals(1, project.endpoints.size)

        val endpoint = project.endpoints.single()
        assertEquals("get-items", endpoint.id)
        assertEquals("List Items", endpoint.alias)
        assertEquals("listItems", endpoint.referenceName)
        assertEquals(3, endpoint.variants.size)
        assertTrue(endpoint.variants.any { it.isDefault == true && it.name == "Success" })
        assertEquals("error", endpoint.variants.first { it.name == "Error" }.referenceName)
        assertEquals("success", endpoint.variants.first { it.name == "Success" }.referenceName)
        assertEquals("success2", endpoint.variants.first { it.name == "Success 2" }.referenceName)

        val fallbackBody = endpoint.variants.first { it.name == "Error" }.body
        assertIs<YamlValue.Str>(fallbackBody)
        assertEquals("{not json}", fallbackBody.value)

        val booleanBody = endpoint.variants.first { it.name == "Success" }.body
        assertIs<YamlValue.Bool>(booleanBody)
        assertEquals(true, booleanBody.value)

        val objectBody = endpoint.variants.first { it.name == "Success 2" }.body
        assertTrue(objectBody is YamlValue.Obj)
        val idValue = objectBody.value["id"]
        assertIs<YamlValue.Int>(idValue)
        assertEquals(1, idValue.value)
    }

    @Test
    fun `preserves parsed aliases when importing`() {
        val parsed = ParsedSpec(
            title = "Imported API",
            version = "1.0.0",
            endpoints = listOf(
                ParsedEndpoint(
                    method = "GET",
                    path = "/pets",
                    alias = "Browse Pets",
                    description = "Returns all pets",
                    referenceName = "browsePets",
                    responses = listOf(
                        ParsedResponse(name = "default", statusCode = 200, body = "[]"),
                    ),
                ),
            ),
        )

        val project = ImportConverter.convert(
            spec = parsed,
            acceptedEntries = parsed.endpoints.map { ImportEndpointEntry(endpoint = it) },
            projectName = "Imported API",
            projectPath = "/tmp/imported-api",
        )

        assertEquals("Browse Pets", project.endpoints.single().alias)
        assertEquals("Returns all pets", project.endpoints.single().description)
        assertEquals("browsePets", project.endpoints.single().referenceName)
    }

    @Test
    fun `generates unique endpoint reference names from imported aliases`() {
        val parsed = ParsedSpec(
            title = "Imported API",
            version = "1.0.0",
            endpoints = listOf(
                ParsedEndpoint(
                    method = "GET",
                    path = "/pets",
                    alias = "Browse Pets",
                    responses = listOf(ParsedResponse(name = "default", statusCode = 200, body = "[]")),
                ),
                ParsedEndpoint(
                    method = "POST",
                    path = "/pets",
                    alias = "Browse Pets",
                    responses = listOf(ParsedResponse(name = "created", statusCode = 201, body = "{}")),
                ),
            ),
        )

        val project = ImportConverter.convert(
            spec = parsed,
            acceptedEntries = parsed.endpoints.map { ImportEndpointEntry(endpoint = it) },
            projectName = "Imported API",
            projectPath = "/tmp/imported-api",
        )

        assertEquals(listOf("browsePets", "browsePets2"), project.endpoints.map { it.referenceName })
    }

    @Test
    fun `converts parsed cookies into request rules`() {
        val parsed = ParsedSpec(
            title = "Imported API",
            version = "1.0.0",
            endpoints = listOf(
                ParsedEndpoint(
                    method = "GET",
                    path = "/profile",
                    cookies = listOf(
                        RuleMatcher(
                            name = "session_id",
                            match = "abc123",
                            required = true,
                            matchType = MatchType.EQUAL_TO,
                        )
                    ),
                    responses = listOf(
                        ParsedResponse(name = "default", statusCode = 200, body = "{}"),
                    ),
                ),
            ),
        )

        val project = ImportConverter.convert(
            spec = parsed,
            acceptedEntries = parsed.endpoints.map { ImportEndpointEntry(endpoint = it) },
            projectName = "Imported API",
            projectPath = "/tmp/imported-api",
        )

        val cookie = project.endpoints.single().requestRules?.cookies?.single()
        assertEquals("session_id", cookie?.name)
        assertEquals("abc123", cookie?.match)
        assertEquals(MatchType.EQUAL_TO, cookie?.matchType)
    }

    @Test
    fun `converts parsed query parameters into request rules`() {
        val parsed = ParsedSpec(
            title = "Imported API",
            version = "1.0.0",
            endpoints = listOf(
                ParsedEndpoint(
                    method = "GET",
                    path = "/search",
                    queryParameters = listOf(
                        RuleMatcher(
                            name = "q",
                            match = "laptop",
                            required = true,
                            matchType = MatchType.EQUAL_TO,
                        ),
                        RuleMatcher(name = "page", match = "1", matchType = MatchType.EQUAL_TO),
                    ),
                    responses = listOf(
                        ParsedResponse(name = "default", statusCode = 200, body = "{}"),
                    ),
                ),
            ),
        )

        val project = ImportConverter.convert(
            spec = parsed,
            acceptedEntries = parsed.endpoints.map { ImportEndpointEntry(endpoint = it) },
            projectName = "Imported API",
            projectPath = "/tmp/imported-api",
        )

        val queryParams = project.endpoints.single().requestRules?.queryParams.orEmpty()
        assertEquals(2, queryParams.size)
        assertEquals("laptop", queryParams.first { it.name == "q" }.match)
        assertEquals(true, queryParams.first { it.name == "q" }.required)
        assertEquals("1", queryParams.first { it.name == "page" }.match)
        assertEquals(MatchType.EQUAL_TO, queryParams.first { it.name == "page" }.matchType)
    }

    @Test
    fun `includes generated import variants after parsed responses`() {
        val endpoint = ParsedEndpoint(
            method = "GET",
            path = "/pets",
            responses = listOf(
                ParsedResponse(
                    name = "default",
                    statusCode = 200,
                    headers = mapOf("Content-Type" to "application/json"),
                    body = "[]",
                ),
            ),
        )
        val parsed = ParsedSpec(
            title = "Imported API",
            version = "1.0.0",
            endpoints = listOf(endpoint),
        )

        val project = ImportConverter.convert(
            spec = parsed,
            acceptedEntries = listOf(
                ImportEndpointEntry(
                    endpoint = endpoint,
                    generatedResponses = listOf(
                        ParsedResponse(
                            name = "not-found",
                            statusCode = 404,
                            headers = mapOf("Content-Type" to "application/json"),
                            body = "{\"error\":\"missing\"}",
                            description = "Missing pet",
                        ),
                    ),
                ),
            ),
            projectName = "Imported API",
            projectPath = "/tmp/imported-api",
        )

        val variants = project.endpoints.single().variants
        assertEquals(2, variants.size)
        assertEquals("Success", variants[0].name)
        assertEquals("not-found", variants[1].name)
        assertEquals("Missing pet", variants[1].description)
        assertEquals(404, variants[1].status)
    }
}
