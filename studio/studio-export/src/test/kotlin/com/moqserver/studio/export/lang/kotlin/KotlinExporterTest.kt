package com.moqserver.studio.export.lang.kotlin

import com.moqserver.studio.export.ExportCatalog
import com.moqserver.studio.export.ExportEndpoint
import com.moqserver.studio.export.ExportLanguage
import com.moqserver.studio.export.ExportOptions
import com.moqserver.studio.export.ExportVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinExporterTest {

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
        val options = ExportOptions(languages = setOf(ExportLanguage.KOTLIN))
        val file = KotlinExporter().generate(catalog, options)

        assertEquals("MoqAPIs.kt", file.fileName)
    }

    @Test
    fun `includes package when specified`() {
        val options = ExportOptions(languages = setOf(ExportLanguage.KOTLIN), kotlinPackage = "com.example.api")
        val file = KotlinExporter().generate(catalog, options)

        assertTrue(file.content.contains("package com.example.api"))
    }

    @Test
    fun `omits package when null`() {
        val options = ExportOptions(languages = setOf(ExportLanguage.KOTLIN))
        val file = KotlinExporter().generate(catalog, options)

        assertFalse(file.content.contains("package "))
    }

    @Test
    fun `generates object structure`() {
        val options = ExportOptions(languages = setOf(ExportLanguage.KOTLIN))
        val file = KotlinExporter().generate(catalog, options)

        assertTrue(file.content.contains("object MoqAPIs {"))
        assertTrue(file.content.contains("object ListUsers {"))
        assertTrue(file.content.contains("enum class Variant("))
    }

    @Test
    fun `includes path and method constants`() {
        val options = ExportOptions(languages = setOf(ExportLanguage.KOTLIN))
        val file = KotlinExporter().generate(catalog, options)

        assertTrue(file.content.contains("""const val PATH = "/api/v1/users""""))
        assertTrue(file.content.contains("""const val METHOD = "GET""""))
    }

    @Test
    fun `includes descriptions when both enabled`() {
        val options = ExportOptions(
            languages = setOf(ExportLanguage.KOTLIN),
            includeApiDescriptions = true,
            includeVariantDescriptions = true,
        )
        val file = KotlinExporter().generate(catalog, options)

        assertTrue(file.content.contains("Returns all users"))
        assertTrue(file.content.contains("Successful response"))
    }

    @Test
    fun `omits all descriptions when both disabled`() {
        val options = ExportOptions(
            languages = setOf(ExportLanguage.KOTLIN),
            includeApiDescriptions = false,
            includeVariantDescriptions = false,
        )
        val file = KotlinExporter().generate(catalog, options)

        assertTrue(file.content.contains("URL partial path:"))
        assertFalse(file.content.contains("Returns all users"))
        assertFalse(file.content.contains("Successful response"))
    }

    @Test
    fun `includes only API descriptions when variant descriptions disabled`() {
        val options = ExportOptions(
            languages = setOf(ExportLanguage.KOTLIN),
            includeApiDescriptions = true,
            includeVariantDescriptions = false,
        )
        val file = KotlinExporter().generate(catalog, options)

        assertTrue(file.content.contains("Returns all users"))
        assertFalse(file.content.contains("Successful response"))
    }

    @Test
    fun `includes only variant descriptions when API descriptions disabled`() {
        val options = ExportOptions(
            languages = setOf(ExportLanguage.KOTLIN),
            includeApiDescriptions = false,
            includeVariantDescriptions = true,
        )
        val file = KotlinExporter().generate(catalog, options)

        assertFalse(file.content.contains("Returns all users"))
        assertTrue(file.content.contains("Successful response"))
    }

    @Test
    fun `generates correct enum constants`() {
        val options = ExportOptions(languages = setOf(ExportLanguage.KOTLIN))
        val file = KotlinExporter().generate(catalog, options)

        assertTrue(file.content.contains("""SUCCESS("success", 200, true,"""))
        assertTrue(file.content.contains("""SERVER_ERROR("serverError", 500, false, null)"""))
    }

    @Test
    fun `deduplicates normalized endpoint and variant names`() {
        val collisionCatalog = ExportCatalog(
            projectName = "Collisions",
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

        val options = ExportOptions(languages = setOf(ExportLanguage.KOTLIN))
        val file = KotlinExporter().generate(collisionCatalog, options)

        assertTrue(file.content.contains("object FooBar {"))
        assertTrue(file.content.contains("object FooBar2 {"))
        assertTrue(file.content.contains("""SERVER_ERROR("serverError", 500, false, null)"""))
        assertTrue(file.content.contains("""SERVER_ERROR_2("server_error", 502, false, null)"""))
    }

    @Test
    fun `sanitizes user descriptions before writing comments`() {
        val commentCatalog = ExportCatalog(
            projectName = "Comments",
            endpoints = listOf(
                ExportEndpoint(
                    referenceName = "dangerous",
                    method = "GET",
                    path = "/danger",
                    description = "Endpoint */ comment\nSecond line",
                    variants = listOf(
                        ExportVariant(
                            referenceName = "dangerousVariant",
                            name = "Dangerous Variant",
                            status = 200,
                            isDefault = true,
                            description = "Variant */ comment",
                        ),
                    ),
                ),
            ),
        )

        val options = ExportOptions(languages = setOf(ExportLanguage.KOTLIN))
        val file = KotlinExporter().generate(commentCatalog, options)

        assertTrue(file.content.contains("Endpoint * / comment"))
        assertTrue(file.content.contains("Second line"))
        assertTrue(file.content.contains("Variant * / comment"))
        assertFalse(file.content.contains("Endpoint */ comment"))
    }
}
