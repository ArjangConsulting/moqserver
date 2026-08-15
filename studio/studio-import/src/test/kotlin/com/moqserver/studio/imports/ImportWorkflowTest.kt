package com.moqserver.studio.imports

import com.moqserver.studio.domain.ImportConverter
import com.moqserver.studio.domain.ImportEndpointEntry
import com.moqserver.studio.domain.ParsedEndpoint
import com.moqserver.studio.domain.ParsedResponse
import com.moqserver.studio.domain.ParsedSpec
import com.moqserver.studio.projectformat.ProjectRepository
import com.moqserver.studio.projectformat.YamlValue
import com.moqserver.studio.projectformat.format.FormatBinaryLocator
import com.moqserver.studio.projectformat.format.FormatClient
import com.moqserver.studio.projectformat.format.FormatProcess
import java.io.File
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
			acceptedEntries = listOf(ImportEndpointEntry(endpoint = endpoint)),
			projectName = "Notes",
			projectPath = "/tmp/notes",
		)

		val variants = project.endpoints.single().variants
		assertEquals("listNotes", project.endpoints.single().referenceName)
		assertEquals(listOf("error", "success"), variants.map { it.referenceName })
		assertEquals("Success", variants.single { it.isDefault == true }.name)
		assertIs<YamlValue.Str>(variants.single { it.name == "Success" }.body)
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
            """.trimIndent(),
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
            """.trimIndent(),
		)

		val endpoint = parsed.endpoints.single()
		assertEquals("/image.jpg", endpoint.path)
		assertEquals("/9j/4AAQSkZJRg==", endpoint.responses.single().body)
	}

	@Test
	fun `har import preserves duplicate same-status same-body responses as separate variants`() {
		val parsed = HARImportParser().parse(
			"""
            {
              "log": {
                "version": "1.2",
                "entries": [
                  {
                    "request": {
                      "method": "GET",
                      "url": "https://example.com/api/items",
                      "headers": []
                    },
                    "response": {
                      "status": 200,
                      "headers": [],
                      "content": {
                        "mimeType": "application/json",
                        "text": "{\"ok\":true}"
                      }
                    }
                  },
                  {
                    "request": {
                      "method": "GET",
                      "url": "https://example.com/api/items",
                      "headers": []
                    },
                    "response": {
                      "status": 200,
                      "headers": [],
                      "content": {
                        "mimeType": "application/json",
                        "text": "{\"ok\":true}"
                      }
                    }
                  }
                ]
              }
            }
            """.trimIndent(),
		)

		val endpoint = parsed.endpoints.single()
		val project = ImportConverter.convert(
			spec = ParsedSpec(title = parsed.title, version = parsed.version, endpoints = parsed.endpoints),
			acceptedEntries = listOf(ImportEndpointEntry(endpoint = endpoint)),
			projectName = "HAR Duplicate Import",
			projectPath = "/tmp/har-duplicate-import",
		)

		val variants = project.endpoints.single().variants
		assertEquals(2, variants.size)
		assertEquals(listOf("Success", "Success 2"), variants.map { it.name })
		assertEquals(listOf(200, 200), variants.map { it.status })
	}

	@Test
	fun `har import round trips through project save and load with special headers and binary bodies`() {
		val binaryPath = try {
			FormatBinaryLocator.locate()
		} catch (e: FormatBinaryLocator.NotFoundException) {
			println("Skipping: ${e.message}")
			return
		}
		val process = FormatProcess(locateBinary = { binaryPath }).apply { start() }
		try {
			val parsed = HARImportParser().parse(sampleBinaryHar())

			val endpoint = parsed.endpoints.single()
			val project = ImportConverter.convert(
				spec = ParsedSpec(title = parsed.title, version = parsed.version, endpoints = parsed.endpoints),
				acceptedEntries = listOf(ImportEndpointEntry(endpoint = endpoint)),
				projectName = "HAR Import Regression",
				projectPath = "/tmp/har-import-regression",
			)

			val tempDir = kotlin.io.path.createTempDirectory("moqproj-har-roundtrip").toFile()
			try {
				runBlocking {
					withTimeout(15_000) {
						val repository = ProjectRepository(FormatClient(process))
						repository.save(project, tempDir.absolutePath)

						val reloaded = repository.load(tempDir.absolutePath)
						val variant = reloaded.endpoints.single().variants.single()
						val headers = requireNotNull(variant.headers)
						val bodyFile = requireNotNull(variant.bodyFile)
						val fixture = File(tempDir, bodyFile)

						assertEquals("/vi/iONDebHX9qk/mqdefault.jpg", reloaded.endpoints.single().path)
						// Swift's fixture-path scheme sanitizes the whole endpoint path, not just its
						// last segment the way Kotlin's own (now-deleted) writer did — a one-time,
						// expected naming difference now that Swift's writer is canonical.
						assertEquals(
							"fixtures/responses/get-vi-iondebhx9qk-mqdefaultjpg/vi-iondebhx9qk-mqdefault-jpg-success.jpg",
							bodyFile,
						)
						assertNotNull(variant.bodyFile)
						assertEquals(null, variant.body)
						assertTrue(fixture.isFile)
						assertTrue(fixture.readBytes().contentEquals(Base64.getDecoder().decode("/9j/4AAQSkZJRg==")))
						assertEquals(
							"""{"group":"youtube","max_age":2592000,"endpoints":[{"url":"https://csp.withgoogle.com/csp/report-to/youtube"}]}""",
							headers["report-to"],
						)
						assertEquals(
							"""h3=":443"; ma=2592000,h3-29=":443"; ma=2592000""",
							headers["Alt-Svc"],
						)
					}
				}
			} finally {
				tempDir.deleteRecursively()
			}
		} finally {
			process.stop()
		}
	}

	@Test
	fun `har parser can parse bundled sample fixture`() {
		val parser = HARImportParser()
		val fixture = File(findProjectRoot(), "server/Tests/MoqRuntimeTests/Fixtures/sample.har")

		val parsed = parser.parse(fixture.readText())

		assertTrue(parsed.endpoints.isNotEmpty())
		assertTrue(parsed.endpoints.any { it.responses.isNotEmpty() })
	}

	private fun findProjectRoot(): File {
		val cwd = File(System.getProperty("user.dir"))
		return when (cwd.name) {
			"studio-import" -> cwd.parentFile.parentFile
			"studio" -> cwd.parentFile
			else -> cwd
		}
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
