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
        acceptedEntries: List<ImportEndpointEntry>,
        projectName: String,
        projectPath: String,
    ): MoqProject {
        val assignedEndpointReferenceNames = mutableListOf<String>()
        val endpoints = acceptedEntries.map { convertEndpoint(it, assignedEndpointReferenceNames) }
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

    /**
     * Merges accepted import entries into an existing project.
     *
     * Strategy:
     * - NEW entries: converted fresh and appended to the project endpoint list.
     * - CHANGED entries: spec-owned fields (auth, request rules, tags) are updated on the existing
     *   endpoint; only new response status codes from the spec are added as new variants — existing
     *   user-authored variants are preserved untouched.
     * - UNCHANGED entries: always skipped (they contribute nothing new to the project).
     * - Entries with [accepted=false] are always skipped regardless of status.
     */
    fun merge(
        existingProject: MoqProject,
        acceptedEntries: List<ImportEndpointEntry>,
        updateSelection: UpdateSelection = UpdateSelection(),
    ): MoqProject {
        val newEntries = acceptedEntries.filter {
            it.updateStatus == EndpointUpdateStatus.NEW && updateSelection.url
        }
        val changedEntries = acceptedEntries.filter {
            it.updateStatus == EndpointUpdateStatus.CHANGED && shouldMergeChangedEntry(it, updateSelection)
        }

        // Build set of existing endpoint IDs for collision avoidance when assigning new reference names
        val assignedEndpointReferenceNames = existingProject.endpoints.map { it.referenceName }.toMutableList()

        // Convert brand-new endpoints fresh
        val convertedNew = newEntries.map { convertEndpoint(it, assignedEndpointReferenceNames) }

        // Apply spec-field updates to changed endpoints (preserving user content)
        val updatedExisting = existingProject.endpoints.map { existing ->
            val changedEntry = changedEntries.find { entry ->
                endpointId(entry.endpoint.method, entry.endpoint.path) == existing.id
            }
            if (changedEntry != null) mergeEndpoint(existing, changedEntry, updateSelection) else existing
        }

        return existingProject.copy(
            endpoints = updatedExisting + convertedNew,
        )
    }

    /**
     * Computes a [EndpointSpecDiff] by comparing a freshly parsed endpoint against an existing
     * [EndpointDocument]. Only spec-owned, non-user-editable fields are compared.
     */
    fun diffEndpoint(parsed: ParsedEndpoint, existing: EndpointDocument): EndpointSpecDiff {
        val parsedStatusCodes = parsed.responses.map { it.statusCode }.toSet()
        val existingStatusCodes = existing.variants.map { it.status }.toSet()
        val newStatusCodes = parsedStatusCodes - existingStatusCodes
        val removedStatusCodes = existingStatusCodes - parsedStatusCodes

        val parsedAuth = if (parsed.authType != AuthType.NONE) {
            parsed.authType to parsed.authHeaderName
        } else {
            null
        }
        val existingAuth = existing.auth?.let { it.type to it.headerName }
        val authChanged = parsedAuth != existingAuth

        val parsedRequiredHeaders = parsed.requiredHeaders.toSet()
        val existingRequiredHeaders = existing.requestRules?.headers
            ?.filter { it.required == true }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()

        val parsedQueryParams = if (parsed.queryParameters.isNotEmpty()) {
            parsed.queryParameters.map { it.name }.toSet()
        } else {
            parsed.requiredQueryParameters.toSet()
        }
        val existingQueryParams = existing.requestRules?.queryParams
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()

        val parsedCookies = parsed.cookies.map { it.name }.toSet()
        val existingCookies = existing.requestRules?.cookies
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()

        val requestRulesChanged = parsedRequiredHeaders != existingRequiredHeaders ||
            parsedQueryParams != existingQueryParams ||
            parsedCookies != existingCookies

        val parsedTags = parsed.tags.toSet()
        val existingTags = existing.tags?.toSet() ?: emptySet()
        val tagsChanged = parsedTags != existingTags

		val parsedBodiesByStatus = parsed.responses.associate { response ->
			response.statusCode to normalizeParsedBody(response.body)
		}
		val existingBodiesByStatus = existing.variants.associate {
			it.status to normalizeBody(it.body?.let(::yamlBodyToComparableString))
		}
        val sharedStatusCodes = parsedBodiesByStatus.keys intersect existingBodiesByStatus.keys
        val responseBodyChanged = sharedStatusCodes.any { status ->
            parsedBodiesByStatus[status] != existingBodiesByStatus[status]
        }

		return EndpointSpecDiff(
			newStatusCodes = newStatusCodes,
			removedStatusCodes = removedStatusCodes,
			authChanged = authChanged,
			requestRulesChanged = requestRulesChanged,
			tagsChanged = tagsChanged,
			responseBodyChanged = responseBodyChanged,
		)
	}

    /**
     * Merges spec-owned fields from a [changedEntry] into an [existing] endpoint.
     *
     * Preserved from existing (user-owned): alias, description, referenceName, body/bodyFile,
     * delayMs, requestMatch, isDefault, variant names/reference names.
     *
     * Updated from spec (spec-owned): auth, requestRules, tags.
     * Added from spec: variants whose status codes are new in the spec (existing variants kept).
     */
    @Suppress("LongMethod")
    private fun mergeEndpoint(
        existing: EndpointDocument,
        changedEntry: ImportEndpointEntry,
        updateSelection: UpdateSelection,
    ): EndpointDocument {
        val parsed = changedEntry.endpoint
        val diff = changedEntry.specDiff

        // Update spec-owned structural fields
        val updatedAuth = if (parsed.authType != AuthType.NONE) {
            ProjectAuthConfig(
                type = parsed.authType,
                verify = true,
                headerName = parsed.authHeaderName,
            )
        } else {
            null
        }

        val updatedRequestRules = if (updateSelection.details && diff?.requestRulesChanged == true) {
            buildRequestRules(parsed)
        } else {
            existing.requestRules
        }

        val updatedTags = if (updateSelection.details && diff?.tagsChanged == true) {
            parsed.tags.ifEmpty { null }
        } else {
            existing.tags
        }

        // Add or replace variants when body updates are enabled; otherwise preserve existing variants.
        val existingStatusCodes = existing.variants.map { it.status }.toSet()
        val mergedVariants = if (updateSelection.body) {
            mergeVariants(existing, parsed, changedEntry.generatedResponses)
        } else {
            existing.variants
        }

		return existing.copy(
			auth = if (updateSelection.details && diff?.authChanged == true) updatedAuth else existing.auth,
			requestRules = updatedRequestRules,
			tags = updatedTags,
			variants = mergedVariants,
		)
	}

	private fun shouldMergeChangedEntry(entry: ImportEndpointEntry, updateSelection: UpdateSelection): Boolean {
		val diff = entry.specDiff ?: return false
		return (updateSelection.details && diff.affectsDetails) ||
			(updateSelection.body && (diff.affectsBody || entry.generatedResponses.isNotEmpty()))
	}

	private fun mergeVariants(
		existing: EndpointDocument,
		parsed: ParsedEndpoint,
		generatedResponses: List<ParsedResponse>,
	): List<ProjectVariant> {
		val responses = deduplicateResponses(parsed.responses + generatedResponses)
		val responsesByStatus = responses.groupBy { it.statusCode }.mapValues { (_, values) -> values.toMutableList() }

		val assignedNames = existing.variants.map { it.name }.toMutableList()
		val assignedRefNames = existing.variants.map { it.referenceName }.toMutableList()

		val updatedExisting = existing.variants.map { variant ->
			val candidates = responsesByStatus[variant.status] ?: return@map variant
			val replacement = candidates.firstOrNull { it.name == variant.name }
				?: candidates.singleOrNull()
				?: return@map variant
			candidates.remove(replacement)
			convertVariant(
				resp = replacement,
				name = variant.name,
				referenceName = variant.referenceName,
				isDefault = variant.isDefault == true,
			).copy(
				requestMatch = variant.requestMatch,
				delayMs = variant.delayMs,
				bodyFile = variant.bodyFile,
			)
		}

		val newResponses = responsesByStatus.values.flatten()
		val newVariants = newResponses.map { resp ->
			val name = suggestedVariantName(
				status = resp.statusCode,
				existingNames = assignedNames,
				preferredName = resp.name,
			)
			assignedNames += name
			val refName = suggestedVariantReferenceName(
				preferredSource = name,
				status = resp.statusCode,
				existingNames = assignedRefNames,
			)
			assignedRefNames += refName
			convertVariant(resp = resp, name = name, referenceName = refName, isDefault = false)
		}

		return updatedExisting + newVariants
	}

	private fun normalizeBody(body: String?): String? = body?.trim()?.ifBlank { null }

	private fun normalizeParsedBody(body: String?): String? {
		if (body == null) return null
		return parseBodyToYamlValue(body).let(::yamlBodyToComparableString).trim().ifBlank { null }
	}

	private fun yamlBodyToComparableString(body: YamlValue): String = when (body) {
		is YamlValue.Null -> "null"
		is YamlValue.Str -> body.value
		is YamlValue.Int -> body.value.toString()
		is YamlValue.Double -> body.value.toString()
		is YamlValue.Bool -> body.value.toString()
		is YamlValue.Array ->
			body.value.joinToString(prefix = "[", postfix = "]") { yamlBodyToComparableString(it) }
		is YamlValue.Obj ->
			body.value.entries
				.sortedBy { it.key }
				.joinToString(prefix = "{", postfix = "}") { (key, value) ->
					"$key:${yamlBodyToComparableString(value)}"
				}
	}

	@Suppress("LongMethod")
	private fun convertEndpoint(
        entry: ImportEndpointEntry,
        assignedEndpointReferenceNames: MutableList<String>,
    ): EndpointDocument {
        val parsed = entry.endpoint
        val id = endpointId(parsed.method, parsed.path)
        val alias = parsed.alias?.takeIf { it.isNotBlank() }
            ?: defaultAliasForEndpoint(method = parsed.method, path = parsed.path)
        val referenceName = suggestedEndpointReferenceName(
            preferredSource = parsed.referenceName ?: alias,
            fallbackId = id,
            existingNames = assignedEndpointReferenceNames,
        )
        assignedEndpointReferenceNames += referenceName
		val allResponses = deduplicateResponses(parsed.responses + entry.generatedResponses)
        val defaultIndex = when {
            allResponses.isEmpty() -> -1
            else -> allResponses.indexOfFirst { it.name == "default" }.takeIf { it >= 0 }
                ?: allResponses.indexOfFirst { it.statusCode in 200..299 }.takeIf { it >= 0 }
                ?: 0
        }
        val assignedNames = mutableListOf<String>()
        val assignedReferenceNames = mutableListOf<String>()
        val variants = allResponses.mapIndexed { index, resp ->
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
            tags = parsed.tags.ifEmpty { null },
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
            description = resp.description,
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

	private fun deduplicateResponses(responses: List<ParsedResponse>): List<ParsedResponse> {
		return responses
			.groupBy { response -> response.statusCode to normalizeParsedBody(response.body) }
			.values
			.map { duplicates -> duplicates.last() }
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
    fun endpointId(method: String, path: String): String {
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
