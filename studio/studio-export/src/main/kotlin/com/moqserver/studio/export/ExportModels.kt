package com.moqserver.studio.export

/**
 * Supported export languages for reference code generation.
 */
enum class ExportLanguage(val displayName: String, val fileExtension: String) {
    KOTLIN("Kotlin", "kt"),
    JAVA("Java", "java"),
    JAVASCRIPT("JavaScript", "js"),
    SWIFT("Swift", "swift"),
}

/**
 * Configuration for a code generation export run.
 *
 * @property languages The set of languages to generate.
 * @property includeApiDescriptions Whether to include endpoint (API) descriptions in generated docs.
 * @property includeVariantDescriptions Whether to include variant descriptions in generated docs.
 * @property kotlinPackage Optional package declaration for Kotlin output.
 * @property javaPackage Optional package declaration for Java output.
 */
data class ExportOptions(
    val languages: Set<ExportLanguage>,
    val includeApiDescriptions: Boolean = true,
    val includeVariantDescriptions: Boolean = true,
    val kotlinPackage: String? = null,
    val javaPackage: String? = null,
)

/**
 * A stable, sorted intermediate catalog built from a [com.moqserver.studio.projectformat.MoqProject].
 * Endpoints are sorted alphabetically by reference name. Variants are nested under their endpoint.
 */
data class ExportCatalog(
    val projectName: String,
    val endpoints: List<ExportEndpoint>,
)

/**
 * An endpoint entry in the export catalog, sorted by [referenceName].
 */
data class ExportEndpoint(
    val referenceName: String,
    val method: String,
    val path: String,
    val description: String?,
    val variants: List<ExportVariant>,
)

/**
 * A variant entry nested under its parent endpoint.
 */
data class ExportVariant(
    val referenceName: String,
    val name: String,
    val status: Int,
    val isDefault: Boolean,
    val description: String?,
)

/**
 * A generated output file ready to be written to disk.
 */
data class GeneratedFile(
    val fileName: String,
    val content: String,
)
