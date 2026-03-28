package com.moqserver.studio.data

import com.moqserver.studio.domain.ImportConverter
import com.moqserver.studio.domain.ParsedEndpoint
import com.moqserver.studio.domain.ParsedResponse
import com.moqserver.studio.domain.ParsedSpec
import com.moqserver.studio.projectformat.YamlValue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ImportWorkflowTest {

    @Test
    fun `import converter keeps plain text bodies as strings and chooses a 2xx default variant`() {
        val endpoint = ParsedEndpoint(
            method = "GET",
            path = "/notes",
            responses = listOf(
                ParsedResponse(name = "error-404", statusCode = 404, body = "missing"),
                ParsedResponse(name = "success-200", statusCode = 200, body = "plain text body"),
            ),
        )

        val project = ImportConverter.convert(
            spec = ParsedSpec(title = "Notes", version = "1.0.0", endpoints = listOf(endpoint)),
            acceptedEndpoints = listOf(endpoint),
            projectName = "Notes",
            projectPath = "/tmp/notes",
        )

        val variants = project.endpoints.single().variants
        assertEquals("success-200", variants.single { it.isDefault == true }.name)
        assertIs<YamlValue.Str>(variants.single { it.name == "success-200" }.body)
    }

    @Test
    fun `har parser decodes base64 response bodies`() {
        val parsed = HARImportParser().parse(
            """
            {
              "log": {
                "version": "1.2",
                "entries": [
                  {
                    "request": {
                      "method": "GET",
                      "url": "https://example.com/api/users",
                      "headers": []
                    },
                    "response": {
                      "status": 200,
                      "headers": [],
                      "content": {
                        "mimeType": "application/json",
                        "text": "eyJvayI6dHJ1ZX0=",
                        "encoding": "base64"
                      }
                    }
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val endpoint = parsed.endpoints.single()
        assertEquals("/api/users", endpoint.path)
        assertEquals("{\"ok\":true}", endpoint.responses.single().body)
    }

    @Test
    fun `har parser preserves base64 text for binary mime types`() {
        val parsed = HARImportParser().parse(
            """
            {
              "log": {
                "version": "1.2",
                "entries": [
                  {
                    "request": {
                      "method": "GET",
                      "url": "https://example.com/image.jpg",
                      "headers": []
                    },
                    "response": {
                      "status": 200,
                      "headers": [],
                      "content": {
                        "mimeType": "image/jpeg",
                        "text": "/9j/4AAQSkZJRg==",
                        "encoding": "base64"
                      }
                    }
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val endpoint = parsed.endpoints.single()
        assertEquals("/image.jpg", endpoint.path)
        assertEquals("/9j/4AAQSkZJRg==", endpoint.responses.single().body)
    }

    @Test
    fun `har parser can parse bundled sample fixture`() {
        val parser = HARImportParser()
        val fixture = File(findProjectRoot(), "server/Tests/MoqParsingTests/Fixtures/sample.har")

        val parsed = parser.parse(fixture.readText())

        assertTrue(parsed.endpoints.isNotEmpty())
        assertTrue(parsed.endpoints.any { it.responses.isNotEmpty() })
    }

    private fun findProjectRoot(): File {
        val cwd = File(System.getProperty("user.dir"))
        return when (cwd.name) {
            "studio-data" -> cwd.parentFile.parentFile
            "studio" -> cwd.parentFile
            else -> cwd
        }
    }
}
