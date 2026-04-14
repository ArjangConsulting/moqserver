package com.moqserver.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class URLImportWorkflowIntentTest {

	@Test
	fun `swagger update from url keeps dialog copy and execution plan aligned`() {
		val state = ImportFromURLState(
			url = "https://example.com/swagger-ui/",
			mode = URLImportMode.SWAGGER,
			action = URLImportAction.UPDATE,
		)

		val copy = importFromURLDialogCopy(state)
		val plan = planURLImportExecution(state)

		assertEquals("Update from Swagger URL", copy.title)
		assertEquals("Update", copy.submitButton)
		assertEquals("Updating from", plan.operationLabel)
		assertEquals("swagger", plan.modeLabel)
		assertTrue(plan.requiresProject)
	}
}
