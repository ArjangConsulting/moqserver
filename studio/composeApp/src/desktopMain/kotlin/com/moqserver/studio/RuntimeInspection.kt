package com.moqserver.studio

import com.moqserver.studio.domain.RuntimeInspectionState
import com.moqserver.studio.domain.RuntimeRequest
import com.moqserver.studio.domain.RuntimeScenarioDefinition
import com.moqserver.studio.domain.StudioRootViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal suspend fun inspectRuntime(
	viewModel: StudioRootViewModel,
	action: RuntimeAction,
	scenario: String? = null,
	ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
	val initial = viewModel.state.value.runtime
	if (initial.loading) return
	viewModel.runtimeUpdated(initial.copy(loading = true, error = null))
	try {
		val updated = runOnDispatcher(ioDispatcher) { RuntimeConnection(initial).use { it.perform(action, scenario) } }
		viewModel.runtimeUpdated(updated.copy(loading = false))
	} catch (error: CancellationException) {
		viewModel.runtimeUpdated(initial.copy(loading = false))
		throw error
	} catch (error: Exception) {
		viewModel.runtimeUpdated(initial.copy(loading = false, error = error.message ?: "Runtime request failed"))
	}
}

private suspend fun <T> runOnDispatcher(dispatcher: CoroutineDispatcher, block: suspend () -> T): T =
	withContext(dispatcher) { block() }

private class RuntimeConnection(private var state: RuntimeInspectionState) : AutoCloseable {
	private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

	override fun close() { client.close() }

	fun perform(action: RuntimeAction, scenario: String?): RuntimeInspectionState {
		when (action) {
			RuntimeAction.REFRESH -> Unit
			RuntimeAction.SAVE_SCENARIO -> {
				val overrides = Json.parseToJsonElement(state.scenarioOverrides).jsonObject
				require(overrides.values.all { it is JsonPrimitive && it.isString }) { "Variant values must be strings." }
				request(
					"PUT",
					"scenarios",
					buildJsonObject {
					put("name", state.scenarioName)
					put("overrides", overrides)
				}.toString(),
				)
			}
			RuntimeAction.ACTIVATE_SCENARIO -> request("PUT", "scenario", buildJsonObject { put("name", scenario) }.toString())
			RuntimeAction.DELETE_SCENARIO -> request("DELETE", "scenarios", buildJsonObject { put("name", scenario) }.toString())
			RuntimeAction.RESET -> request("DELETE", "state")
			RuntimeAction.CLEAR_HISTORY -> request("DELETE", "requests")
			RuntimeAction.CREATE_SESSION -> {
				val result = Json.parseToJsonElement(request("POST", "sessions")).jsonObject
				state = state.copy(sessionId = result.getValue("id").jsonPrimitive.content, scenarios = emptyList(), requests = emptyList())
			}
			RuntimeAction.RELEASE_SESSION -> {
				request("DELETE", "sessions/${state.sessionId}")
				state = state.copy(sessionId = "", scenarios = emptyList(), requests = emptyList())
			}
		}
		return try {
			val scenarios = Json.parseToJsonElement(request("GET", "scenarios")).jsonArray.map {
				RuntimeScenarioDefinition(
					it.jsonObject.getValue("name").jsonPrimitive.content,
					it.jsonObject.getValue("overrides").toString(),
				)
			}
			val requests = Json.parseToJsonElement(
				request("GET", "requests"),
			).jsonArray.map { decodeRuntimeRequest(it.jsonObject) }
			state.copy(scenarios = scenarios, requests = requests, error = null)
		} catch (error: Exception) {
			state.copy(error = error.message ?: "Could not refresh runtime state")
		}
	}

	private fun request(method: String, path: String, body: String? = null): String {
		val base = URI(state.serverUrl.trimEnd('/') + "/")
		require(base.scheme in setOf("http", "https") && base.host != null) { "Enter an HTTP or HTTPS server URL." }
		val builder = HttpRequest.newBuilder(base.resolve("_admin/$path")).timeout(Duration.ofSeconds(10))
		if (state.bearerToken.isNotBlank()) builder.header("Authorization", "Bearer ${state.bearerToken}")
		if (state.sessionId.isNotBlank()) builder.header("X-Mock-Session", state.sessionId)
		if (body != null) builder.header("Content-Type", "application/json")
		val request = builder.method(
			method,
			body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody(),
		).build()
		val response = client.send(request, HttpResponse.BodyHandlers.ofString())
		check(response.statusCode() in 200..299) { "Server returned ${response.statusCode()}: ${response.body().take(1000)}" }
		return response.body()
	}
}

internal fun decodeRuntimeRequest(value: JsonObject): RuntimeRequest = RuntimeRequest(
	id = value.getValue("id").jsonPrimitive.content,
	method = value.getValue("method").jsonPrimitive.content,
	path = value.getValue("path").jsonPrimitive.content,
	status = value.getValue("status").jsonPrimitive.int,
	variant = value["variant"]?.jsonPrimitive?.content,
	reason = value.getValue("reason").jsonPrimitive.content,
	callNumber = value["callNumber"]?.jsonPrimitive?.intOrNull,
)
