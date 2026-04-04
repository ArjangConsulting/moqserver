package com.moqserver.studio.export.lang.javascript

import com.moqserver.studio.export.ExportCatalog
import com.moqserver.studio.export.ExportEndpoint
import com.moqserver.studio.export.ExportLanguage
import com.moqserver.studio.export.ExportOptions
import com.moqserver.studio.export.ExportVariant
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `generates variant entries`() {
        val options = ExportOptions(languages = setOf(ExportLanguage.JAVASCRIPT))
        val file = JavaScriptExporter().generate(catalog, options)

        assertTrue(file.content.contains("SUCCESS: Object.freeze({"))
        assertTrue(file.content.contains("""referenceName: "success""""))
        assertTrue(file.content.contains("status: 200"))
        assertTrue(file.content.contains("isDefault: true"))
    }
}
