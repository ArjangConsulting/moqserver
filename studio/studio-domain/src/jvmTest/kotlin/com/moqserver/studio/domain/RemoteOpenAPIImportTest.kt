package com.moqserver.studio.domain

import com.moqserver.studio.projectformat.ProjectRepository
import com.moqserver.studio.projectformat.format.FormatBinaryLocator
import com.moqserver.studio.projectformat.format.FormatClient
import com.moqserver.studio.projectformat.format.FormatProcess
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The `parseOpenapi` counterpart to [RemoteHARImportTest] — see that class for why this stays a
 * thin chain-verification test rather than re-asserting OpenAPI parsing itself (Swift's own
 * `OpenAPIImporterTests` already covers that). What's OpenAPI-specific and worth pinning here is
 * `requiresBody`/`acceptedContentTypes`: Studio's AI mock-generation prompt reads them off the
 * parsed endpoint, and they only exist for OpenAPI (HAR has no request-body schema to report).
 *
 * Requires a built moq-format binary; skips gracefully when none is found, matching
 * `FormatClientIntegrationTest`.
 */
class RemoteOpenAPIImportTest {
    @Test
    fun `openapi source round trips through parseOpenapi, ImportConverter, and ProjectRepository`() {
        val binaryPath = try {
            FormatBinaryLocator.locate()
        } catch (e: FormatBinaryLocator.NotFoundException) {
            println("Skipping: ${e.message}")
            return
        }
        val process = FormatProcess(locateBinary = { binaryPath }).apply { start() }
        try {
            val specPath = kotlin.io.path.createTempFile("moqproj-openapi-roundtrip", ".yaml").toFile()
            val tempDir = kotlin.io.path.createTempDirectory("moqproj-openapi-roundtrip").toFile()
            try {
                specPath.writeText(sampleOpenAPISpec())

                runBlocking {
                    withTimeout(15_000) {
                        val client = FormatClient(process)
                        val result = client.parseOpenapi(specPath.absolutePath)
                        assertEquals(specPath.absolutePath, result.resolvedSource)

                        val parsed = result.spec.toParsedSpec()
                        assertRequestBodyMetadata(parsed)

                        val getUsers = parsed.endpoints.single { it.path == "/users" && it.method == "GET" }
                        val project = ImportConverter.convert(
                            spec = ParsedSpec(
                                title = parsed.title,
                                version = parsed.version,
                                endpoints = parsed.endpoints,
                            ),
                            acceptedEntries = listOf(ImportEndpointEntry(endpoint = getUsers)),
                            projectName = "OpenAPI Import Regression",
                            projectPath = tempDir.absolutePath,
                        )

                        val repository = ProjectRepository(client)
                        repository.save(project, tempDir.absolutePath)

                        val reloaded = repository.load(tempDir.absolutePath)
                        assertRoundTrippedBody(reloaded, tempDir)
                    }
                }
            } finally {
                specPath.delete()
                tempDir.deleteRecursively()
            }
        } finally {
            process.stop()
        }
    }

    private fun assertRequestBodyMetadata(parsed: ParsedSpec) {
        val createUser = parsed.endpoints.single { it.path == "/users" && it.method == "POST" }
        assertTrue(createUser.requiresBody)
        assertEquals(listOf("application/json"), createUser.acceptedContentTypes)

        val listUsers = parsed.endpoints.single { it.path == "/users" && it.method == "GET" }
        assertTrue(!listUsers.requiresBody)
        assertTrue(listUsers.acceptedContentTypes.isEmpty())
    }

    private fun assertRoundTrippedBody(reloaded: com.moqserver.studio.projectformat.MoqProject, tempDir: File) {
        val endpoint = reloaded.endpoints.single()
        assertEquals("/users", endpoint.path)
        val variant = endpoint.variants.single()
        val bodyFile = requireNotNull(variant.bodyFile)
        val fixture = File(tempDir, bodyFile)

        assertNotNull(variant.bodyFile)
        assertEquals(null, variant.body)
        assertTrue(fixture.isFile)
        val body = Json.parseToJsonElement(fixture.readText()).jsonObject
        assertTrue(body["users"]?.jsonArray?.isEmpty() == true)
    }

    private fun sampleOpenAPISpec(): String = """
        openapi: 3.0.3
        info:
          title: Regression API
          version: "1.0"
        paths:
          /users:
            get:
              operationId: listUsers
              responses:
                "200":
                  description: OK
                  content:
                    application/json:
                      schema:
                        type: object
                      example:
                        users: []
            post:
              operationId: createUser
              requestBody:
                required: true
                content:
                  application/json:
                    schema:
                      type: object
              responses:
                "201":
                  description: Created
    """.trimIndent()
}
