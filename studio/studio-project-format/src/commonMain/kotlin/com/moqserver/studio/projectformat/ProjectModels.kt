package com.moqserver.studio.projectformat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A loaded .moqproj project — the aggregate root for the project format. */
data class MoqProject(
    val manifest: ProjectManifest,
    val endpoints: List<EndpointDocument>,
    val projectPath: String,
)

/** The project manifest, representing the contents of project.yml. */
@Serializable
data class ProjectManifest(
    val version: String = "1",
    val name: String,
    val description: String? = null,
    val defaults: ProjectDefaults,
    @SerialName("global_rules")
    val globalRules: GlobalRules? = null,
)

/** Default settings for the project, applied to all endpoints unless overridden. */
@Serializable
data class ProjectDefaults(
    @SerialName("delay_ms")
    val delayMs: Int = 0,
    val auth: ProjectAuthConfig,
    val network: NetworkBehavior,
)

/** Auth configuration in the .moqproj format. */
@Serializable
data class ProjectAuthConfig(
    val type: AuthType,
    val verify: Boolean,
    @SerialName("header_name")
    val headerName: String? = null,
)

@Serializable
enum class AuthType {
    @SerialName("none") NONE,
    @SerialName("bearer") BEARER,
    @SerialName("basic") BASIC,
    @SerialName("api-key") API_KEY,
    @SerialName("header") HEADER,
}

/** Network simulation configuration in the .moqproj format. */
@Serializable
data class NetworkBehavior(
    @SerialName("latency_ms")
    val latencyMs: Int? = null,
    @SerialName("jitter_ms")
    val jitterMs: Int? = null,
    @SerialName("packet_loss_percent")
    val packetLossPercent: Double? = null,
)

/** Project-level global request rules. */
@Serializable
data class GlobalRules(
    @SerialName("required_headers")
    val requiredHeaders: List<RuleMatcher>? = null,
    @SerialName("verify_cookies")
    val verifyCookies: Boolean? = null,
)

/** An endpoint definition in the .moqproj format, representing one YAML file in endpoints/. */
@Serializable
data class EndpointDocument(
    val id: String,
    val alias: String? = null,
    val method: String,
    val path: String,
    val tags: List<String>? = null,
    val auth: ProjectAuthConfig? = null,
    @SerialName("request_rules")
    val requestRules: RequestRules? = null,
    val operation: EndpointOperation? = null,
    val network: NetworkBehavior? = null,
    val variants: List<ProjectVariant>,
)

/** A response variant in the .moqproj format. */
@Serializable
data class ProjectVariant(
    val name: String,
    @SerialName("default")
    val isDefault: Boolean? = null,
    val status: Int,
    val headers: Map<String, String>? = null,
    val body: YamlValue? = null,
    @SerialName("body_file")
    val bodyFile: String? = null,
    @SerialName("delay_ms")
    val delayMs: Int? = null,
)

/** Request validation rules for an endpoint in the .moqproj format. */
@Serializable
data class RequestRules(
    val headers: List<RuleMatcher>? = null,
    @SerialName("verify_cookies")
    val verifyCookies: Boolean? = null,
    @SerialName("query_params")
    val queryParams: List<RuleMatcher>? = null,
    val cookies: List<RuleMatcher>? = null,
)

/** Match type for a request validation rule. */
@Serializable
enum class MatchType {
    @SerialName("require") REQUIRE,
    @SerialName("equal_to") EQUAL_TO,
    @SerialName("not_equal_to") NOT_EQUAL_TO,
    @SerialName("contains") CONTAINS,
    @SerialName("not_contains") NOT_CONTAINS,
    @SerialName("begins_with") BEGINS_WITH,
    @SerialName("ends_with") ENDS_WITH,
    @SerialName("gt") GT,
    @SerialName("gte") GTE,
    @SerialName("lt") LT,
    @SerialName("lte") LTE,
}

/** A request validation rule for matching headers, cookies, or query parameters. */
@Serializable
data class RuleMatcher(
    val name: String,
    val match: String? = null,
    val required: Boolean? = null,
    @SerialName("match_type")
    val matchType: MatchType? = null,
)

/** GraphQL operation matching configuration. */
@Serializable
data class EndpointOperation(
    val type: OperationType,
    val name: String? = null,
    val document: String? = null,
)

@Serializable
enum class OperationType {
    @SerialName("query") QUERY,
    @SerialName("mutation") MUTATION,
    @SerialName("subscription") SUBSCRIPTION,
}
