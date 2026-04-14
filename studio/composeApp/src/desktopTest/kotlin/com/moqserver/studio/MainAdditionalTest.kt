package com.moqserver.studio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainAdditionalTest {

	// ── planURLImportExecution ───────────────────────────────────────

	@Test
	fun `planURLImportExecution for import action`() {
		val state = ImportFromURLState(
			mode = URLImportMode.OPENAPI,
			action = URLImportAction.IMPORT,
		)
		val plan = planURLImportExecution(state)
		assertEquals("Importing", plan.operationLabel)
		assertEquals("openapi", plan.modeLabel)
		assertFalse(plan.requiresProject)
	}

	@Test
	fun `planURLImportExecution for update action`() {
		val state = ImportFromURLState(
			mode = URLImportMode.SWAGGER,
			action = URLImportAction.UPDATE,
		)
		val plan = planURLImportExecution(state)
		assertEquals("Updating from", plan.operationLabel)
		assertEquals("swagger", plan.modeLabel)
		assertTrue(plan.requiresProject)
	}

	// ── buildURLImportAuth ──────────────────────────────────────────

	@Test
	fun `buildURLImportAuth returns null for NONE`() {
		val state = ImportFromURLState(authType = URLAuthType.NONE)
		val auth = buildURLImportAuth(state)
		assertEquals(null, auth)
	}

	@Test
	fun `buildURLImportAuth returns Bearer for BEARER type`() {
		val state = ImportFromURLState(
			authType = URLAuthType.BEARER,
			bearerToken = "my-token",
		)
		val auth = buildURLImportAuth(state)
		assertTrue(auth is com.moqserver.studio.imports.URLImportAuth.Bearer)
		assertEquals("my-token", auth.token)
	}

	@Test
	fun `buildURLImportAuth returns Basic for BASIC type`() {
		val state = ImportFromURLState(
			authType = URLAuthType.BASIC,
			basicUsername = "user",
			basicPassword = "pass",
		)
		val auth = buildURLImportAuth(state)
		assertTrue(auth is com.moqserver.studio.imports.URLImportAuth.Basic)
		val basic = auth
		assertEquals("user", basic.username)
		assertEquals("pass", basic.password)
	}

	// ── URLImportExecutionPlan data class ───────────────────────────

	@Test
	fun `URLImportExecutionPlan equality`() {
		val a = URLImportExecutionPlan("Importing", "openapi", false)
		val b = URLImportExecutionPlan("Importing", "openapi", false)
		assertEquals(a, b)
	}
}
