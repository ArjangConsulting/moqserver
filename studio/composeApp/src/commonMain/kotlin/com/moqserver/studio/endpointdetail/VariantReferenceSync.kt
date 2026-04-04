package com.moqserver.studio.endpointdetail

import com.moqserver.studio.data.VariantReferenceSyncPreference
import com.moqserver.studio.projectformat.suggestedVariantReferenceName

internal fun shouldPromptToSyncVariantReferenceName(
	previousName: String,
	currentReferenceName: String,
	existingReferenceNames: Collection<String>,
): Boolean {
	if (previousName.isBlank()) return false
	val baselineReferenceName = suggestedVariantReferenceName(
		preferredSource = previousName,
		status = 200,
		existingNames = existingReferenceNames.filterNot { it == currentReferenceName },
	)
	return currentReferenceName == baselineReferenceName
}

internal fun syncedVariantReferenceName(
	newName: String,
	currentReferenceName: String,
	existingReferenceNames: Collection<String>,
): String {
	return suggestedVariantReferenceName(
		preferredSource = newName,
		status = 200,
		existingNames = existingReferenceNames.filterNot { it == currentReferenceName },
	)
}

internal fun applyVariantNameChange(
	newName: String,
	currentReferenceName: String,
	previousName: String,
	existingReferenceNames: Collection<String>,
	preference: VariantReferenceSyncPreference?,
): VariantNameChangeResult {
	val prompt = shouldPromptToSyncVariantReferenceName(
		previousName = previousName,
		currentReferenceName = currentReferenceName,
		existingReferenceNames = existingReferenceNames,
	)
	return when {
		!prompt -> VariantNameChangeResult(newReferenceName = currentReferenceName)
		preference == VariantReferenceSyncPreference.ALWAYS_UPDATE -> VariantNameChangeResult(
			newReferenceName = syncedVariantReferenceName(
				newName = newName,
				currentReferenceName = currentReferenceName,
				existingReferenceNames = existingReferenceNames,
			),
		)

		preference == VariantReferenceSyncPreference.NEVER_UPDATE -> VariantNameChangeResult(newReferenceName = currentReferenceName)
		else -> VariantNameChangeResult(
			newReferenceName = currentReferenceName,
			shouldPrompt = true,
		)
	}
}

internal data class VariantNameChangeResult(
	val newReferenceName: String,
	val shouldPrompt: Boolean = false,
)
