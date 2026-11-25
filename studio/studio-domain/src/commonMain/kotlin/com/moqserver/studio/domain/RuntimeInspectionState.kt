package com.moqserver.studio.domain

data class RuntimeRequest(
	val id: String,
	val method: String,
	val path: String,
	val status: Int,
	val variant: String?,
	val reason: String,
	val callNumber: Int?,
	/** The endpoint that served the request; `null` when none matched or the server refused it. */
	val endpoint: String? = null,
	val rejection: RuntimeRejection? = null,
	/** Captured headers (`serve --capture-request-headers`); credentials never carry a value. */
	val headers: List<RuntimeHeader> = emptyList(),
	/** Claims the `jwt-claims` add-on recorded. */
	val claims: Map<String, String> = emptyMap(),
	/** Captured query parameters in the order sent (`serve --capture-request-query`). */
	val query: List<RuntimeQueryParameter> = emptyList(),
) {
	/** No endpoint serves this path; a refused request is not unmatched. */
	val isUnmatched: Boolean get() = endpoint == null && rejection == null
}

/** Why the server refused a request itself: `kind` is auth, request_rule, session, call_count or no_variant. */
data class RuntimeRejection(val kind: String, val code: String, val message: String)

data class RuntimeHeader(
	val name: String,
	val value: String? = null,
	val redacted: Boolean = false,
	val scheme: String? = null,
	val fingerprint: String? = null,
	val cookieNames: List<String> = emptyList(),
)

/** A captured query parameter; a credential-like one keeps only [fingerprint]. */
data class RuntimeQueryParameter(
	val name: String,
	val value: String? = null,
	val redacted: Boolean = false,
	val fingerprint: String? = null,
)

/** What `GET /_admin/version` reported; [available] is false for a server that predates the route. */
data class RuntimeServerInfo(
	val available: Boolean,
	val version: String? = null,
	val commit: String? = null,
	val capabilities: List<String> = emptyList(),
)

data class RuntimeScenarioDefinition(val name: String, val overridesJson: String)

data class RuntimeInspectionState(
	val serverUrl: String = "http://127.0.0.1:8080",
	val bearerToken: String = "",
	val sessionId: String = "",
	val scenarioName: String = "",
	val scenarioOverrides: String = "{\"GET /users\": \"success\"}",
	val scenarios: List<RuntimeScenarioDefinition> = emptyList(),
	val requests: List<RuntimeRequest> = emptyList(),
	val serverInfo: RuntimeServerInfo? = null,
	val loading: Boolean = false,
	val error: String? = null,
)
