package com.moqserver.studio.projectformat.format

import com.moqserver.studio.projectformat.EndpointOperation
import com.moqserver.studio.projectformat.NetworkBehavior
import com.moqserver.studio.projectformat.ProjectAuthConfig
import com.moqserver.studio.projectformat.RequestRules
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Transport-only DTOs for `moq-format`'s JSON-RPC surface. Not `.moqproj` document types (those
 * are schema-generated in `ProjectModels.generated.kt`) — these mirror
 * `server/Sources/MoqService/Payloads.swift`, field for field, because that's the contract this
 * client decodes against.
 */
@Serializable
data class ProjectDescription(
    val name: String,
    val description: String? = null,
    val path: String,
    @SerialName("endpoint_count")
    val endpointCount: Int,
    val dirty: Boolean,
)

@Serializable
data class EndpointSummary(
    val id: String,
    val method: String,
    val path: String,
    val alias: String? = null,
    @SerialName("reference_name")
    val referenceName: String,
    @SerialName("variant_count")
    val variantCount: Int,
    @SerialName("default_variant")
    val defaultVariant: String? = null,
)

@Serializable
data class SuggestedEndpointIdentity(
    val id: String,
    val alias: String,
    @SerialName("reference_name")
    val referenceName: String,
)

@Serializable
data class DiagnosticPayload(
    val severity: String,
    val code: String? = null,
    val message: String,
    val file: String? = null,
    val field: String? = null,
    @SerialName("endpoint_id")
    val endpointId: String? = null,
    @SerialName("variant_name")
    val variantName: String? = null,
)

@Serializable
data class ValidationResult(
    @SerialName("error_count")
    val errorCount: Int,
    @SerialName("warning_count")
    val warningCount: Int,
    val diagnostics: List<DiagnosticPayload>,
)

@Serializable
data class ImportSummary(
    @SerialName("project_endpoint_count")
    val projectEndpointCount: Int,
    @SerialName("new_endpoint_count")
    val newEndpointCount: Int,
    val warnings: List<String>,
)

/**
 * Auth for a URL-sourced OpenAPI import. Mirrors `ImportAuthInput` in the Swift core: exactly one
 * of the three should be set — the server picks the first non-null field, matching Swift's own
 * `resolved` precedence (bearer, then basic, then header).
 */
@Serializable
data class ImportAuthInput(
    val bearer: String? = null,
    val basic: BasicAuthInput? = null,
    val header: HeaderAuthInput? = null,
) {
    @Serializable
    data class BasicAuthInput(val username: String, val password: String)

    @Serializable
    data class HeaderAuthInput(val name: String, val value: String)
}

/**
 * Input for `endpoint.upsert`. Mirrors `EndpointUpsertInput` in the Swift core: metadata only,
 * never variants — those go through `upsertVariant`/`removeVariant`, and are preserved
 * server-side across a metadata update.
 */
@Serializable
data class EndpointUpsertInput(
    val id: String,
    val alias: String? = null,
    val description: String? = null,
    @SerialName("reference_name")
    val referenceName: String? = null,
    val method: String,
    val path: String,
    val tags: List<String>? = null,
    val auth: ProjectAuthConfig? = null,
    @SerialName("request_rules")
    val requestRules: RequestRules? = null,
    val operation: EndpointOperation? = null,
    val network: NetworkBehavior? = null,
)
