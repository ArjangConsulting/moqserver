package com.moqserver.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class URLImportExecutionPlanTest {

	@Test
	fun `planURLImportExecution treats import as non-project workflow`() {
		val plan = planURLImportExecution(
			ImportFromURLState(
				mode = URLImportMode.OPENAPI,
				action = URLImportAction.IMPORT,
			),
		)

		assertEquals("Importing", plan.operationLabel)
		assertEquals("openapi", plan.modeLabel)
		assertFalse(plan.requiresProject)
	}

	@Test
	fun `planURLImportExecution treats update as project workflow`() {
		val plan = planURLImportExecution(
			ImportFromURLState(
				mode = URLImportMode.SWAGGER,
				action = URLImportAction.UPDATE,
			),
		)

		assertEquals("Updating from", plan.operationLabel)
		assertEquals("swagger", plan.modeLabel)
		assertTrue(plan.requiresProject)
	}
}
