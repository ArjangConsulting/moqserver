package com.moqserver.studio.ai.prompts

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
