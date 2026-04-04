package com.moqserver.studio.export

import kotlin.test.Test
import kotlin.test.assertEquals

class ExportRegistryTest {

    @Test
    fun `generates files for all selected languages`() {
        val catalog = ExportCatalog(
            projectName = "Multi",
            endpoints = listOf(
                ExportEndpoint(
                    referenceName = "test",
                    method = "GET",
                    path = "/test",
                    description = null,
                    variants = listOf(ExportVariant("ok", "ok", 200, true, null)),
                ),
            ),
        )
        val options = ExportOptions(
            languages = setOf(ExportLanguage.KOTLIN, ExportLanguage.SWIFT),
        )

        val files = ExportRegistry.generate(catalog, options)

        assertEquals(2, files.size)
        assertEquals("MoqAPIs.kt", files[0].fileName)
        assertEquals("MoqAPIs.swift", files[1].fileName)
    }

    @Test
    fun `generates empty list for no languages`() {
        val catalog = ExportCatalog(projectName = "Empty", endpoints = emptyList())
        val options = ExportOptions(languages = emptySet())

        val files = ExportRegistry.generate(catalog, options)

        assertEquals(0, files.size)
    }
}
