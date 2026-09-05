package com.moqserver.studio.domain

data class RuntimeRequest(
	val id: String,
	val method: String,
	val path: String,
	val status: Int,
	val variant: String?,
	val reason: String,
	val callNumber: Int?,
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
	val loading: Boolean = false,
	val error: String? = null,
)
