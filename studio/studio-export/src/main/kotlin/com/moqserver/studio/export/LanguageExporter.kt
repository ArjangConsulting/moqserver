package com.moqserver.studio.export

import com.moqserver.studio.export.lang.java.JavaExporter
import com.moqserver.studio.export.lang.javascript.JavaScriptExporter
import com.moqserver.studio.export.lang.kotlin.KotlinExporter
import com.moqserver.studio.export.lang.swift.SwiftExporter

/**
 * Generates a [GeneratedFile] for a specific language from the export catalog.
 */
interface LanguageExporter {
    val language: ExportLanguage
    fun generate(catalog: ExportCatalog, options: ExportOptions): GeneratedFile
}

/**
 * Registry of built-in language exporters.
 */
object ExportRegistry {

    private val exporters: List<LanguageExporter> = listOf(
        KotlinExporter(),
        JavaExporter(),
        JavaScriptExporter(),
        SwiftExporter(),
    )

    fun exporterFor(language: ExportLanguage): LanguageExporter =
        exporters.first { it.language == language }

    fun generate(catalog: ExportCatalog, options: ExportOptions): List<GeneratedFile> =
        options.languages
            .sortedBy { it.ordinal }
            .map { language -> exporterFor(language).generate(catalog, options) }
}
