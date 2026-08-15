package com.moqserver.studio.projectformat

import com.moqserver.studio.projectformat.format.FormatBinaryLocator
import com.moqserver.studio.projectformat.format.FormatClient
import com.moqserver.studio.projectformat.format.FormatProcess
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * With no local Kotlin codec left, this is a smoke test of the load path against moq-format
 * itself: the canonical example, loaded through the real subprocess.
 */
class CanonicalFormatCompatibilityTest {
	@Test
	fun canonicalExampleLoadsInStudio() {
		val binaryPath = try {
			FormatBinaryLocator.locate()
		} catch (e: FormatBinaryLocator.NotFoundException) {
			println("Skipping: ${e.message}")
			return
		}
		val repositoryRoot = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
			.first { File(it, "format/schema.json").isFile }
		val projectDirectory = File(repositoryRoot, "format/examples/sample-app.moqproj")
		assertTrue(projectDirectory.isDirectory, "Canonical format example is missing")

		val process = FormatProcess(locateBinary = { binaryPath }).apply { start() }
		try {
			runBlocking {
				withTimeout(15_000) {
					val project = ProjectRepository(FormatClient(process)).load(projectDirectory.path)

					assertEquals("1", project.manifest.version)
					assertTrue(
						project.endpoints.any { endpoint ->
							endpoint.variants.any { variant -> variant.requestMatch != null && variant.description != null }
						},
					)
				}
			}
		} finally {
			process.stop()
		}
	}
}
