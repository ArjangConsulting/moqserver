package com.moqserver.studio.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class HttpClassificationTest {

	@Test
	fun `classifies status families by range`() {
		assertEquals(HttpStatusClass.SUCCESS, httpStatusClass(200))
		assertEquals(HttpStatusClass.SUCCESS, httpStatusClass(299))
		assertEquals(HttpStatusClass.REDIRECT, httpStatusClass(300))
		assertEquals(HttpStatusClass.REDIRECT, httpStatusClass(399))
		assertEquals(HttpStatusClass.CLIENT_ERROR, httpStatusClass(404))
		assertEquals(HttpStatusClass.SERVER_ERROR, httpStatusClass(500))
		assertEquals(HttpStatusClass.SERVER_ERROR, httpStatusClass(599))
	}

	@Test
	fun `classifies out-of-range and informational codes as other`() {
		assertEquals(HttpStatusClass.OTHER, httpStatusClass(100))
		assertEquals(HttpStatusClass.OTHER, httpStatusClass(199))
		assertEquals(HttpStatusClass.OTHER, httpStatusClass(600))
		assertEquals(HttpStatusClass.OTHER, httpStatusClass(0))
	}

	@Test
	fun `maps methods to roles case-insensitively`() {
		assertEquals(HttpMethodRole.PRIMARY, httpMethodRole("GET"))
		assertEquals(HttpMethodRole.PRIMARY, httpMethodRole("get"))
		assertEquals(HttpMethodRole.TERTIARY, httpMethodRole("post"))
		assertEquals(HttpMethodRole.SECONDARY, httpMethodRole("put"))
		assertEquals(HttpMethodRole.SECONDARY, httpMethodRole("patch"))
		assertEquals(HttpMethodRole.ERROR, httpMethodRole("delete"))
	}

	@Test
	fun `maps unknown methods to outline`() {
		assertEquals(HttpMethodRole.OUTLINE, httpMethodRole("OPTIONS"))
		assertEquals(HttpMethodRole.OUTLINE, httpMethodRole("TRACE"))
		assertEquals(HttpMethodRole.OUTLINE, httpMethodRole(""))
	}
}
