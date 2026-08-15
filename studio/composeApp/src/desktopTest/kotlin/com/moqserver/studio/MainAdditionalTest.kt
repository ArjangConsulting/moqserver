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

	@Test
	fun `shouldPersistImportedProjectImmediately returns true for new imports`() {
		assertTrue(shouldPersistImportedProjectImmediately(isUpdateMode = false))
	}

	@Test
	fun `shouldPersistImportedProjectImmediately returns false for update imports`() {
		assertFalse(shouldPersistImportedProjectImmediately(isUpdateMode = true))
	}

	// ── buildURLImportAuth ──────────────────────────────────────────

	@Test
	fun `buildURLImportAuth returns null for NONE`() {
		val state = ImportFromURLState(authType = URLAuthType.NONE)
		val auth = buildURLImportAuth(state)
		assertEquals(null, auth)
	}

	@Test
	fun `buildURLImportAuth returns bearer for BEARER type`() {
		val state = ImportFromURLState(
			authType = URLAuthType.BEARER,
			bearerToken = "my-token",
		)
		val auth = buildURLImportAuth(state)
		assertEquals("my-token", auth?.bearer)
		assertEquals(null, auth?.basic)
	}

	@Test
	fun `buildURLImportAuth returns basic for BASIC type`() {
		val state = ImportFromURLState(
			authType = URLAuthType.BASIC,
			basicUsername = "user",
			basicPassword = "pass",
		)
		val auth = buildURLImportAuth(state)
		assertEquals("user", auth?.basic?.username)
		assertEquals("pass", auth?.basic?.password)
		assertEquals(null, auth?.bearer)
	}

	// ── URLImportExecutionPlan data class ───────────────────────────

	@Test
	fun `URLImportExecutionPlan equality`() {
		val a = URLImportExecutionPlan("Importing", "openapi", false)
		val b = URLImportExecutionPlan("Importing", "openapi", false)
		assertEquals(a, b)
	}

	// ── sourceNameFromUrl ────────────────────────────────────────────
	// Ported from the deleted OpenAPIURLFetcherTest - moq-format now does the fetching, but this
	// display-name derivation is presentation-only and stayed in Kotlin.

	@Test
	fun `sourceNameFromUrl extracts host and path`() {
		assertEquals("example.com/api/openapi.json", sourceNameFromUrl("https://example.com/api/openapi.json"))
	}

	@Test
	fun `sourceNameFromUrl strips trailing slash`() {
		assertEquals("example.com/api", sourceNameFromUrl("https://example.com/api/"))
	}

	@Test
	fun `sourceNameFromUrl returns host only for root url`() {
		assertEquals("example.com", sourceNameFromUrl("https://example.com"))
	}

	@Test
	fun `sourceNameFromUrl returns original on invalid url`() {
		val badUrl = "not a url"
		assertEquals(badUrl, sourceNameFromUrl(badUrl))
	}
}
