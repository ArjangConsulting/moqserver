package com.moqserver.studio.domain

import com.moqserver.studio.projectformat.AuthType
import com.moqserver.studio.projectformat.EndpointDocument
import com.moqserver.studio.projectformat.MoqProject
import com.moqserver.studio.projectformat.NetworkBehavior
import com.moqserver.studio.projectformat.ProjectAuthConfig
import com.moqserver.studio.projectformat.ProjectDefaults
import com.moqserver.studio.projectformat.ProjectManifest
import com.moqserver.studio.projectformat.ProjectVariant
import com.moqserver.studio.projectformat.RequestRules
import com.moqserver.studio.projectformat.RuleMatcher
import com.moqserver.studio.projectformat.YamlValue
import com.moqserver.studio.projectformat.defaultAliasForEndpoint
import com.moqserver.studio.projectformat.suggestedEndpointReferenceName
import com.moqserver.studio.projectformat.suggestedVariantName
import com.moqserver.studio.projectformat.suggestedVariantReferenceName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Converts a ParsedSpec into a MoqProject, filtering to accepted endpoints only. */
object ImportConverter {
    private val json = Json { ignoreUnknownKeys = true }

    fun convert(
        spec: ParsedSpec,
        acceptedEndpoints: List<ParsedEndpoint>,
        projectName: String,
        projectPath: String,
    ): MoqProject {
        val assignedEndpointReferenceNames = mutableListOf<String>()
        val endpoints = acceptedEndpoints.map { convertEndpoint(it, assignedEndpointReferenceNames) }
        val manifest = ProjectManifest(
            version = "1",
            name = projectName,
            description = "Imported from ${spec.title} (v${spec.version})",
            defaults = ProjectDefaults(
                delayMs = 0,
                auth = ProjectAuthConfig(type = AuthType.NONE, verify = false),
                network = NetworkBehavior(),
            ),
        )
        return MoqProject(
            manifest = manifest,
            endpoints = endpoints,
            projectPath = projectPath,
        )
    }

    private fun convertEndpoint(
        parsed: ParsedEndpoint,
        assignedEndpointReferenceNames: MutableList<String>,
    ): EndpointDocument {
        val id = endpointId(parsed.method, parsed.path)
        val alias = parsed.alias?.takeIf { it.isNotBlank() }
            ?: defaultAliasForEndpoint(method = parsed.method, path = parsed.path)
        val referenceName = suggestedEndpointReferenceName(
            preferredSource = parsed.referenceName ?: alias,
            fallbackId = id,
            existingNames = assignedEndpointReferenceNames,
        )
        assignedEndpointReferenceNames += referenceName
        val defaultIndex = when {
            parsed.responses.isEmpty() -> -1
            else -> parsed.responses.indexOfFirst { it.name == "default" }.takeIf { it >= 0 }
                ?: parsed.responses.indexOfFirst { it.statusCode in 200..299 }.takeIf { it >= 0 }
                ?: 0
        }
        val assignedNames = mutableListOf<String>()
        val assignedReferenceNames = mutableListOf<String>()
        val variants = parsed.responses.mapIndexed { index, resp ->
            val name = suggestedVariantName(
                status = resp.statusCode,
                existingNames = assignedNames,
                preferredName = resp.name,
            )
            assignedNames += name
            val variantReferenceName = suggestedVariantReferenceName(
                preferredSource = name,
                status = resp.statusCode,
                existingNames = assignedReferenceNames,
            )
            assignedReferenceNames += variantReferenceName
            convertVariant(
                resp = resp,
                name = name,
                referenceName = variantReferenceName,
                isDefault = index == defaultIndex,
            )
        }

        val auth = if (parsed.authType != AuthType.NONE) {
            ProjectAuthConfig(
                type = parsed.authType,
                verify = true,
                headerName = parsed.authHeaderName,
            )
        } else {
            null
        }

        val requestRules = buildRequestRules(parsed)

        return EndpointDocument(
            id = id,
            alias = alias,
            description = parsed.description?.takeIf { it.isNotBlank() },
            referenceName = referenceName,
            method = parsed.method.uppercase(),
            path = parsed.path,
            auth = auth,
            requestRules = requestRules,
            variants = variants,
        )
    }

    private fun convertVariant(
        resp: ParsedResponse,
        name: String,
        referenceName: String,
        isDefault: Boolean,
    ): ProjectVariant {
        val body = resp.body?.let { parseBodyToYamlValue(it) }
        return ProjectVariant(
            name = name,
            referenceName = referenceName,
            isDefault = if (isDefault) true else null,
            status = resp.statusCode,
            headers = resp.headers.ifEmpty { null },
            body = body,
        )
    }

    private fun parseBodyToYamlValue(body: String): YamlValue {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return YamlValue.Str(body)

        return runCatching { json.parseToJsonElement(trimmed) }
            .map { jsonElementToYamlValue(it) }
            .getOrElse { YamlValue.Str(body) }
    }

    private fun jsonElementToYamlValue(element: JsonElement): YamlValue {
        return when (element) {
            is JsonNull -> YamlValue.Null
            is JsonPrimitive -> {
                when {
                    element.isString -> YamlValue.Str(element.content)
                    element.booleanOrNull != null -> YamlValue.Bool(element.booleanOrNull!!)
                    element.intOrNull != null -> YamlValue.Int(element.intOrNull!!)
                    element.longOrNull != null -> YamlValue.Double(element.longOrNull!!.toDouble())
                    element.doubleOrNull != null -> YamlValue.Double(element.doubleOrNull!!)
                    else -> YamlValue.Str(element.content)
                }
            }
            is JsonArray ->
                YamlValue.Array(element.map { jsonElementToYamlValue(it) })
            is JsonObject ->
                YamlValue.Obj(element.entries.associate { (k, v) -> k to jsonElementToYamlValue(v) })
        }
    }

    private fun buildRequestRules(parsed: ParsedEndpoint): RequestRules? {
        val headers = parsed.requiredHeaders.map { RuleMatcher(name = it, required = true) }
            .ifEmpty { null }
        val queryParams = parsed.queryParameters
            .ifEmpty {
                parsed.requiredQueryParameters.map { RuleMatcher(name = it, required = true) }
            }
            .ifEmpty { null }
        val cookies = parsed.cookies.ifEmpty { null }

        return if (headers != null || queryParams != null || cookies != null) {
            RequestRules(headers = headers, queryParams = queryParams, cookies = cookies)
        } else {
            null
        }
    }

    /** Generate a deterministic endpoint ID from method + path. */
    internal fun endpointId(method: String, path: String): String {
        val normalized = path
            .removePrefix("/")
            .replace(Regex("\\{[^}]+}"), "param")
            .replace(Regex("[^a-zA-Z0-9/]"), "")
            .replace("/", "-")
            .lowercase()
            .trimEnd('-')

        val id = "${method.lowercase()}-$normalized"
        return id.ifEmpty { "${method.lowercase()}-root" }
    }
}
