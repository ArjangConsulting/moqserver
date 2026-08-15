package com.moqserver.studio.domain

import com.moqserver.studio.projectformat.ProjectRepository
import com.moqserver.studio.projectformat.format.FormatBinaryLocator
import com.moqserver.studio.projectformat.format.FormatClient
import com.moqserver.studio.projectformat.format.FormatProcess
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Kotlin-client integration test for import: HAR parsing itself (base64 handling, duplicate-
 * response naming, ...) is Swift's job now and is covered by the Swift core's own
 * `HARImporterTests`/`ImportConverterTests` — re-asserting that here through IPC would test Swift
 * a second time, more slowly, from a different client. What's worth verifying from the Kotlin
 * side is the whole chain this module actually drives: `FormatClient.parseHar` ->
 * `RemoteParsedSpec.toParsedSpec()` -> `ImportConverter.convert` -> `ProjectRepository.save`/
 * `load`, landing the same bytes a real import would.
 *
 * Requires a built moq-format binary; skips gracefully when none is found, matching
 * `FormatClientIntegrationTest`.
 */
class RemoteHARImportTest {
    @Test
    fun `har import round trips through parseHar, ImportConverter, and ProjectRepository`() {
        val binaryPath = try {
            FormatBinaryLocator.locate()
        } catch (e: FormatBinaryLocator.NotFoundException) {
            println("Skipping: ${e.message}")
            return
        }
        val process = FormatProcess(locateBinary = { binaryPath }).apply { start() }
        try {
            val harPath = kotlin.io.path.createTempFile("moqproj-har-roundtrip", ".har").toFile()
            val tempDir = kotlin.io.path.createTempDirectory("moqproj-har-roundtrip").toFile()
            try {
                harPath.writeText(sampleBinaryHar())

                runBlocking {
                    withTimeout(15_000) {
                        val client = FormatClient(process)
                        val parsed = client.parseHar(harPath.absolutePath).toParsedSpec()
                        val endpoint = parsed.endpoints.single()

                        val project = ImportConverter.convert(
                            spec = ParsedSpec(
                                title = parsed.title,
                                version = parsed.version,
                                endpoints = parsed.endpoints,
                            ),
                            acceptedEntries = listOf(ImportEndpointEntry(endpoint = endpoint)),
                            projectName = "HAR Import Regression",
                            projectPath = tempDir.absolutePath,
                        )

                        val repository = ProjectRepository(client)
                        repository.save(project, tempDir.absolutePath)

                        val reloaded = repository.load(tempDir.absolutePath)
                        assertRoundTrippedFixture(reloaded, tempDir)
                    }
                }
            } finally {
                harPath.delete()
                tempDir.deleteRecursively()
            }
        } finally {
            process.stop()
        }
    }

    private fun assertRoundTrippedFixture(
        reloaded: com.moqserver.studio.projectformat.MoqProject,
        tempDir: File,
    ) {
        val variant = reloaded.endpoints.single().variants.single()
        val headers = requireNotNull(variant.headers)
        val bodyFile = requireNotNull(variant.bodyFile)
        val fixture = File(tempDir, bodyFile)

        assertEquals("/vi/iONDebHX9qk/mqdefault.jpg", reloaded.endpoints.single().path)
        // Swift's fixture-path scheme sanitizes the whole endpoint path, not just its last
        // segment - an expected naming difference now that Swift's writer is canonical.
        assertEquals(
            "fixtures/responses/get-vi-iondebhx9qk-mqdefaultjpg/vi-iondebhx9qk-mqdefault-jpg-success.jpg",
            bodyFile,
        )
        assertNotNull(variant.bodyFile)
        assertEquals(null, variant.body)
        assertTrue(fixture.isFile)
        assertTrue(fixture.readBytes().contentEquals(Base64.getDecoder().decode("/9j/4AAQSkZJRg==")))
        assertEquals(
            """{"group":"youtube","max_age":2592000,""" +
                """"endpoints":[{"url":"https://csp.withgoogle.com/csp/report-to/youtube"}]}""",
            headers["report-to"],
        )
        assertEquals(
            """h3=":443"; ma=2592000,h3-29=":443"; ma=2592000""",
            headers["Alt-Svc"],
        )
    }

    private fun sampleBinaryHar(): String = """
        {
          "log": {
            "version": "1.2",
            "creator": { "name": "Browser", "version": "1.0" },
            "entries": [
              {
                "request": {
                  "method": "GET",
                  "url": "https://img.youtube.com/vi/iONDebHX9qk/mqdefault.jpg",
                  "headers": [],
                  "queryString": []
                },
                "response": {
                  "status": 200,
                  "headers": [
                    { "name": "Alt-Svc", "value": "h3=\":443\"; ma=2592000,h3-29=\":443\"; ma=2592000" },
                    { "name": "report-to", "value": "{\"group\":\"youtube\",\"max_age\":2592000,\"endpoints\":[{\"url\":\"https://csp.withgoogle.com/csp/report-to/youtube\"}]}" }
                  ],
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
}
