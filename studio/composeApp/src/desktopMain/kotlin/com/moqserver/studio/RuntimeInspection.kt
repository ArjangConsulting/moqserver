package com.moqserver.studio

import com.moqserver.studio.domain.RuntimeHeader
import com.moqserver.studio.domain.RuntimeInspectionState
import com.moqserver.studio.domain.RuntimeQueryParameter
import com.moqserver.studio.domain.RuntimeRejection
import com.moqserver.studio.domain.RuntimeRequest
import com.moqserver.studio.domain.RuntimeScenarioDefinition
import com.moqserver.studio.domain.RuntimeServerInfo
import com.moqserver.studio.domain.StudioRootViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
			state.copy(scenarios = scenarios, requests = requests, serverInfo = fetchServerInfo(), error = null)
		} catch (error: Exception) {
			state.copy(error = error.message ?: "Could not refresh runtime state")
		}
	}

	private fun fetchServerInfo(): RuntimeServerInfo = try {
		decodeServerInfo(Json.parseToJsonElement(request("GET", "version")).jsonObject)
	} catch (error: RuntimeStatusException) {
		if (error.status != NOT_FOUND) throw error
		RuntimeServerInfo(available = false)
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
		if (response.statusCode() !in 200..299) throw RuntimeStatusException(response.statusCode(), response.body())
		return response.body()
	}
}

private const val NOT_FOUND = 404

private class RuntimeStatusException(val status: Int, body: String) :
	IllegalStateException("Server returned $status: ${body.take(1000)}")

internal fun decodeServerInfo(value: JsonObject): RuntimeServerInfo = RuntimeServerInfo(
	available = true,
	version = value["version"]?.stringOrNull(),
	commit = value["commit"]?.stringOrNull(),
	capabilities = value["capabilities"]?.jsonArray?.mapNotNull { it.stringOrNull() }.orEmpty(),
)

internal fun decodeRuntimeRequest(value: JsonObject): RuntimeRequest = RuntimeRequest(
	id = value.getValue("id").jsonPrimitive.content,
	method = value.getValue("method").jsonPrimitive.content,
	path = value.getValue("path").jsonPrimitive.content,
	status = value.getValue("status").jsonPrimitive.int,
	variant = value["variant"]?.jsonPrimitive?.content,
	reason = value.getValue("reason").jsonPrimitive.content,
	callNumber = value["callNumber"]?.jsonPrimitive?.intOrNull,
	endpoint = value["endpoint"]?.stringOrNull(),
	rejection = (value["rejection"] as? JsonObject)?.let {
		RuntimeRejection(
			kind = it["kind"]?.stringOrNull().orEmpty(),
			code = it["code"]?.stringOrNull().orEmpty(),
			message = it["message"]?.stringOrNull().orEmpty(),
		)
	},
	headers = (value["requestHeaders"] as? JsonArray)
		?.mapNotNull { header -> (header as? JsonObject)?.let(::decodeRuntimeHeader) }
		.orEmpty(),
	claims = ((value["addons"] as? JsonObject)?.get("jwt-claims") as? JsonObject)
		?.mapNotNull { (key, claim) -> claim.stringOrNull()?.let { key to it } }
		?.toMap()
		.orEmpty(),
	query = (value["requestQuery"] as? JsonArray)
		?.mapNotNull { parameter -> (parameter as? JsonObject)?.let(::decodeRuntimeQueryParameter) }
		.orEmpty(),
)

private fun decodeRuntimeQueryParameter(value: JsonObject): RuntimeQueryParameter? {
	val name = value["name"]?.stringOrNull() ?: return null
	return RuntimeQueryParameter(
		name = name,
		value = value["value"]?.stringOrNull(),
		redacted = (value["redacted"] as? JsonPrimitive)?.booleanOrNull == true,
		fingerprint = value["fingerprint"]?.stringOrNull(),
	)
}

private fun decodeRuntimeHeader(value: JsonObject): RuntimeHeader? {
	val name = value["name"]?.stringOrNull() ?: return null
	return RuntimeHeader(
		name = name,
		value = value["value"]?.stringOrNull(),
		redacted = (value["redacted"] as? JsonPrimitive)?.booleanOrNull == true,
		scheme = value["scheme"]?.stringOrNull(),
		fingerprint = value["fingerprint"]?.stringOrNull(),
		cookieNames = (value["cookieNames"] as? JsonArray)?.mapNotNull { it.stringOrNull() }.orEmpty(),
	)
}

private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
