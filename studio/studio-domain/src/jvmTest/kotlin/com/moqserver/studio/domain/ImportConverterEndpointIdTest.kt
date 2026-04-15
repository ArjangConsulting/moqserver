package com.moqserver.studio.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImportConverterEndpointIdTest {
	@Test
	fun `endpointId handles root path`() {
		assertEquals("get-", ImportConverter.endpointId("GET", "/"))
	}

	@Test
	fun `endpointId normalises path params to param`() {
		assertEquals(
			"delete-users-param-posts-param",
			ImportConverter.endpointId("DELETE", "/users/{userId}/posts/{postId}"),
		)
	}

	@Test
	fun `endpointId lowercases method`() {
		assertEquals("post-items", ImportConverter.endpointId("POST", "/items"))
	}

	@Test
	fun `endpointId handles empty path`() {
		assertTrue(ImportConverter.endpointId("GET", "").startsWith("get"))
	}
}
