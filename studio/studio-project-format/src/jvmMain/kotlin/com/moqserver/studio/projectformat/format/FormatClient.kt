package com.moqserver.studio.projectformat.format

import com.moqserver.studio.projectformat.EndpointDocument
import com.moqserver.studio.projectformat.MoqProject
import com.moqserver.studio.projectformat.ProjectVariant
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Typed Kotlin surface over `moq-format`'s JSON-RPC methods (see `Dispatcher.swift` for the
 * method list this mirrors). Every call here corresponds 1:1 to a method `moq-mcp`'s tool
 * handlers also reach through `MoqService` — this is the JSON-RPC transport's equivalent of that
 * adapter, just decoding/encoding Kotlin types instead of MCP's `Value`.
 */
// One thin method per RPC method by design; splitting the class would only scatter this 1:1
// mapping to Dispatcher.swift across files.
@Suppress("TooManyFunctions")
class FormatClient(private val process: FormatProcess) {
    // Defaults stay omitted (kotlinx.serialization's own default): an absent optional field and
    // Kotlin's local convenience default should mean the same "no opinion" thing on the wire.
    // The one field the schema requires present with no fallback (ProjectManifest.version) is
    // annotated @EncodeDefault(ALWAYS) at its declaration instead of forcing this globally — see
    // that annotation's origin in the model generator for why a blanket encodeDefaults = true
    // is the wrong fix (it silently manufactures a body on variants that don't have one).
    private val json = Json { ignoreUnknownKeys = true }

    // MARK: - Sessions

    suspend fun openSession(): String {
        val result = call("session.open", buildJsonObject {})
        return result.jsonObject.getValue("handle").jsonPrimitiveContent()
    }

    suspend fun closeSession(handle: String) {
        call("session.close", buildJsonObject { put("handle", handle) })
    }

    // MARK: - Project lifecycle

    suspend fun createProject(handle: String, name: String, description: String?, path: String, force: Boolean = false):
        ProjectDescription {
        val result = call(
            "project.create",
            buildJsonObject {
                put("handle", handle)
                put("name", name)
                description?.let { put("description", it) }
                put("path", path)
                put("force", force)
            },
        )
        return json.decodeFromJsonElement(ProjectDescription.serializer(), result)
    }

    suspend fun openProject(handle: String, path: String, force: Boolean = false): ProjectDescription {
        val result = call(
            "project.open",
            buildJsonObject {
                put("handle", handle)
                put("path", path)
                put("force", force)
            },
        )
        return json.decodeFromJsonElement(ProjectDescription.serializer(), result)
    }

    suspend fun describeProject(handle: String): ProjectDescription {
        val result = call("project.describe", buildJsonObject { put("handle", handle) })
        return json.decodeFromJsonElement(ProjectDescription.serializer(), result)
    }

    suspend fun saveProject(handle: String) {
        call("project.save", buildJsonObject { put("handle", handle) })
    }

    /** The full project currently open in `handle` — the whole document, not the summary
     * [describeProject] returns. */
    suspend fun readProject(handle: String): MoqProject {
        val result = call("project.read", buildJsonObject { put("handle", handle) })
        return json.decodeFromJsonElement(MoqProject.serializer(), result)
    }

    /**
     * Writes a whole edited [project] to disk in one call: opens the bundle at
     * `project.projectPath` if one exists there, or creates it, replaces the in-memory
     * manifest/endpoints, and saves. This is the whole-project counterpart to
     * [upsertEndpoint]/[upsertVariant] — a client (this one) that edits a [MoqProject] value in
     * memory and wants the complete result persisted, rather than one mutation at a time.
     */
    suspend fun writeProject(handle: String, project: MoqProject, force: Boolean = false): ProjectDescription {
        val params = buildJsonObject {
            put("handle", handle)
            put("project", json.encodeToJsonElement(MoqProject.serializer(), project))
            put("force", force)
        }
        val result = call("project.write", params)
        return json.decodeFromJsonElement(ProjectDescription.serializer(), result)
    }

    // MARK: - Validation

    /** Validates the project currently open in `handle` (server-side session state). */
    suspend fun validateProject(handle: String): ValidationResult {
        val result = call("project.validate", buildJsonObject { put("handle", handle) })
        return json.decodeFromJsonElement(ValidationResult.serializer(), result)
    }

    /**
     * Validates a project value directly — no session, nothing needing a prior save. This is the
     * call Studio's live editor uses: it has an edited, unsaved [MoqProject] in memory and wants
     * an answer for exactly that value.
     */
    suspend fun validateProject(project: MoqProject): ValidationResult {
        val params = buildJsonObject { put("project", json.encodeToJsonElement(MoqProject.serializer(), project)) }
        val result = call("validate", params)
        return json.decodeFromJsonElement(ValidationResult.serializer(), result)
    }

    // MARK: - Endpoints

