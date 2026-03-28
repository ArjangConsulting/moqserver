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
            acceptedEndpoints = parsed.endpoints,
            projectName = "Imported API",
            projectPath = "/tmp/imported-api",
        )

        assertEquals("Imported API", project.manifest.name)
        assertEquals(1, project.endpoints.size)

        val endpoint = project.endpoints.single()
        assertEquals("get-items", endpoint.id)
        assertEquals("List Items", endpoint.alias)
        assertEquals(3, endpoint.variants.size)
        assertTrue(endpoint.variants.any { it.isDefault == true && it.name == "Success" })

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
                    responses = listOf(
                        ParsedResponse(name = "default", statusCode = 200, body = "[]"),
                    ),
                ),
            ),
        )

        val project = ImportConverter.convert(
            spec = parsed,
            acceptedEndpoints = parsed.endpoints,
            projectName = "Imported API",
            projectPath = "/tmp/imported-api",
        )

        assertEquals("Browse Pets", project.endpoints.single().alias)
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
            acceptedEndpoints = parsed.endpoints,
            projectName = "Imported API",
            projectPath = "/tmp/imported-api",
        )

        val cookie = project.endpoints.single().requestRules?.cookies?.single()
        assertEquals("session_id", cookie?.name)
        assertEquals("abc123", cookie?.match)
        assertEquals(MatchType.EQUAL_TO, cookie?.matchType)
    }
}
