package com.moqserver.studio

import com.moqserver.studio.domain.RuntimeServerInfo
import com.moqserver.studio.projectformat.format.FormatServiceException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeInspectionTest {
	@Test
	fun `request history retains the selection explanation`() {
		val value = Json.parseToJsonElement(
			"""{"id":"1","method":"GET","path":"/users","status":500,"variant":"error","reason":"runtime override","callNumber":2}""",
		).jsonObject
		val request = decodeRuntimeRequest(value)
		assertEquals("runtime override", request.reason)
		assertEquals("error", request.variant)
		assertEquals(2, request.callNumber)
	}

	@Test
	fun `a refused request decodes its rejection, redacted headers and claims`() {
		val value = Json.parseToJsonElement(
			"""
			{"id":"2","method":"GET","path":"/v1/me","status":400,"reason":"missing_required_header",
			 "rejection":{"kind":"request_rule","code":"missing_required_header","message":"Header 'X-App-Device' is required"},
			 "requestHeaders":[
			  {"name":"authorization","truncated":false,"redacted":true,"scheme":"Bearer","fingerprint":"aaaaaaaaaaaaaaaa"},
			  {"name":"cookie","truncated":false,"redacted":true,"cookieNames":["example_session"]},
			  {"name":"accept","value":"application/json","truncated":false,"redacted":false}],
			 "addons":{"jwt-claims":{"sub":"user-1"}}}
			""".trimIndent(),
		).jsonObject
		val request = decodeRuntimeRequest(value)
		assertEquals("request_rule", request.rejection?.kind)
		assertFalse(request.isUnmatched)
		assertEquals(mapOf("sub" to "user-1"), request.claims)
		assertEquals(
			"""
			Rejected (request_rule missing_required_header): Header 'X-App-Device' is required
			Headers: authorization (Bearer, fingerprint aaaaaaaaaaaaaaaa), cookie (cookies example_session), accept=application/json
			Claims: sub=user-1
			""".trimIndent(),
			requestSummary(request),
		)
	}

	@Test
	fun `captured query parameters are listed in order with credentials fingerprinted`() {
		val value = Json.parseToJsonElement(
			"""
			{"id":"4","method":"GET","path":"/v1/items","endpoint":"GET /v1/items","status":200,"variant":"ok",
			 "reason":"declared default","callNumber":1,
			 "requestQuery":[
			  {"name":"status","value":"open","truncated":false,"redacted":false},
			  {"name":"api_key","truncated":false,"redacted":true,"fingerprint":"bbbbbbbbbbbbbbbb"}]}
			""".trimIndent(),
		).jsonObject
		assertEquals(
			"""
			declared default · call 1 · ok
			Query: status=open&api_key (fingerprint bbbbbbbbbbbbbbbb)
			""".trimIndent(),
			requestSummary(decodeRuntimeRequest(value)),
		)
	}

	@Test
	fun `a request no endpoint served is unmatched`() {
		val value = Json.parseToJsonElement(
			"""{"id":"3","method":"GET","path":"/v1/unknown","status":404,"reason":"no endpoint"}""",
		).jsonObject
		val request = decodeRuntimeRequest(value)
		assertTrue(request.isUnmatched)
		assertEquals("Unmatched: no endpoint serves this path", requestSummary(request))
	}

	@Test
	fun `server summary shows the release and capabilities`() {
		val info = decodeServerInfo(
			Json.parseToJsonElement(
				"""{"version":"1.2.3","commit":"0123456789abcdef","capabilities":["request-query","limited-overrides"]}""",
			).jsonObject,
		)
		assertEquals("Server 1.2.3 (0123456789ab) · capabilities: request-query, limited-overrides", serverSummary(info))
		val sourceJson = """{"version":null,"commit":null,"capabilities":[]}"""
		val source = decodeServerInfo(Json.parseToJsonElement(sourceJson).jsonObject)
		assertEquals("Server unreleased build · capabilities: none", serverSummary(source))
		assertTrue(serverSummary(RuntimeServerInfo(available = false)).contains("predates"))
	}

	@Test
	fun `header names split on commas and whitespace without duplicates`() {
		assertEquals(listOf("X-App-Device", "X-Trace"), parseHeaderNames(" X-App-Device, x-app-device X-Trace,, "))
	}

	@Test
	fun `disk conflict recovery preserves local edits through save as`() {
		val message = requireNotNull(recoveryMessage(FormatServiceException("E_PROJECT_CHANGED", "conflict")))
		assertTrue(message.contains("Save As"))
		assertTrue(message.contains("Reload Project"))
	}
}
