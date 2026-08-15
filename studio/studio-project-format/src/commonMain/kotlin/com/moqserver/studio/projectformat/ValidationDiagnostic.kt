package com.moqserver.studio.projectformat

/**
 * A validation finding, whatever produced it. Structural validation (format version, id
 * patterns, reserved paths, cross-endpoint rules, fixture existence) comes from `moq-format` — see
 * `format.RemoteProjectValidator` — but a few immediate-editing-feedback diagnostics
 * (`StudioRootViewModel.duplicateDefaultVariantDiagnostic`) are still produced locally, since they
 * flag an in-progress edit before it's even a candidate for a full revalidate. Both use this type
 * so the UI (`ValidationPanel`) doesn't need to know which source a diagnostic came from.
 */
data class ValidationDiagnostic(
    val severity: Severity,
    val message: String,
    val code: String? = null,
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
