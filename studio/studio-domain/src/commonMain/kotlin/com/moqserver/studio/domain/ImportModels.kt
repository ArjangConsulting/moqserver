package com.moqserver.studio.domain

import com.moqserver.studio.projectformat.AuthType
import com.moqserver.studio.projectformat.MoqProject
import com.moqserver.studio.projectformat.RuleMatcher

/**
 * Describes which changes were detected between a freshly parsed endpoint and an existing
 * project endpoint. Only spec-owned fields are compared; user-edited fields (body, alias,
 * description, reference names) are intentionally excluded to avoid noise.
 */
data class EndpointSpecDiff(
	val newStatusCodes: Set<Int> = emptySet(),
	val removedStatusCodes: Set<Int> = emptySet(),
	val authChanged: Boolean = false,
	val requestRulesChanged: Boolean = false,
	val tagsChanged: Boolean = false,
) {
	val hasChanges: Boolean
		get() = newStatusCodes.isNotEmpty() ||
			removedStatusCodes.isNotEmpty() ||
			authChanged ||
			requestRulesChanged ||
			tagsChanged

	fun summary(): String = buildList {
		if (newStatusCodes.isNotEmpty()) add("new responses: ${newStatusCodes.sorted().joinToString()}")
		if (removedStatusCodes.isNotEmpty()) add("removed responses: ${removedStatusCodes.sorted().joinToString()}")
		if (authChanged) add("auth changed")
		if (requestRulesChanged) add("request rules changed")
		if (tagsChanged) add("tags changed")
	}.joinToString("; ")
}

/** Describes how a parsed endpoint relates to an existing project during an update-mode import. */
enum class EndpointUpdateStatus {
	/** This endpoint does not exist in the current project. */
	NEW,

	/**
	 * This endpoint exists in the project but spec-owned fields have changed
	 * (new/removed response status codes, auth, request rules, or tags).
	 */
	CHANGED,

	/** This endpoint exists in the project and all spec-owned fields match. */
	UNCHANGED,
}

/** Describes the mode in which the import review was triggered. */
sealed class ImportMode {
	/** Standard import — creates a new project from the parsed spec. */
	data object NewProject : ImportMode()

	/**
	 * Update import — merges newly parsed endpoints into the given existing project.
	 * [existingProject] is the project being updated (used for diff computation and merge).
	 */
	data class UpdateExisting(
		val existingProject: MoqProject,
	) : ImportMode()
}

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
	val tags: List<String> = emptyList(),
	val responses: List<ParsedResponse>,
	val authType: AuthType = AuthType.NONE,
	val authHeaderName: String? = null,
	val queryParameters: List<RuleMatcher> = emptyList(),
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
	val description: String? = null,
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
	val generatedResponses: List<ParsedResponse> = emptyList(),
	val aiGenerationLoading: Boolean = false,
	val aiGenerationError: String? = null,
	/** Optional user-provided context to guide AI mock generation for this endpoint. */
	val aiContextHint: String = "",
	/** How this endpoint relates to the current project (only meaningful in update mode). */
	val updateStatus: EndpointUpdateStatus = EndpointUpdateStatus.NEW,
	/** Non-null when [updateStatus] is [EndpointUpdateStatus.CHANGED], describing what changed. */
	val specDiff: EndpointSpecDiff? = null,
)

data class ImportAIBulkState(
	val running: Boolean = false,
	val completedCount: Int = 0,
	val totalCount: Int = 0,
)

/** State for the import workflow. */
data class ImportState(
	val source: ImportSourceType,
	val sourceFileName: String,
	val parsedSpec: ParsedSpec,
	val entries: List<ImportEndpointEntry>,
	val projectName: String,
	val aiBulkState: ImportAIBulkState = ImportAIBulkState(),
	/** The mode this import was started in. */
	val mode: ImportMode = ImportMode.NewProject,
) {
	val acceptedCount: Int get() = entries.count { it.accepted }
	val totalCount: Int get() = entries.size
	val warnings: List<String> get() = parsedSpec.warnings
	val isUpdateMode: Boolean get() = mode is ImportMode.UpdateExisting
}
