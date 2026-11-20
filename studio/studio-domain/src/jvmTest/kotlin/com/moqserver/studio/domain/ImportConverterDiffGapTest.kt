package com.moqserver.studio.domain

import com.moqserver.studio.projectformat.AuthType
import com.moqserver.studio.projectformat.RequestRules
import com.moqserver.studio.projectformat.RuleMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportConverterDiffGapTest {
	@Test
	fun `diffEndpoint detects auth removal (BEARER to NONE)`() {
		val diff = ImportConverter.diffEndpoint(
			parsedEndpoint(authType = AuthType.NONE),
			makeEndpoint(authType = AuthType.BEARER),
		)
		assertTrue(diff.authChanged)
	}

	@Test
	fun `diffEndpoint detects cookie change`() {
		val parsed = ParsedEndpoint(
			method = "GET",
			path = "/items",
			responses = listOf(ParsedResponse(name = "ok", statusCode = 200)),
			cookies = listOf(RuleMatcher(name = "session", required = true)),
		)

		val diff = ImportConverter.diffEndpoint(parsed, makeEndpoint())
		assertTrue(diff.requestRulesChanged)
	}

	@Test
	fun `diffEndpoint detects tags removed to empty`() {
		val diff = ImportConverter.diffEndpoint(
			parsedEndpoint(tags = emptyList()),
			makeEndpoint(tags = listOf("pets")),
		)
		assertTrue(diff.tagsChanged)
	}

	@Test
	fun `diffEndpoint detects queryParameters change via requiredQueryParameters`() {
		val parsed = ParsedEndpoint(
			method = "GET",
			path = "/items",
			responses = listOf(ParsedResponse(name = "ok", statusCode = 200)),
			requiredQueryParameters = listOf("limit"),
		)

		val diff = ImportConverter.diffEndpoint(parsed, makeEndpoint())
		assertTrue(diff.requestRulesChanged)
	}

	@Test
	fun `diffEndpoint detects additional duplicate response with same status and body`() {
		val existing = makeEndpoint(path = "/items", statusCodes = listOf(200))
		val parsed = ParsedEndpoint(
			method = "GET",
			path = "/items",
			responses = listOf(
				ParsedResponse(name = "default", statusCode = 200, body = "{\"spec\":\"value\"}"),
				ParsedResponse(name = "success-200", statusCode = 200, body = "{\"spec\":\"value\"}"),
			),
		)

		val diff = ImportConverter.diffEndpoint(parsed, existing)

		assertTrue(diff.hasChanges)
		assertTrue(diff.responseBodyChanged)
		assertFalse(diff.newStatusCodes.isNotEmpty())
	}

	@Test
	fun `diffEndpoint does not flag duplicate responses when counts already match`() {
		val existing = makeEndpoint(path = "/items", statusCodes = listOf(200, 200))
		val parsed = ParsedEndpoint(
			method = "GET",
			path = "/items",
			responses = listOf(
				ParsedResponse(name = "default", statusCode = 200, body = "{\"spec\":\"value\"}"),
				ParsedResponse(name = "success-200", statusCode = 200, body = "{\"spec\":\"value\"}"),
			),
		)

		val diff = ImportConverter.diffEndpoint(parsed, existing)

		assertFalse(diff.hasChanges)
	}
}

class ImportConverterBodyRulesTest {
	private val bodyRulesEndpoint = ParsedEndpoint(
		method = "POST",
		path = "/orders",
		responses = listOf(ParsedResponse(name = "created", statusCode = 201)),
		requiresBody = true,
		acceptedContentTypes = listOf("application/json", "image/*"),
	)

	@Test
	fun `import keeps requires_body and accepted_content_types`() {
		val spec = ParsedSpec(title = "Orders", version = "1", endpoints = listOf(bodyRulesEndpoint))

		val rules = ImportConverter.convert(
			spec = spec,
			acceptedEntries = spec.endpoints.map { ImportEndpointEntry(endpoint = it) },
			projectName = "Orders",
			projectPath = "/tmp/orders",
		).endpoints.single().requestRules

		assertEquals(true, rules?.requiresBody)
		assertEquals(listOf("application/json", "image/*"), rules?.acceptedContentTypes)
	}

	@Test
	fun `diffEndpoint flags a change in body rules only`() {
		val existing = makeEndpoint(method = "POST", path = "/orders", statusCodes = listOf(201))

		assertTrue(ImportConverter.diffEndpoint(bodyRulesEndpoint, existing).requestRulesChanged)
		assertFalse(
			ImportConverter.diffEndpoint(
				bodyRulesEndpoint,
				existing.copy(
					requestRules = RequestRules(requiresBody = true, acceptedContentTypes = listOf("image/*", "application/json")),
				),
			).requestRulesChanged,
		)
	}
}
