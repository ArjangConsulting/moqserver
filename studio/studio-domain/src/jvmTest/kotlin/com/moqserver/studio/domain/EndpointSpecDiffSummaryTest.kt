package com.moqserver.studio.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EndpointSpecDiffSummaryTest {
	@Test
	fun `summary returns empty string when no changes`() {
		assertEquals("", EndpointSpecDiff().summary())
	}

	@Test
	fun `summary lists new status codes in sorted order`() {
		assertTrue(EndpointSpecDiff(newStatusCodes = setOf(500, 201)).summary().contains("new responses: 201, 500"))
	}

	@Test
	fun `summary lists removed status codes in sorted order`() {
		assertTrue(EndpointSpecDiff(removedStatusCodes = setOf(404, 200)).summary().contains("removed responses: 200, 404"))
	}

	@Test
	fun `summary includes auth changed`() {
		assertTrue(EndpointSpecDiff(authChanged = true).summary().contains("auth changed"))
	}

	@Test
	fun `summary includes request rules changed`() {
		assertTrue(EndpointSpecDiff(requestRulesChanged = true).summary().contains("request rules changed"))
	}

	@Test
	fun `summary includes tags changed`() {
		assertTrue(EndpointSpecDiff(tagsChanged = true).summary().contains("tags changed"))
	}

	@Test
	fun `summary includes response bodies changed`() {
		assertTrue(EndpointSpecDiff(responseBodyChanged = true).summary().contains("response bodies changed"))
	}

	@Test
	fun `summary combines multiple changes with semicolon`() {
		val summary = EndpointSpecDiff(newStatusCodes = setOf(201), authChanged = true, tagsChanged = true).summary()
		assertTrue(summary.contains("new responses"))
		assertTrue(summary.contains("auth changed"))
		assertTrue(summary.contains("tags changed"))
		assertEquals(2, summary.count { it == ';' })
	}
}
