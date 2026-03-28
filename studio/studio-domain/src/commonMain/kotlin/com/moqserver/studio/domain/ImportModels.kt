package com.moqserver.studio.domain

import com.moqserver.studio.projectformat.AuthType
import com.moqserver.studio.projectformat.RuleMatcher
import kotlinx.serialization.Serializable

/** A fully parsed API spec — intermediate model before conversion to MoqProject. */
data class ParsedSpec(
    val title: String,
    val version: String,
    val endpoints: List<ParsedEndpoint>,
    val warnings: List<String> = emptyList(),
)

/** A parsed endpoint from an API spec. */
data class ParsedEndpoint(
    val method: String,
    val path: String,
    val alias: String? = null,
    val description: String? = null,
    val referenceName: String? = null,
    val responses: List<ParsedResponse>,
    val authType: AuthType = AuthType.NONE,
    val authHeaderName: String? = null,
    val requiredQueryParameters: List<String> = emptyList(),
    val requiredHeaders: List<String> = emptyList(),
    val cookies: List<RuleMatcher> = emptyList(),
    val requiresBody: Boolean = false,
    val acceptedContentTypes: List<String> = emptyList(),
)

/** A parsed response variant from an API spec. */
data class ParsedResponse(
    val name: String,
    val statusCode: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

/** Import source type. */
enum class ImportSourceType {
    OPENAPI,
    HAR,
}

/** State for a single endpoint in the import review screen. */
data class ImportEndpointEntry(
    val endpoint: ParsedEndpoint,
    val accepted: Boolean = true,
)

/** State for the import workflow. */
data class ImportState(
    val source: ImportSourceType,
    val sourceFileName: String,
    val parsedSpec: ParsedSpec,
    val entries: List<ImportEndpointEntry>,
    val projectName: String,
) {
    val acceptedCount: Int get() = entries.count { it.accepted }
    val totalCount: Int get() = entries.size
    val warnings: List<String> get() = parsedSpec.warnings
}
