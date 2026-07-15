package com.moqserver.studio.projectformat

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CanonicalFormatCompatibilityTest {
	@Test
	fun canonicalExampleLoadsInStudio() {
		val repositoryRoot = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
			.first { File(it, "format/schema.json").isFile }
		val projectDirectory = File(repositoryRoot, "format/examples/sample-app.moqproj")

		assertTrue(projectDirectory.isDirectory, "Canonical format example is missing")
		val project = ProjectRepository().load(projectDirectory.path)

		assertEquals("1", project.manifest.version)
		assertTrue(
			project.endpoints.any { endpoint ->
				endpoint.variants.any { variant -> variant.requestMatch != null && variant.description != null }
			},
		)
	}
}
