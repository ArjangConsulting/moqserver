package com.moqserver.studio.export.lang.swift

import com.moqserver.studio.export.ExportCatalog
import com.moqserver.studio.export.ExportEndpoint
import com.moqserver.studio.export.ExportLanguage
import com.moqserver.studio.export.ExportOptions
import com.moqserver.studio.export.ExportVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwiftExporterTest {

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
					ExportVariant("serverError", "server error", 500, false, null),
				),
			),
		),
	)

	@Test
	fun `generates correct filename`() {
		val options = ExportOptions(languages = setOf(ExportLanguage.SWIFT))
		val file = SwiftExporter().generate(catalog, options)

		assertEquals("MoqAPIs.swift", file.fileName)
	}

	@Test
	fun `generates caseless enum structure`() {
		val options = ExportOptions(languages = setOf(ExportLanguage.SWIFT))
		val file = SwiftExporter().generate(catalog, options)

		assertTrue(file.content.contains("enum MoqAPIs {"))
		assertTrue(file.content.contains("enum ListUsers {"))
		assertTrue(file.content.contains("enum Variant: String, CaseIterable {"))
	}

	@Test
	fun `emits path and method as comments instead of constants`() {
		val options = ExportOptions(languages = setOf(ExportLanguage.SWIFT))
		val file = SwiftExporter().generate(catalog, options)

		assertTrue(file.content.contains("/// GET /api/v1/users"))
		assertFalse(file.content.contains("static let path"))
		assertFalse(file.content.contains("static let method"))
	}

	@Test
	fun `generates variant cases`() {
		val options = ExportOptions(languages = setOf(ExportLanguage.SWIFT))
		val file = SwiftExporter().generate(catalog, options)

		assertTrue(file.content.contains("""case success = "success""""))
		assertTrue(file.content.contains("""case serverError = "serverError""""))
	}

	@Test
	fun `emits status and isDefault as comments only`() {
		val options = ExportOptions(languages = setOf(ExportLanguage.SWIFT))
		val file = SwiftExporter().generate(catalog, options)

		assertTrue(file.content.contains("/// Status: 200 (Default)"))
		assertTrue(file.content.contains("/// Status: 500"))
		assertFalse(file.content.contains("var isDefault"))
		assertFalse(file.content.contains("var status"))
		assertFalse(file.content.contains("var variantDescription"))
	}

	@Test
	fun `includes description comments when both enabled`() {
		val options = ExportOptions(
			languages = setOf(ExportLanguage.SWIFT),
			includeApiDescriptions = true,
			includeVariantDescriptions = true,
		)
		val file = SwiftExporter().generate(catalog, options)

		assertTrue(file.content.contains("/// Successful response"))
		assertTrue(file.content.contains("Returns all users"))
	}

	@Test
	fun `omits all descriptions when both disabled`() {
		val options = ExportOptions(
			languages = setOf(ExportLanguage.SWIFT),
			includeApiDescriptions = false,
			includeVariantDescriptions = false,
		)
		val file = SwiftExporter().generate(catalog, options)

		assertFalse(file.content.contains("Returns all users"))
		assertFalse(file.content.contains("/// Successful response"))
		// Status comments are always present
		assertTrue(file.content.contains("/// Status: 200 (Default)"))
		assertTrue(file.content.contains("/// Status: 500"))
	}

	@Test
	fun `includes only API descriptions when variant descriptions disabled`() {
		val options = ExportOptions(
			languages = setOf(ExportLanguage.SWIFT),
			includeApiDescriptions = true,
			includeVariantDescriptions = false,
		)
		val file = SwiftExporter().generate(catalog, options)

		assertTrue(file.content.contains("Returns all users"))
		assertFalse(file.content.contains("/// Successful response"))
	}

	@Test
	fun `includes only variant descriptions when API descriptions disabled`() {
		val options = ExportOptions(
			languages = setOf(ExportLanguage.SWIFT),
			includeApiDescriptions = false,
			includeVariantDescriptions = true,
		)
		val file = SwiftExporter().generate(catalog, options)

		assertFalse(file.content.contains("Returns all users"))
		assertTrue(file.content.contains("/// Successful response"))
	}

	@Test
	fun `deduplicates normalized Swift symbols`() {
		val collisionCatalog = ExportCatalog(
			projectName = "Swift Collisions",
			endpoints = listOf(
				ExportEndpoint(
					referenceName = "fooBar",
					method = "GET",
					path = "/one",
					description = null,
					variants = listOf(
						ExportVariant("serverError", "Server Error", 500, false, null),
						ExportVariant("server_error", "Server Error 2", 502, false, null),
					),
				),
				ExportEndpoint(
					referenceName = "foo_bar",
					method = "GET",
					path = "/two",
					description = null,
					variants = listOf(ExportVariant("ok", "OK", 200, true, null)),
				),
			),
		)

		val options = ExportOptions(languages = setOf(ExportLanguage.SWIFT))
		val file = SwiftExporter().generate(collisionCatalog, options)

		assertTrue(file.content.contains("enum FooBar {"))
		assertTrue(file.content.contains("enum FooBar2 {"))
		assertTrue(file.content.contains("""case serverError = "serverError""""))
		assertTrue(file.content.contains("""case serverError2 = "server_error""""))
	}
}
