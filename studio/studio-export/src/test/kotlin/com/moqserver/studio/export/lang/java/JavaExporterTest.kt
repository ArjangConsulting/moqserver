package com.moqserver.studio.export.lang.java

import com.moqserver.studio.export.ExportCatalog
import com.moqserver.studio.export.ExportEndpoint
import com.moqserver.studio.export.ExportLanguage
import com.moqserver.studio.export.ExportOptions
import com.moqserver.studio.export.ExportVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaExporterTest {

	private val catalog = ExportCatalog(
		projectName = "Test API",
		endpoints = listOf(
			ExportEndpoint(
				referenceName = "listUsers",
				method = "GET",
				path = "/api/v1/users",
				description = "Returns all users",
				variants = listOf(
					ExportVariant("success", "success", 200, true, "Successful response"),
				),
			),
		),
	)

	@Test
	fun `generates correct filename`() {
		val options = ExportOptions(languages = setOf(ExportLanguage.JAVA))
		val file = JavaExporter().generate(catalog, options)

		assertEquals("MoqAPIs.java", file.fileName)
	}

	@Test
	fun `includes package when specified`() {
		val options = ExportOptions(languages = setOf(ExportLanguage.JAVA), javaPackage = "com.example.api")
		val file = JavaExporter().generate(catalog, options)

		assertTrue(file.content.contains("package com.example.api;"))
	}

	@Test
	fun `generates class structure`() {
		val options = ExportOptions(languages = setOf(ExportLanguage.JAVA))
		val file = JavaExporter().generate(catalog, options)

		assertTrue(file.content.contains("public final class MoqAPIs {"))
		assertTrue(file.content.contains("public static final class ListUsers {"))
		assertTrue(file.content.contains("public enum Variant {"))
	}

	@Test
	fun `emits path and method as comments instead of constants`() {
		val options = ExportOptions(languages = setOf(ExportLanguage.JAVA))
		val file = JavaExporter().generate(catalog, options)

		assertTrue(file.content.contains("* GET /api/v1/users"))
		assertFalse(file.content.contains("public static final String PATH"))
		assertFalse(file.content.contains("public static final String METHOD"))
	}

	@Test
	fun `emits status and isDefault as comments and keeps referenceName getter only`() {
		val options = ExportOptions(languages = setOf(ExportLanguage.JAVA))
		val file = JavaExporter().generate(catalog, options)

		assertTrue(file.content.contains("* Status: 200 (Default)"))
		assertTrue(file.content.contains("public String getReferenceName()"))
		assertFalse(file.content.contains("isDefault"))
		assertFalse(file.content.contains("getStatus"))
		assertFalse(file.content.contains("getDescription"))
	}
}
