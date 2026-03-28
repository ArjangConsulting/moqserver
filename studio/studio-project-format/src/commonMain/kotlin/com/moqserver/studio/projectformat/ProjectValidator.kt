package com.moqserver.studio.projectformat

data class ValidationDiagnostic(
    val severity: Severity,
    val message: String,
    val file: String? = null,
    val field: String? = null,
    val endpointId: String? = null,
) {
    enum class Severity { ERROR, WARNING }

    override fun toString(): String = buildString {
        append("[${severity.name.lowercase()}]")
        file?.let { append(" $it") }
        field?.let { append(" ($it)") }
        append(" $message")
    }
}

class ProjectValidator(
    private val fixtureExists: (projectPath: String, bodyFile: String) -> Boolean = { _, _ -> true },
) {
    private val reservedPaths = setOf("/health", "/__admin/endpoints")
    private val validMethods = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
    private val idPattern = Regex("^[a-z0-9][a-z0-9-]*$")

    fun validate(project: MoqProject): List<ValidationDiagnostic> {
        val diagnostics = mutableListOf<ValidationDiagnostic>()

        if (project.manifest.version != "1") {
            diagnostics += ValidationDiagnostic(
                severity = ValidationDiagnostic.Severity.ERROR,
                message = "Unsupported format version: \"${project.manifest.version}\". Expected \"1\".",
                file = "project.yml",
                field = "version",
            )
        }

        if (project.endpoints.isEmpty()) {
            diagnostics += ValidationDiagnostic(
                severity = ValidationDiagnostic.Severity.ERROR,
                message = "No endpoint files found in endpoints/.",
            )
        }

        val seenIds = mutableMapOf<String, String>()

        for (endpoint in project.endpoints) {
            val fileName = "endpoints/${endpoint.id}.yml"

            val existing = seenIds[endpoint.id]
            if (existing != null) {
                diagnostics += ValidationDiagnostic(
                    severity = ValidationDiagnostic.Severity.ERROR,
                    message = "Duplicate endpoint id \"${endpoint.id}\" (also in $existing).",
                    file = fileName,
                    field = "id",
                    endpointId = endpoint.id,
                )
            } else {
                seenIds[endpoint.id] = fileName
            }

            if (!idPattern.matches(endpoint.id)) {
                diagnostics += ValidationDiagnostic(
                    severity = ValidationDiagnostic.Severity.ERROR,
                    message = "Endpoint id \"${endpoint.id}\" must be lowercase alphanumeric with hyphens.",
                    file = fileName,
                    field = "id",
                    endpointId = endpoint.id,
                )
            }

            if (endpoint.path in reservedPaths) {
                diagnostics += ValidationDiagnostic(
                    severity = ValidationDiagnostic.Severity.ERROR,
                    message = "Path \"${endpoint.path}\" is reserved and cannot be used by mock endpoints.",
                    file = fileName,
                    field = "path",
                    endpointId = endpoint.id,
                )
            }

            if (!endpoint.path.startsWith("/")) {
                diagnostics += ValidationDiagnostic(
                    severity = ValidationDiagnostic.Severity.ERROR,
                    message = "Path must start with \"/\".",
                    file = fileName,
                    field = "path",
                    endpointId = endpoint.id,
                )
            }

            if (endpoint.method.uppercase() !in validMethods) {
                diagnostics += ValidationDiagnostic(
                    severity = ValidationDiagnostic.Severity.ERROR,
                    message = "Invalid HTTP method: \"${endpoint.method}\".",
                    file = fileName,
                    field = "method",
                    endpointId = endpoint.id,
                )
            }

            if (endpoint.variants.isEmpty()) {
                diagnostics += ValidationDiagnostic(
                    severity = ValidationDiagnostic.Severity.ERROR,
                    message = "Endpoint must have at least one variant.",
                    file = fileName,
                    field = "variants",
                    endpointId = endpoint.id,
                )
            }

            val defaultCount = endpoint.variants.count { it.isDefault == true }
            if (defaultCount > 1) {
                diagnostics += ValidationDiagnostic(
                    severity = ValidationDiagnostic.Severity.ERROR,
                    message = "Only one variant may be marked as default ($defaultCount found).",
                    file = fileName,
                    field = "variants",
                    endpointId = endpoint.id,
                )
            }

            val seenVariantNames = mutableSetOf<String>()
            for ((index, variant) in endpoint.variants.withIndex()) {
                val variantField = "variants[$index]"

                if (!seenVariantNames.add(variant.name)) {
                    diagnostics += ValidationDiagnostic(
                        severity = ValidationDiagnostic.Severity.ERROR,
                        message = "Duplicate variant name \"${variant.name}\".",
                        file = fileName,
                        field = "$variantField.name",
                        endpointId = endpoint.id,
                    )
                }

                if (variant.body != null && variant.bodyFile != null) {
                    diagnostics += ValidationDiagnostic(
                        severity = ValidationDiagnostic.Severity.ERROR,
                        message = "Variant \"${variant.name}\" defines both body and body_file. Only one is allowed.",
                        file = fileName,
                        field = variantField,
                        endpointId = endpoint.id,
                    )
                }

                variant.bodyFile?.let { bodyFile ->
                    if (!bodyFile.startsWith("fixtures/")) {
                        diagnostics += ValidationDiagnostic(
                            severity = ValidationDiagnostic.Severity.ERROR,
                            message = "body_file \"$bodyFile\" must start with \"fixtures/\".",
                            file = fileName,
                            field = "$variantField.body_file",
                            endpointId = endpoint.id,
                        )
                    } else if (!fixtureExists(project.projectPath, bodyFile)) {
                        diagnostics += ValidationDiagnostic(
                            severity = ValidationDiagnostic.Severity.ERROR,
                            message = "Fixture file not found: $bodyFile",
                            file = fileName,
                            field = "$variantField.body_file",
                            endpointId = endpoint.id,
                        )
                    }

                    if (".." in bodyFile) {
                        diagnostics += ValidationDiagnostic(
                            severity = ValidationDiagnostic.Severity.ERROR,
                            message = "body_file must not contain path traversal (..).",
                            file = fileName,
                            field = "$variantField.body_file",
                            endpointId = endpoint.id,
                        )
                    }
                }
            }

            endpoint.auth?.let { auth ->
                diagnostics += validateAuth(auth, fileName, "auth", endpoint.id)
            }

            if (endpoint.path == "/graphql" || endpoint.operation != null) {
                diagnostics += validateGraphQL(endpoint, fileName)
            }
        }

        diagnostics += validateAuth(project.manifest.defaults.auth, "project.yml", "defaults.auth", null)

        return diagnostics
    }

    private fun validateAuth(
        auth: ProjectAuthConfig,
        file: String,
        field: String,
        endpointId: String?,
    ): List<ValidationDiagnostic> {
        val diagnostics = mutableListOf<ValidationDiagnostic>()
        if (auth.type in listOf(AuthType.API_KEY, AuthType.HEADER)) {
            if (auth.headerName.isNullOrBlank()) {
                diagnostics += ValidationDiagnostic(
                    severity = ValidationDiagnostic.Severity.ERROR,
                    message = "header_name is required when auth type is \"${auth.type.name.lowercase().replace("_", "-")}\".",
                    file = file,
                    field = "$field.header_name",
                    endpointId = endpointId,
                )
            }
        }
        return diagnostics
    }

    private fun validateGraphQL(
        endpoint: EndpointDocument,
        fileName: String,
    ): List<ValidationDiagnostic> {
        val diagnostics = mutableListOf<ValidationDiagnostic>()

        if (endpoint.path == "/graphql" && endpoint.operation == null) {
            diagnostics += ValidationDiagnostic(
                severity = ValidationDiagnostic.Severity.ERROR,
                message = "GraphQL endpoints (path=/graphql) must define an operation.",
                file = fileName,
                field = "operation",
                endpointId = endpoint.id,
            )
        }

        endpoint.operation?.let { operation ->
            if (endpoint.path != "/graphql") {
                diagnostics += ValidationDiagnostic(
                    severity = ValidationDiagnostic.Severity.WARNING,
                    message = "Endpoint has an operation but path is not /graphql.",
                    file = fileName,
                    field = "path",
                    endpointId = endpoint.id,
                )
            }

            if (operation.name == null && operation.document == null) {
                diagnostics += ValidationDiagnostic(
                    severity = ValidationDiagnostic.Severity.ERROR,
                    message = "GraphQL operation must define at least one of \"name\" or \"document\".",
                    file = fileName,
                    field = "operation",
                    endpointId = endpoint.id,
                )
            }

            if (operation.document?.trim().isNullOrEmpty()) {
                if (operation.document != null) {
                    diagnostics += ValidationDiagnostic(
                        severity = ValidationDiagnostic.Severity.ERROR,
                        message = "GraphQL operation document must be non-empty after normalization.",
                        file = fileName,
                        field = "operation.document",
                        endpointId = endpoint.id,
                    )
                }
            }
        }

        return diagnostics
    }
}
