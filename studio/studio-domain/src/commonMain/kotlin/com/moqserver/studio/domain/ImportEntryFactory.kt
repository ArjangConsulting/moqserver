package com.moqserver.studio.domain

import com.moqserver.studio.projectformat.EndpointDocument
import com.moqserver.studio.projectformat.ProjectVariant
import com.moqserver.studio.projectformat.suggestedVariantName

internal object ImportEntryFactory {
	fun forNewImport(parsed: ParsedEndpoint): ImportEndpointEntry {
		val normalizedResponses = normalizeResponses(parsed.responses)
		return ImportEndpointEntry(
			endpoint = parsed.withResponses(normalizedResponses.responses),
			accepted = true,
			lockedResponseIndices = normalizedResponses.lockedResponseIndices,
		)
	}

	fun forUpdateImport(
		parsed: ParsedEndpoint,
		existing: EndpointDocument?,
		previouslyDeselectedIds: Set<String>,
	): ImportEndpointEntry {
		val endpointId = ImportConverter.endpointId(parsed.method, parsed.path)
		val normalizedResponses = normalizeResponses(
			responses = parsed.responses,
			existingVariants = existing?.variants.orEmpty(),
			preferExistingNamesByStatus = false,
		)
		val normalizedParsed = parsed.withResponses(normalizedResponses.responses)

		return when {
			existing == null -> ImportEndpointEntry(
				endpoint = normalizedParsed,
				accepted = endpointId !in previouslyDeselectedIds,
				lockedResponseIndices = normalizedResponses.lockedResponseIndices,
				updateStatus = EndpointUpdateStatus.NEW,
			)

			else -> {
				val diff = ImportConverter.diffEndpoint(normalizedParsed, existing)
				if (diff.hasChanges) {
					ImportEndpointEntry(
						endpoint = normalizedParsed,
						accepted = endpointId !in previouslyDeselectedIds,
						lockedResponseIndices = normalizedResponses.lockedResponseIndices,
						updateStatus = EndpointUpdateStatus.CHANGED,
						specDiff = diff,
					)
				} else {
					ImportEndpointEntry(
						endpoint = normalizedParsed,
						accepted = false,
						lockedResponseIndices = normalizedResponses.lockedResponseIndices,
						updateStatus = EndpointUpdateStatus.UNCHANGED,
					)
				}
			}
		}
	}

	fun renameResponses(
		responses: List<ParsedResponse>,
		existingVariants: List<ProjectVariant> = emptyList(),
		preferExistingNamesByStatus: Boolean = false,
		normalizeGeneratedNames: Boolean = true,
	): NormalizedImportResponses {
		return normalizeResponses(
			responses = responses,
			existingVariants = existingVariants,
			preferExistingNamesByStatus = preferExistingNamesByStatus,
			normalizeGeneratedNames = normalizeGeneratedNames,
		)
	}

	private fun normalizeResponses(
		responses: List<ParsedResponse>,
		existingVariants: List<ProjectVariant> = emptyList(),
		preferExistingNamesByStatus: Boolean = true,
		normalizeGeneratedNames: Boolean = true,
	): NormalizedImportResponses {
		val existingVariantsByStatus = existingVariants.groupBy { it.status }
		val incomingCountByStatus = responses.groupingBy { it.statusCode }.eachCount()
		val assignedNames = existingVariants.map { it.name }.toMutableList()
		val lockedIndices = mutableSetOf<Int>()

		val normalizedResponses = responses.mapIndexed { index, response ->
			val preservedExisting = if (preferExistingNamesByStatus) {
				val candidates = existingVariantsByStatus[response.statusCode].orEmpty()
				if (candidates.size == 1 && incomingCountByStatus[response.statusCode] == 1) {
					candidates.single()
				} else {
					null
				}
			} else {
				null
			}

			val normalizedName = preservedExisting?.name ?: suggestedVariantName(
				status = response.statusCode,
				existingNames = assignedNames,
				preferredName = response.name.takeIf { normalizeGeneratedNames },
			)
				.takeIf { normalizeGeneratedNames }
				?: uniqueResponseName(response.name, assignedNames)

			if (preservedExisting != null) lockedIndices += index
			assignedNames += normalizedName
			response.copy(name = normalizedName)
		}

		return NormalizedImportResponses(
			responses = normalizedResponses,
			lockedResponseIndices = lockedIndices,
		)
	}

	private fun uniqueResponseName(name: String, existingNames: Collection<String>): String {
		val trimmedName = name.trim().ifBlank { "Variant" }
		if (trimmedName !in existingNames) return trimmedName

		var suffix = 2
		while (true) {
			val candidate = "$trimmedName $suffix"
			if (candidate !in existingNames) return candidate
			suffix += 1
		}
	}
}

internal data class NormalizedImportResponses(
	val responses: List<ParsedResponse>,
	val lockedResponseIndices: Set<Int>,
)
