package com.moqserver.studio.projectformat

data class ValidationDiagnostic(
    val severity: Severity,
    val message: String,
    val file: String? = null,
    val field: String? = null,
    val endpointId: String? = null,
    val endpointLabel: String? = null,
    val variantName: String? = null,
) {
    enum class Severity { ERROR, WARNING }

    override fun toString(): String = buildString {
        append("[${severity.name.lowercase()}]")
        endpointLabel?.let { append(" $it") }
        variantName?.let { append(" ($it)") }
        file?.let { if (endpointLabel == null) append(" $it") }
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

        if (project.manifest.version != MoqProjectFormat.FORMAT_VERSION) {
            diagnostics += ValidationDiagnostic(
                severity = ValidationDiagnostic.Severity.ERROR,
                message = "Unsupported format version: \"${project.manifest.version}\". Expected \"${MoqProjectFormat.FORMAT_VERSION}\".",
                file = MoqProjectFormat.MANIFEST_FILE,
                field = "version",
            )
        }

        if (project.endpoints.isEmpty()) {
            diagnostics += ValidationDiagnostic(
                severity = ValidationDiagnostic.Severity.ERROR,
                message = "No endpoint files found in ${MoqProjectFormat.ENDPOINTS_DIR}/.",
            )
        }

        val seenIds = mutableMapOf<String, String>()
        val seenEndpointReferenceNames = mutableMapOf<String, String>()

        for (endpoint in project.endpoints) {
            val fileName = "${MoqProjectFormat.ENDPOINTS_DIR}/${endpoint.id}.yml"
            val endpointLabel = "${endpoint.method} ${endpoint.path}"

            val existing = seenIds[endpoint.id]
            if (existing != null) {
                diagnostics += ValidationDiagnostic(
                    severity = ValidationDiagnostic.Severity.ERROR,
                    message = "Duplicate endpoint id \"${endpoint.id}\" (also in $existing).",
                    file = fileName,
                    field = "id",
                    endpointId = endpoint.id,
                    endpointLabel = endpointLabel,
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
                    endpointLabel = endpointLabel,
                )
            }

            if (endpoint.referenceName.isBlank()) {
                diagnostics += ValidationDiagnostic(
                    severity = ValidationDiagnostic.Severity.ERROR,
                    message = "Endpoint reference_name is required.",
                    file = fileName,
                    field = "reference_name",
                    endpointId = endpoint.id,
                    endpointLabel = endpointLabel,
                )
            } else if (!isValidReferenceName(endpoint.referenceName)) {
                diagnostics += ValidationDiagnostic(
                    severity = ValidationDiagnostic.Severity.ERROR,
                    message = "Endpoint reference_name \"${endpoint.referenceName}\" must start with a letter or underscore and contain only letters, numbers, or underscores.",
                    file = fileName,
                    field = "reference_name",
                    endpointId = endpoint.id,
                    endpointLabel = endpointLabel,
                )
            } else {
                val existingReferenceNameFile = seenEndpointReferenceNames[endpoint.referenceName]
                if (existingReferenceNameFile != null) {
                    diagnostics += ValidationDiagnostic(
                        severity = ValidationDiagnostic.Severity.ERROR,
                        message = "Duplicate endpoint reference_name \"${endpoint.referenceName}\" (also in $existingReferenceNameFile).",
                        file = fileName,
                        field = "reference_name",
                        endpointId = endpoint.id,
                        endpointLabel = endpointLabel,
                    )
                } else {
                    seenEndpointReferenceNames[endpoint.referenceName] = fileName
                }
            }

            if (endpoint.path in reservedPaths) {
                diagnostics += ValidationDiagnostic(
                    severity = ValidationDiagnostic.Severity.ERROR,
                    message = "Path \"${endpoint.path}\" is reserved and cannot be used by mock endpoints.",
                    file = fileName,
                    field = "path",
                    endpointId = endpoint.id,
                    endpointLabel = endpointLabel,
                )
            }

            if (!endpoint.path.startsWith("/")) {
                diagnostics += ValidationDiagnostic(
                    severity = ValidationDiagnostic.Severity.ERROR,
                    message = "Path must start with \"/\".",
                    file = fileName,
                    field = "path",
                    endpointId = endpoint.id,
                    endpointLabel = endpointLabel,
                )
            }

            if (endpoint.method.uppercase() !in validMethods) {
                diagnostics += ValidationDiagnostic(
                    severity = ValidationDiagnostic.Severity.ERROR,
                    message = "Invalid HTTP method: \"${endpoint.method}\".",
                    file = fileName,
                    field = "method",
                    endpointId = endpoint.id,
                    endpointLabel = endpointLabel,
                )
            }

            if (endpoint.variants.isEmpty()) {
                diagnostics += ValidationDiagnostic(
                    severity = ValidationDiagnostic.Severity.ERROR,
                    message = "Endpoint must have at least one variant.",
                    file = fileName,
                    field = "variants",
                    endpointId = endpoint.id,
                    endpointLabel = endpointLabel,
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
                    endpointLabel = endpointLabel,
                )
            }

            val seenVariantNames = mutableSetOf<String>()
            val seenVariantReferenceNames = mutableSetOf<String>()
            for ((index, variant) in endpoint.variants.withIndex()) {
                val variantField = "variants[$index]"

                if (!seenVariantNames.add(variant.name)) {
                    diagnostics += ValidationDiagnostic(
                        severity = ValidationDiagnostic.Severity.ERROR,
                        message = "Duplicate variant name \"${variant.name}\".",
                        file = fileName,
                        field = "$variantField.name",
                        endpointId = endpoint.id,
                        endpointLabel = endpointLabel,
                        variantName = variant.name,
                    )
                }

                if (variant.referenceName.isBlank()) {
                    diagnostics += ValidationDiagnostic(
                        severity = ValidationDiagnostic.Severity.ERROR,
                        message = "Variant reference_name is required.",
                        file = fileName,
                        field = "$variantField.reference_name",
                        endpointId = endpoint.id,
                        endpointLabel = endpointLabel,
                        variantName = variant.name,
                    )
                } else if (!isValidReferenceName(variant.referenceName)) {
                    diagnostics += ValidationDiagnostic(
                        severity = ValidationDiagnostic.Severity.ERROR,
                        message = "Variant reference_name \"${variant.referenceName}\" must start with a letter or underscore and contain only letters, numbers, or underscores.",
                        file = fileName,
                        field = "$variantField.reference_name",
                        endpointId = endpoint.id,
                        endpointLabel = endpointLabel,
                        variantName = variant.name,
                    )
                } else if (!seenVariantReferenceNames.add(variant.referenceName)) {
                    diagnostics += ValidationDiagnostic(
                        severity = ValidationDiagnostic.Severity.ERROR,
                        message = "Duplicate variant reference_name \"${variant.referenceName}\".",
                        file = fileName,
                        field = "$variantField.reference_name",
                        endpointId = endpoint.id,
                        endpointLabel = endpointLabel,
                        variantName = variant.name,
                    )
                }

                if (variant.body != null && variant.bodyFile != null) {
                    diagnostics += ValidationDiagnostic(
                        severity = ValidationDiagnostic.Severity.ERROR,
                        message = "Variant \"${variant.name}\" defines both body and body_file. Only one is allowed.",
                        file = fileName,
                        field = variantField,
                        endpointId = endpoint.id,
                        endpointLabel = endpointLabel,
                        variantName = variant.name,
                    )
                }

                variant.bodyFile?.let { bodyFile ->
                    if (!bodyFile.startsWith("${MoqProjectFormat.FIXTURES_DIR}/")) {
                        diagnostics += ValidationDiagnostic(
                            severity = ValidationDiagnostic.Severity.ERROR,
                            message = "body_file \"$bodyFile\" must start with \"${MoqProjectFormat.FIXTURES_DIR}/\".",
                            file = fileName,
                            field = "$variantField.body_file",
                            endpointId = endpoint.id,
                            endpointLabel = endpointLabel,
                            variantName = variant.name,
                        )
                    } else if (!fixtureExists(project.projectPath, bodyFile)) {
                        diagnostics += ValidationDiagnostic(
                            severity = ValidationDiagnostic.Severity.ERROR,
                            message = "Fixture file not found: $bodyFile",
                            file = fileName,
                            field = "$variantField.body_file",
                            endpointId = endpoint.id,
                            endpointLabel = endpointLabel,
                            variantName = variant.name,
                        )
                    }

                    if (".." in bodyFile) {
                        diagnostics += ValidationDiagnostic(
                            severity = ValidationDiagnostic.Severity.ERROR,
                            message = "body_file must not contain path traversal (..).",
                            file = fileName,
                            field = "$variantField.body_file",
                            endpointId = endpoint.id,
                            endpointLabel = endpointLabel,
                            variantName = variant.name,
                        )
                    }
                }
            }

            endpoint.auth?.let { auth ->
                diagnostics += validateAuth(auth, fileName, "auth", endpoint.id, endpointLabel)
            }

            if (endpoint.path == MoqProjectFormat.GRAPHQL_PATH || endpoint.operation != null) {
                diagnostics += validateGraphQL(endpoint, fileName, endpointLabel)
            }
        }

        diagnostics += validateAuth(project.manifest.defaults.auth, MoqProjectFormat.MANIFEST_FILE, "defaults.auth", null)

        return diagnostics
    }

    private fun validateAuth(
        auth: ProjectAuthConfig,
        file: String,
        field: String,
        endpointId: String?,
        endpointLabel: String? = null,
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
                    endpointLabel = endpointLabel,
                )
            }
        }
        return diagnostics
    }

    private fun validateGraphQL(
        endpoint: EndpointDocument,
        fileName: String,
        endpointLabel: String,
    ): List<ValidationDiagnostic> {
        val diagnostics = mutableListOf<ValidationDiagnostic>()

        if (endpoint.path == MoqProjectFormat.GRAPHQL_PATH && endpoint.operation == null) {
            diagnostics += ValidationDiagnostic(
                severity = ValidationDiagnostic.Severity.ERROR,
                message = "GraphQL endpoints (path=${MoqProjectFormat.GRAPHQL_PATH}) must define an operation.",
                file = fileName,
                field = "operation",
                endpointId = endpoint.id,
                endpointLabel = endpointLabel,
            )
        }

        endpoint.operation?.let { operation ->
            if (endpoint.path != MoqProjectFormat.GRAPHQL_PATH) {
                diagnostics += ValidationDiagnostic(
                    severity = ValidationDiagnostic.Severity.WARNING,
                    message = "Endpoint has an operation but path is not ${MoqProjectFormat.GRAPHQL_PATH}.",
                    file = fileName,
                    field = "path",
                    endpointId = endpoint.id,
                    endpointLabel = endpointLabel,
                )
            }

            if (operation.name == null && operation.document == null) {
                diagnostics += ValidationDiagnostic(
                    severity = ValidationDiagnostic.Severity.ERROR,
                    message = "GraphQL operation must define at least one of \"name\" or \"document\".",
                    file = fileName,
                    field = "operation",
                    endpointId = endpoint.id,
                    endpointLabel = endpointLabel,
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
                        endpointLabel = endpointLabel,
                    )
                }
            }
        }

        return diagnostics
    }
}
