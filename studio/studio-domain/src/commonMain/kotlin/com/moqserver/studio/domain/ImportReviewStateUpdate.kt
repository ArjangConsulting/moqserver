package com.moqserver.studio.domain

import com.moqserver.studio.projectformat.ProjectVariant

internal data class ImportReviewStateUpdate(
	val entries: List<ImportEndpointEntry>,
	val statusLine: String,
)

internal fun completeImportAIGeneration(
	importState: ImportState,
	index: Int,
	generatedResponses: List<ParsedResponse>,
	existingVariants: List<ProjectVariant>,
): ImportReviewStateUpdate? {
	val entries = importState.entries.toMutableList()
	if (index !in entries.indices) return null
	val endpoint = entries[index].endpoint
	val normalizedResponses = ImportEntryFactory.renameResponses(
		generatedResponses,
		existingVariants = existingVariants + endpoint.responses.map {
			ProjectVariant(name = it.name, status = it.statusCode)
		},
		preferExistingNamesByStatus = false,
	)
	entries[index] = entries[index].copy(
		generatedResponses = normalizedResponses.responses,
		selectedGeneratedResponseIndices = normalizedResponses.responses.indices.toSet(),
		aiGenerationLoading = false,
		aiGenerationError = null,
	)
	return ImportReviewStateUpdate(
		entries = entries,
		statusLine = if (generatedResponses.isEmpty()) {
			"AI did not generate extra variants for ${endpoint.method} ${endpoint.path}"
		} else {
			"Generated ${generatedResponses.size} AI variant(s) for ${endpoint.method} ${endpoint.path}"
		},
	)
}

internal fun failImportAIGeneration(
	importState: ImportState,
	index: Int,
	error: String,
): ImportReviewStateUpdate? {
	val entries = importState.entries.toMutableList()
	if (index !in entries.indices) return null
	val endpoint = entries[index].endpoint
	entries[index] = entries[index].copy(aiGenerationLoading = false, aiGenerationError = error)
	return ImportReviewStateUpdate(
		entries = entries,
		statusLine = "Error: Failed to generate AI variants for ${endpoint.method} ${endpoint.path}",
	)
}

internal fun updateImportAIContextHintEntry(
	importState: ImportState,
	index: Int,
	hint: String,
): List<ImportEndpointEntry>? {
	val entries = importState.entries.toMutableList()
	if (index !in entries.indices) return null
	entries[index] = entries[index].copy(aiContextHint = hint)
	return entries
}

internal fun renameImportedResponse(
	importState: ImportState,
	index: Int,
	responseIndex: Int,
	name: String,
	existingVariants: List<ProjectVariant>,
): List<ImportEndpointEntry>? {
	val entries = importState.entries.toMutableList()
	if (index !in entries.indices) return null
	val entry = entries[index]
	val responses = entry.endpoint.responses.toMutableList()
	if (responseIndex !in responses.indices) return null
	responses[responseIndex] = responses[responseIndex].copy(name = name)
	val renormalizedResponses = ImportEntryFactory.renameResponses(
		responses,
		existingVariants = existingVariants + entry.generatedResponses.map {
			ProjectVariant(name = it.name, status = it.statusCode)
		},
		preferExistingNamesByStatus = false,
		normalizeGeneratedNames = false,
	)
	entries[index] = entry.copy(
		endpoint = entry.endpoint.withResponses(renormalizedResponses.responses),
		lockedResponseIndices = renormalizedResponses.lockedResponseIndices,
	)
	return entries
}

internal fun renameGeneratedResponse(
	importState: ImportState,
	index: Int,
	generatedResponseIndex: Int,
	name: String,
	existingVariants: List<ProjectVariant>,
): List<ImportEndpointEntry>? {
	val entries = importState.entries.toMutableList()
	if (index !in entries.indices) return null
	val entry = entries[index]
	val responses = entry.generatedResponses.toMutableList()
	if (generatedResponseIndex !in responses.indices) return null
	responses[generatedResponseIndex] = responses[generatedResponseIndex].copy(name = name)
	val renormalizedResponses = ImportEntryFactory.renameResponses(
		responses,
		existingVariants = existingVariants + entry.endpoint.responses.map {
			ProjectVariant(name = it.name, status = it.statusCode)
		},
		preferExistingNamesByStatus = false,
		normalizeGeneratedNames = false,
	)
	entries[index] = entry.copy(generatedResponses = renormalizedResponses.responses)
	return entries
}

internal fun toggleGeneratedImportResponseSelection(
	importState: ImportState,
	index: Int,
	generatedResponseIndex: Int,
): List<ImportEndpointEntry>? {
	val entries = importState.entries.toMutableList()
	if (index !in entries.indices) return null
	val entry = entries[index]
	if (generatedResponseIndex !in entry.generatedResponses.indices) return null
	val selectedIndices = entry.selectedGeneratedResponseIndices.toMutableSet()
	if (!selectedIndices.add(generatedResponseIndex)) {
		selectedIndices.remove(generatedResponseIndex)
	}
	entries[index] = entry.copy(selectedGeneratedResponseIndices = selectedIndices)
	return entries
}
