package com.moqserver.studio.projectformat.format

import com.moqserver.studio.projectformat.AuthType
import com.moqserver.studio.projectformat.RuleMatcher
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shape for `import.parseHar`/`import.parseOpenapi`'s result — mirrors
 * `server/Sources/MoqImport/ParsedSpec.swift` field for field, including its snake_case keys.
 *
 * Deliberately not `studio-domain`'s own `ParsedSpec`/`ParsedEndpoint`/`ParsedResponse`: those
 * types live in `studio-domain`, which depends on `studio-project-format` (where `FormatClient`
 * lives), so this module can't return them without a circular dependency. `RemoteImportParsing.kt`
 * (in `studio-domain`, which can see both) maps this shape onto the real one — the intermediate
 * layer costs three small classes, not a module reshuffle.
 */
@Serializable
data class RemoteParsedSpec(
    val title: String,
    val version: String,
    val endpoints: List<RemoteParsedEndpoint>,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class RemoteParsedEndpoint(
    val method: String,
    val path: String,
    val alias: String? = null,
    val description: String? = null,
    @SerialName("reference_name")
    val referenceName: String? = null,
    val tags: List<String> = emptyList(),
    val responses: List<RemoteParsedResponse>,
    @SerialName("auth_type")
    val authType: AuthType = AuthType.NONE,
    @SerialName("auth_header_name")
    val authHeaderName: String? = null,
    @SerialName("query_parameters")
    val queryParameters: List<RuleMatcher> = emptyList(),
    @SerialName("required_query_parameters")
    val requiredQueryParameters: List<String> = emptyList(),
    @SerialName("required_headers")
    val requiredHeaders: List<String> = emptyList(),
    val cookies: List<RuleMatcher> = emptyList(),
    @SerialName("requires_body")
    val requiresBody: Boolean = false,
    @SerialName("accepted_content_types")
    val acceptedContentTypes: List<String> = emptyList(),
)

@Serializable
data class RemoteParsedResponse(
    val name: String,
    @SerialName("status_code")
    val statusCode: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    @SerialName("is_base64")
    val isBase64: Boolean = false,
    val description: String? = null,
)

/** Result of `import.parseOpenapi`: the parsed spec plus the source it actually came from. */
@Serializable
data class RemoteParsedOpenAPIResult(
    val spec: RemoteParsedSpec,
    @SerialName("resolved_source")
    val resolvedSource: String,
)