    suspend fun listEndpoints(
        handle: String,
        filterPath: String? = null,
        filterMethod: String? = null,
        filterTag: String? = null,
    ): List<EndpointSummary> {
        val result = call(
            "endpoint.list",
            buildJsonObject {
                put("handle", handle)
                filterPath?.let { put("filter_path", it) }
                filterMethod?.let { put("filter_method", it) }
                filterTag?.let { put("filter_tag", it) }
            },
        )
        return json.decodeFromJsonElement(ListSerializer(EndpointSummary.serializer()), result)
    }

    suspend fun getEndpoint(handle: String, id: String): EndpointDocument {
        val result = call(
            "endpoint.get",
            buildJsonObject {
                put("handle", handle)
                put("id", id)
            },
        )
        return json.decodeFromJsonElement(EndpointDocument.serializer(), result)
    }

    suspend fun suggestEndpointId(method: String, path: String, alias: String? = null): SuggestedEndpointIdentity {
        val result = call(
            "endpoint.suggestId",
            buildJsonObject {
                put("method", method)
                put("path", path)
                alias?.let { put("alias", it) }
            },
        )
        return json.decodeFromJsonElement(SuggestedEndpointIdentity.serializer(), result)
    }

    suspend fun upsertEndpoint(handle: String, endpoint: EndpointUpsertInput, autosave: Boolean = true): EndpointDocument {
        val params = json.encodeToJsonElement(EndpointUpsertInput.serializer(), endpoint).jsonObject.toMutableMap()
        params["handle"] = JsonPrimitive(handle)
        params["autosave"] = JsonPrimitive(autosave)
        val result = call("endpoint.upsert", JsonObject(params))
        return json.decodeFromJsonElement(EndpointDocument.serializer(), result)
    }

    suspend fun removeEndpoint(handle: String, id: String, autosave: Boolean = true) {
        call(
            "endpoint.remove",
            buildJsonObject {
                put("handle", handle)
                put("id", id)
                put("autosave", autosave)
            },
        )
    }

    // MARK: - Variants

    suspend fun upsertVariant(handle: String, endpointId: String, variant: ProjectVariant, autosave: Boolean = true) {
        val params = json.encodeToJsonElement(ProjectVariant.serializer(), variant).jsonObject.toMutableMap()
        params["handle"] = JsonPrimitive(handle)
        params["endpoint_id"] = JsonPrimitive(endpointId)
        params["autosave"] = JsonPrimitive(autosave)
        call("variant.upsert", JsonObject(params))
    }

    suspend fun removeVariant(handle: String, endpointId: String, name: String, autosave: Boolean = true) {
        call(
            "variant.remove",
            buildJsonObject {
                put("handle", handle)
                put("endpoint_id", endpointId)
                put("name", name)
                put("autosave", autosave)
            },
        )
    }

    // MARK: - Import

    suspend fun importHar(handle: String, path: String, autosave: Boolean = true): ImportSummary {
        val result = call(
            "import.har",
            buildJsonObject {
                put("handle", handle)
                put("path", path)
                put("autosave", autosave)
            },
        )
        return json.decodeFromJsonElement(ImportSummary.serializer(), result)
    }

    suspend fun importOpenapi(handle: String, source: String, autosave: Boolean = true): ImportSummary {
        val result = call(
            "import.openapi",
            buildJsonObject {
                put("handle", handle)
                put("source", source)
                put("autosave", autosave)
            },
        )
        return json.decodeFromJsonElement(ImportSummary.serializer(), result)
    }

    // MARK: - Parse only

    /**
     * Parses a HAR file into a [RemoteParsedSpec] without merging it into any project — the
     * counterpart to [importHar] for a caller (Studio) that wants to hold the parsed result for
     * interactive review before anything is committed. See `RemoteImportParsing.kt` in
     * `studio-domain` for the map onto the domain's own `ParsedSpec`.
     */
    suspend fun parseHar(path: String): RemoteParsedSpec {
        val result = call("import.parseHar", buildJsonObject { put("path", path) })
        return json.decodeFromJsonElement(RemoteParsedSpec.serializer(), result)
    }

    /** Same as [parseHar] for an OpenAPI source (file path or, if the server allows it, a URL). */
    suspend fun parseOpenapi(
        source: String,
        auth: ImportAuthInput? = null,
    ): RemoteParsedOpenAPIResult {
        val params = buildJsonObject {
            put("source", source)
            auth?.let { put("auth", json.encodeToJsonElement(ImportAuthInput.serializer(), it)) }
        }
        val result = call("import.parseOpenapi", params)
        return json.decodeFromJsonElement(RemoteParsedOpenAPIResult.serializer(), result)
    }

    // MARK: - Plumbing

    private suspend fun call(method: String, params: JsonElement): JsonElement = process.call(method, params)

    private fun JsonElement.jsonPrimitiveContent(): String = (this as JsonPrimitive).content
}
