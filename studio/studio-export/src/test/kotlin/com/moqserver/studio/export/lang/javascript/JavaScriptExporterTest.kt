package com.moqserver.studio.export.lang.javascript

import com.moqserver.studio.export.ExportCatalog
import com.moqserver.studio.export.ExportEndpoint
import com.moqserver.studio.export.ExportLanguage
import com.moqserver.studio.export.ExportOptions
import com.moqserver.studio.export.ExportVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaScriptExporterTest {

	private val catalog = ExportCatalog(
		projectName = "Test API",
		endpoints = listOf(
			ExportEndpoint(
				referenceName = "listUsers",
				method = "GET",
				path = "/api/v1/users",
				description = null,
				variants = listOf(
					ExportVariant("success", "success", 200, true, null),
				),
			),
		),
	)

	@Test
	fun `generates correct filename`() {
		val options = ExportOptions(languages = setOf(ExportLanguage.JAVASCRIPT))
		val file = JavaScriptExporter().generate(catalog, options)

		assertEquals("moq-apis.js", file.fileName)
	}

	@Test
	fun `generates frozen object structure`() {
		val options = ExportOptions(languages = setOf(ExportLanguage.JAVASCRIPT))
		val file = JavaScriptExporter().generate(catalog, options)

		assertTrue(file.content.contains("export const MoqAPIs = Object.freeze({"))
		assertTrue(file.content.contains("ListUsers: Object.freeze({"))
		assertTrue(file.content.contains("Variant: Object.freeze({"))
	}

	@Test
	fun `emits path and method as comments instead of properties`() {
		val options = ExportOptions(languages = setOf(ExportLanguage.JAVASCRIPT))
		val file = JavaScriptExporter().generate(catalog, options)

		assertTrue(file.content.contains("* GET /api/v1/users"))
		assertFalse(file.content.contains("PATH:"))
		assertFalse(file.content.contains("METHOD:"))
	}

	@Test
	fun `generates variant entries with referenceName only`() {
		val options = ExportOptions(languages = setOf(ExportLanguage.JAVASCRIPT))
		val file = JavaScriptExporter().generate(catalog, options)

		assertTrue(file.content.contains("SUCCESS: Object.freeze({"))
		assertTrue(file.content.contains("""referenceName: "success""""))
		assertFalse(file.content.contains("isDefault"))
		assertFalse(file.content.contains("status: 200"))
		assertFalse(file.content.contains("description:"))
	}

	@Test
	fun `emits status as comment on variant`() {
		val options = ExportOptions(languages = setOf(ExportLanguage.JAVASCRIPT))
		val file = JavaScriptExporter().generate(catalog, options)

		assertTrue(file.content.contains("* Status: 200 (Default)"))
	}
}
