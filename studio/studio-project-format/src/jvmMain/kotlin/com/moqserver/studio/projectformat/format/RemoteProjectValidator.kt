package com.moqserver.studio.projectformat.format

import com.moqserver.studio.logging.loggerFor
import com.moqserver.studio.projectformat.MoqProject
import com.moqserver.studio.projectformat.ValidationDiagnostic

/**
 * Validates a project through `moq-format` instead of a local Kotlin implementation. This is the
 * whole point of the cutover: Swift's `ProjectValidator` is the only validator left, so this
 * class's job is purely translating `moq-format`'s wire diagnostics into the `ValidationDiagnostic`
 * shape the rest of Studio already renders — not re-implementing any rule.
 */
class RemoteProjectValidator(private val client: FormatClient) {
    private val logger = loggerFor<RemoteProjectValidator>()

    suspend fun validate(project: MoqProject): List<ValidationDiagnostic> {
        val result = client.validateProject(project)
        logger.debug(
            "moq-format validated '{}': {} error(s), {} warning(s)",
            project.manifest.name,
            result.errorCount,
            result.warningCount,
        )
        val endpointsById = project.endpoints.associateBy { it.id }
        return result.diagnostics.map { diagnostic ->
            val endpoint = diagnostic.endpointId?.let { endpointsById[it] }
            ValidationDiagnostic(
                severity = when (diagnostic.severity) {
                    "error" -> ValidationDiagnostic.Severity.ERROR
                    else -> ValidationDiagnostic.Severity.WARNING
                },
                message = diagnostic.message,
                code = diagnostic.code,
                file = diagnostic.file,
                field = diagnostic.field,
                endpointId = diagnostic.endpointId,
                endpointLabel = endpoint?.let { "${it.method} ${it.path}" },
                variantName = diagnostic.variantName,
            )
        }
    }
}
