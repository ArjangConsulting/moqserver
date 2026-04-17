package com.moqserver.studio.domain

import com.moqserver.studio.projectformat.AuthType
import com.moqserver.studio.projectformat.YamlValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportConverterMergeGapTest {
	@Test
	fun `merge updates requestRules on changed endpoint`() {
		val existing = makeEndpoint(path = "/items", requiredHeaders = emptyList())
		val project = makeProject(existing)
		val newSpec = parsedEndpoint(path = "/items", requiredHeaders = listOf("X-Api-Key"))
		val diff = ImportConverter.diffEndpoint(newSpec, existing)
		val entries = changedEntries(newSpec, diff)

		val result = ImportConverter.merge(
			existingProject = project,
			acceptedEntries = entries,
			updateSelection = UpdateSelection(body = true),
		)
		assertFalse(result.endpoints.single().requestRules?.headers.isNullOrEmpty())
	}

	@Test
	fun `merge skips details changes when details update is disabled`() {
		val existing = makeEndpoint(path = "/items", requiredHeaders = emptyList())
		val project = makeProject(existing)
		val newSpec = parsedEndpoint(path = "/items", requiredHeaders = listOf("X-Api-Key"))
		val diff = ImportConverter.diffEndpoint(newSpec, existing)
		val entries = changedEntries(newSpec, diff)

		val result = ImportConverter.merge(
			existingProject = project,
			acceptedEntries = entries,
			updateSelection = UpdateSelection(details = false),
		)
		assertTrue(result.endpoints.single().requestRules?.headers.isNullOrEmpty())
	}

	@Test
	fun `merge updates tags on changed endpoint`() {
		val existing = makeEndpoint(path = "/items", tags = listOf("old-tag"))
		val project = makeProject(existing)
		val newSpec = parsedEndpoint(path = "/items", tags = listOf("new-tag", "another"))
		val diff = ImportConverter.diffEndpoint(newSpec, existing)
		val entries = changedEntries(newSpec, diff)

		val result = ImportConverter.merge(
			existingProject = project,
			acceptedEntries = entries,
			updateSelection = UpdateSelection(body = true),
		)
		assertEquals(listOf("new-tag", "another"), result.endpoints.single().tags)
	}

	@Test
	fun `merge clears tags when spec has empty tags on changed endpoint`() {
		val existing = makeEndpoint(path = "/items", tags = listOf("old-tag"))
		val project = makeProject(existing)
		val newSpec = parsedEndpoint(path = "/items", tags = emptyList())
		val diff = ImportConverter.diffEndpoint(newSpec, existing)
		val entries = changedEntries(newSpec, diff)

		val result = ImportConverter.merge(
			existingProject = project,
			acceptedEntries = entries,
			updateSelection = UpdateSelection(body = true),
		)
		assertNull(result.endpoints.single().tags)
	}

	@Test
	fun `merge adds generatedResponses on CHANGED endpoint`() {
		val existing = makeEndpoint(path = "/items", statusCodes = listOf(200))
		val project = makeProject(existing)
		val newSpec = parsedEndpoint(path = "/items", statusCodes = listOf(200))
		val diff = ImportConverter.diffEndpoint(newSpec, existing)
		val entries = listOf(
			ImportEndpointEntry(
				endpoint = newSpec,
				accepted = true,
				updateStatus = EndpointUpdateStatus.CHANGED,
				specDiff = diff,
				generatedResponses = listOf(ParsedResponse(name = "ai-variant", statusCode = 422)),
			),
		)

		val result = ImportConverter.merge(
			existingProject = project,
			acceptedEntries = entries,
			updateSelection = UpdateSelection(body = true),
		)
		assertTrue(result.endpoints.single().variants.any { it.status == 422 })
	}

	@Test
	fun `merge updates existing response body when body update is enabled`() {
		val existing = makeEndpoint(
			path = "/items",
			statusCodes = listOf(200),
			userBody = YamlValue.Str("old"),
		)
		val project = makeProject(existing)
		val newSpec = ParsedEndpoint(
			method = "GET",
			path = "/items",
			responses = listOf(ParsedResponse(name = "ok", statusCode = 200, body = "{\"spec\":\"new\"}")),
		)
		val diff = ImportConverter.diffEndpoint(newSpec, existing)
		val entries = changedEntries(newSpec, diff)

		val result = ImportConverter.merge(
			existingProject = project,
			acceptedEntries = entries,
			updateSelection = UpdateSelection(body = true),
		)
		assertEquals(
			"new",
			((result.endpoints.single().variants.single().body as YamlValue.Obj).value["spec"] as YamlValue.Str).value,
		)
	}

	@Test
	fun `merge clears existing bodyFile when replacing fixture-backed body from import`() {
		val existing = makeEndpoint(
			path = "/items",
			statusCodes = listOf(200),
			userBody = null,
		).copy(
			variants = listOf(
				makeEndpoint(
					path = "/items",
					statusCodes = listOf(200),
					userBody = null,
				).variants.single().copy(bodyFile = "fixtures/responses/get-items/items-success.json"),
			),
		)
		val project = makeProject(existing)
		val newSpec = ParsedEndpoint(
			method = "GET",
			path = "/items",
			responses = listOf(ParsedResponse(name = "variant-200", statusCode = 200, body = "{\"spec\":\"new\"}")),
		)
		val diff = ImportConverter.diffEndpoint(newSpec, existing)
		val entries = changedEntries(newSpec, diff)

		val result = ImportConverter.merge(
			existingProject = project,
			acceptedEntries = entries,
			updateSelection = UpdateSelection(body = true),
		)

		val variant = result.endpoints.single().variants.single()
		assertNull(variant.bodyFile)
		assertEquals(
			"new",
			((variant.body as YamlValue.Obj).value["spec"] as YamlValue.Str).value,
		)
	}

	@Test
	fun `merge preserves existing response body when body update is disabled`() {
		val existing = makeEndpoint(
			path = "/items",
			statusCodes = listOf(200),
			userBody = YamlValue.Str("old"),
		)
		val project = makeProject(existing)
		val newSpec = ParsedEndpoint(
			method = "GET",
			path = "/items",
			responses = listOf(ParsedResponse(name = "ok", statusCode = 200, body = "{\"spec\":\"new\"}")),
		)
		val diff = ImportConverter.diffEndpoint(newSpec, existing)
		val entries = changedEntries(newSpec, diff)

		val result = ImportConverter.merge(
			existingProject = project,
			acceptedEntries = entries,
			updateSelection = UpdateSelection(body = false),
		)
		assertEquals(YamlValue.Str("old"), result.endpoints.single().variants.single().body)
	}

	@Test
	fun `merge skips new endpoints when url update is disabled`() {
		val project = makeProject(makeEndpoint(path = "/items"))
		val newEndpoint = parsedEndpoint(path = "/users")
		val entries = listOf(
			ImportEndpointEntry(
				endpoint = newEndpoint,
				accepted = true,
				updateStatus = EndpointUpdateStatus.NEW,
			),
		)

		val result = ImportConverter.merge(
			existingProject = project,
			acceptedEntries = entries,
			updateSelection = UpdateSelection(url = false),
		)
		assertEquals(1, result.endpoints.size)
		assertTrue(result.endpoints.none { it.path == "/users" })
	}

	@Test
	fun `merge removes auth when spec has NONE auth on changed endpoint`() {
		val existing = makeEndpoint(path = "/items", authType = AuthType.BEARER)
		val project = makeProject(existing)
		val newSpec = parsedEndpoint(path = "/items", authType = AuthType.NONE)
		val diff = ImportConverter.diffEndpoint(newSpec, existing)
		val entries = changedEntries(newSpec, diff)

		val result = ImportConverter.merge(existingProject = project, acceptedEntries = entries)
		assertNull(result.endpoints.single().auth)
	}

	@Test
	fun `merge with null specDiff on CHANGED entry preserves existing auth and requestRules`() {
		val existing = makeEndpoint(path = "/items", authType = AuthType.BEARER)
		val project = makeProject(existing)
		val newSpec = parsedEndpoint(path = "/items", statusCodes = listOf(200, 404))
		val entries = listOf(
			ImportEndpointEntry(
				endpoint = newSpec,
				accepted = true,
				updateStatus = EndpointUpdateStatus.CHANGED,
				specDiff = null,
			),
		)

		val result = ImportConverter.merge(existingProject = project, acceptedEntries = entries)
		assertEquals(AuthType.BEARER, result.endpoints.single().auth?.type)
	}

	private fun changedEntries(
		endpoint: ParsedEndpoint,
		diff: EndpointSpecDiff?,
	): List<ImportEndpointEntry> = listOf(
		ImportEndpointEntry(
			endpoint = endpoint,
			accepted = true,
			updateStatus = EndpointUpdateStatus.CHANGED,
			specDiff = diff,
		),
	)
}
