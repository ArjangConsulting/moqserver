package com.moqserver.studio.domain

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
        assertEquals(3, endpoint.variants.size)
        assertTrue(endpoint.variants.any { it.isDefault == true && it.name == "default" })

        val fallbackBody = endpoint.variants.first { it.name == "error-500" }.body
        assertIs<YamlValue.Str>(fallbackBody)
        assertEquals("{not json}", fallbackBody.value)

        val booleanBody = endpoint.variants.first { it.name == "default" }.body
        assertIs<YamlValue.Bool>(booleanBody)
        assertEquals(true, booleanBody.value)

        val objectBody = endpoint.variants.first { it.name == "success-201" }.body
        assertTrue(objectBody is YamlValue.Obj)
        val idValue = objectBody.value["id"]
        assertIs<YamlValue.Int>(idValue)
        assertEquals(1, idValue.value)
    }
}
