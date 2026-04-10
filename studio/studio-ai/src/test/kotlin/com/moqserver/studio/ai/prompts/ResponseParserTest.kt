package com.moqserver.studio.ai.prompts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResponseParserTest {

	@Test
	fun `parseGenerateVariantsResponse extracts embedded json array`() {
		val response = """
			Here are realistic variants for the selected endpoint:

			[
			  {
			    "endpointKey": "GET /pets",
			    "name": "success",
			    "statusCode": 200,
			    "contentType": "application/json",
			    "body": "{\"pets\":[]}",
			    "description": "Empty list response"
			  }
			]

			Let me know if you want error variants too.
		""".trimIndent()

		val result = ResponseParser.parseGenerateVariantsResponse(response)

		assertEquals(1, result.variants.size)
		assertEquals("GET /pets", result.variants.single().endpointKey)
		assertEquals("success", result.variants.single().name)
	}

	@Test
	fun `parseGenerateVariantsResponse unwraps named variants object`() {
		val response = """
			{
			  "variants": [
			    {
			      "endpointKey": "GET /users",
			      "name": "server-error",
			      "statusCode": 500,
			      "contentType": "application/json",
			      "body": "{\"error\":true}"
			    }
			  ]
			}
		""".trimIndent()

		val result = ResponseParser.parseGenerateVariantsResponse(response)

		assertEquals(1, result.variants.size)
		assertEquals("GET /users", result.variants.single().endpointKey)
	}

	@Test
	fun `parseGenerateVariantsResponse strips think tags before parsing`() {
		val response = """
			<think>
			I should return JSON.
			```json
			[{"ignored":true}]
			```
			</think>
			[
			  {
			    "endpointKey": "GET /pets",
			    "name": "empty-list",
			    "statusCode": 200,
			    "contentType": "application/json",
			    "body": "{\"pets\":[]}"
			  }
			]
		""".trimIndent()

		val result = ResponseParser.parseGenerateVariantsResponse(response)

		assertEquals(1, result.variants.size)
		assertEquals("empty-list", result.variants.single().name)
	}

	@Test
	fun `parseGenerateVariantsResponse falls back to visible body text`() {
		val response = "Generated response body: {\"ok\":true}"

		val result = ResponseParser.parseGenerateVariantsResponse(response)

		assertEquals(1, result.variants.size)
		assertEquals("UNMAPPED", result.variants.single().endpointKey)
		assertTrue(result.variants.single().body.contains("ok"))
	}

	@Test
	fun `parseGenerateVariantsResponse handles body as raw json object`() {
		val response = """
			[
			  {
			    "endpointKey": "GET /videos",
			    "name": "success",
			    "statusCode": 200,
			    "contentType": "application/json",
			    "body": {"items": [{"id": "abc123", "title": "Video One"}]},
			    "description": "Success with one video"
			  }
			]
		""".trimIndent()

		val result = ResponseParser.parseGenerateVariantsResponse(response)

		assertEquals(1, result.variants.size)
		assertEquals("GET /videos", result.variants.single().endpointKey)
		assertTrue(result.variants.single().body.contains("abc123"))
		assertTrue(result.variants.single().body.contains("items"))
		// Body should be a valid JSON string, not missing braces
		assertTrue(result.variants.single().body.startsWith("{"))
		assertTrue(result.variants.single().body.endsWith("}"))
	}

	@Test
	fun `parseGenerateVariantsResponse handles body as raw json array`() {
		val response = """
			[
			  {
			    "endpointKey": "GET /tags",
			    "name": "success",
			    "statusCode": 200,
			    "contentType": "application/json",
			    "body": ["tag1", "tag2", "tag3"],
			    "description": "Tag list"
			  }
			]
		""".trimIndent()

		val result = ResponseParser.parseGenerateVariantsResponse(response)

		assertEquals(1, result.variants.size)
		val body = result.variants.single().body
		assertTrue(body.startsWith("["))
		assertTrue(body.endsWith("]"))
		assertTrue(body.contains("tag1"))
	}

	@Test
	fun `parseAnalyzeResponse unwraps findings object`() {
		val response = """
			{
			  "findings": [
			    {
			      "severity": "warning",
			      "category": "coverage",
			      "message": "Add an error variant."
			    }
			  ]
			}
		""".trimIndent()

		val result = ResponseParser.parseAnalyzeResponse(response)

		assertEquals(1, result.findings.size)
		assertEquals("coverage", result.findings.single().category)
	}

	@Test
	fun `parseRefineProjectResponse unwraps suggestions object`() {
		val response = """
			{
			  "suggestions": [
			    {
			      "category": "naming",
			      "title": "Rename endpoint",
			      "description": "Use a clearer alias."
			    }
			  ]
			}
		""".trimIndent()

		val result = ResponseParser.parseRefineProjectResponse(response)

		assertEquals(1, result.suggestions.size)
		assertEquals("Rename endpoint", result.suggestions.single().title)
	}
}
