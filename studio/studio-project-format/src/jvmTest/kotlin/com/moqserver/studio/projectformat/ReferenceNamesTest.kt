package com.moqserver.studio.projectformat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReferenceNamesTest {

	// ── isValidReferenceName ────────────────────────────────────────

	@Test
	fun `valid reference names are accepted`() {
		assertTrue(isValidReferenceName("foo"))
		assertTrue(isValidReferenceName("fooBar"))
		assertTrue(isValidReferenceName("_private"))
		assertTrue(isValidReferenceName("foo123"))
		assertTrue(isValidReferenceName("FOO_BAR"))
		assertTrue(isValidReferenceName("a"))
		assertTrue(isValidReferenceName("_"))
	}

	@Test
	fun `invalid reference names are rejected`() {
		assertFalse(isValidReferenceName(""))
		assertFalse(isValidReferenceName("123foo"))
		assertFalse(isValidReferenceName("foo bar"))
		assertFalse(isValidReferenceName("foo-bar"))
		assertFalse(isValidReferenceName("foo.bar"))
		assertFalse(isValidReferenceName("hello world"))
	}

	// ── defaultReferenceNameForEndpointId ───────────────────────────

	@Test
	fun `endpoint id with hyphens becomes camelCase`() {
		assertEquals("getPets", defaultReferenceNameForEndpointId("get-pets"))
	}

	@Test
	fun `endpoint id with single word stays lowercase`() {
		assertEquals("users", defaultReferenceNameForEndpointId("users"))
	}

	@Test
	fun `endpoint id with digits leading gets prefix`() {
		assertEquals("endpoint123abc", defaultReferenceNameForEndpointId("123abc"))
	}

	@Test
	fun `endpoint id with all special chars becomes fallback`() {
		assertEquals("endpoint", defaultReferenceNameForEndpointId("---"))
	}

	@Test
	fun `empty endpoint id returns fallback`() {
		assertEquals("endpoint", defaultReferenceNameForEndpointId(""))
	}

	@Test
	fun `endpoint id with whitespace is trimmed and tokenized`() {
		assertEquals("getUsers", defaultReferenceNameForEndpointId("  get users  "))
	}

	// ── defaultReferenceNameForVariantName ──────────────────────────

	@Test
	fun `variant name becomes camelCase`() {
		assertEquals("notFound", defaultReferenceNameForVariantName("Not Found"))
	}

	@Test
	fun `variant name with hyphens becomes camelCase`() {
		assertEquals("serverError", defaultReferenceNameForVariantName("server-error"))
	}

	@Test
	fun `variant name with leading digit gets prefix`() {
		assertEquals("variant404Error", defaultReferenceNameForVariantName("404-error"))
	}

	@Test
	fun `empty variant name returns fallback`() {
		assertEquals("variant", defaultReferenceNameForVariantName(""))
	}

	@Test
	fun `variant name with camelCase is split and rejoined`() {
		assertEquals("successResponse", defaultReferenceNameForVariantName("successResponse"))
	}

	// ── suggestedEndpointReferenceName ──────────────────────────────

	@Test
	fun `suggested endpoint reference name avoids conflicts`() {
		val existing = setOf("getPets", "getPets2")
		val suggested = suggestedEndpointReferenceName("get-pets", "get-pets", existing)
		assertEquals("getPets3", suggested)
	}

	@Test
	fun `suggested endpoint reference name without conflict returns base`() {
		val existing = setOf("createPets")
		val suggested = suggestedEndpointReferenceName("get-pets", "get-pets", existing)
		assertEquals("getPets", suggested)
	}

	// ── suggestedVariantReferenceName ───────────────────────────────

	@Test
	fun `suggested variant reference name avoids conflicts`() {
		val existing = setOf("success", "success2")
		val suggested = suggestedVariantReferenceName("Success", 200, existing)
		assertEquals("success3", suggested)
	}

	@Test
	fun `suggested variant reference name without conflict returns base`() {
		val existing = setOf("error")
		val suggested = suggestedVariantReferenceName("Success", 200, existing)
		assertEquals("success", suggested)
	}
}
