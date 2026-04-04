package com.moqserver.studio

import com.moqserver.studio.domain.VariantReferenceSyncPreference
import com.moqserver.studio.endpointdetail.applyVariantNameChange
import com.moqserver.studio.endpointdetail.shouldPromptToSyncVariantReferenceName
import com.moqserver.studio.endpointdetail.syncedVariantReferenceName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VariantReferenceSyncTest {
	@Test
	fun `prompts when reference name still matches generated value for old name`() {
		assertTrue(
			shouldPromptToSyncVariantReferenceName(
				previousName = "Server Error",
				currentReferenceName = "serverError",
				existingReferenceNames = listOf("serverError"),
			),
		)
	}

	@Test
	fun `does not prompt when reference name was customized`() {
		assertFalse(
			shouldPromptToSyncVariantReferenceName(
				previousName = "Server Error",
				currentReferenceName = "customFailure",
				existingReferenceNames = listOf("customFailure"),
			),
		)
	}

	@Test
	fun `always update preference syncs to new readable name`() {
		val result = applyVariantNameChange(
			newName = "Client Error",
			currentReferenceName = "serverError",
			previousName = "Server Error",
			existingReferenceNames = listOf("serverError"),
			preference = VariantReferenceSyncPreference.ALWAYS_UPDATE,
		)

		assertEquals("clientError", result.newReferenceName)
		assertFalse(result.shouldPrompt)
	}

	@Test
	fun `never update preference keeps reference name unchanged`() {
		val result = applyVariantNameChange(
			newName = "Client Error",
			currentReferenceName = "serverError",
			previousName = "Server Error",
			existingReferenceNames = listOf("serverError"),
			preference = VariantReferenceSyncPreference.NEVER_UPDATE,
		)

		assertEquals("serverError", result.newReferenceName)
		assertFalse(result.shouldPrompt)
	}

	@Test
	fun `sync helper keeps reference names unique`() {
		assertEquals(
			"clientError2",
			syncedVariantReferenceName(
				newName = "Client Error",
				currentReferenceName = "serverError",
				existingReferenceNames = listOf("serverError", "clientError"),
			),
		)
	}
}
