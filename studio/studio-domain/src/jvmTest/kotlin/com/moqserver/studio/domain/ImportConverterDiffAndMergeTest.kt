package com.moqserver.studio.domain

import com.moqserver.studio.projectformat.AuthType
import com.moqserver.studio.projectformat.YamlValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportConverterDiffAndMergeTest {
	@Test
	fun `diffEndpoint returns no changes when spec matches existing endpoint`() {
		val existing = makeEndpoint(statusCodes = listOf(200, 404))
		val parsed = parsedEndpoint(statusCodes = listOf(200, 404))

		val diff = ImportConverter.diffEndpoint(parsed, existing)

		assertFalse(diff.hasChanges)
	}

	@Test
	fun `diffEndpoint detects new status code`() {
		val existing = makeEndpoint(statusCodes = listOf(200))
		val parsed = parsedEndpoint(statusCodes = listOf(200, 201))

		val diff = ImportConverter.diffEndpoint(parsed, existing)

		assertTrue(diff.hasChanges)
		assertEquals(setOf(201), diff.newStatusCodes)
		assertTrue(diff.removedStatusCodes.isEmpty())
	}

	@Test
	fun `diffEndpoint detects removed status code`() {
		val existing = makeEndpoint(statusCodes = listOf(200, 404))
		val parsed = parsedEndpoint(statusCodes = listOf(200))

		val diff = ImportConverter.diffEndpoint(parsed, existing)

		assertTrue(diff.hasChanges)
		assertTrue(diff.newStatusCodes.isEmpty())
		assertEquals(setOf(404), diff.removedStatusCodes)
	}

	@Test
	fun `diffEndpoint detects auth change`() {
		val existing = makeEndpoint(authType = AuthType.NONE)
		val parsed = parsedEndpoint(authType = AuthType.BEARER)

		val diff = ImportConverter.diffEndpoint(parsed, existing)

		assertTrue(diff.hasChanges)
		assertTrue(diff.authChanged)
	}

	@Test
	fun `diffEndpoint detects request rules change`() {
		val existing = makeEndpoint(requiredHeaders = emptyList())
		val parsed = parsedEndpoint(requiredHeaders = listOf("X-Api-Key"))

		val diff = ImportConverter.diffEndpoint(parsed, existing)

		assertTrue(diff.hasChanges)
		assertTrue(diff.requestRulesChanged)
	}

	@Test
	fun `diffEndpoint detects tag change`() {
		val existing = makeEndpoint(tags = listOf("pets"))
		val parsed = parsedEndpoint(tags = listOf("pets", "catalog"))

		val diff = ImportConverter.diffEndpoint(parsed, existing)

		assertTrue(diff.hasChanges)
		assertTrue(diff.tagsChanged)
	}

	@Test
	fun `diffEndpoint flags body changes as updateable body changes`() {
		val existing = makeEndpoint(statusCodes = listOf(200), userBody = YamlValue.Str("user has edited this body"))
		val parsed = parsedEndpoint(statusCodes = listOf(200))

		val diff = ImportConverter.diffEndpoint(parsed, existing)

		assertTrue(diff.hasChanges)
		assertTrue(diff.responseBodyChanged)
	}

	@Test
	fun `merge appends new endpoints to existing project`() {
		val existing = makeEndpoint(path = "/items")
		val project = makeProject(existing)
		val newParsed = parsedEndpoint(path = "/users", statusCodes = listOf(200))
		val entries = listOf(
			ImportEndpointEntry(
				endpoint = newParsed,
				accepted = true,
				updateStatus = EndpointUpdateStatus.NEW,
			),
		)

		val result = ImportConverter.merge(
			existingProject = project,
			acceptedEntries = entries,
			updateSelection = UpdateSelection(body = true),
		)

		assertEquals(2, result.endpoints.size)
		val ids = result.endpoints.map { it.id }
		assertTrue("get-items" in ids)
		assertTrue("get-users" in ids)
	}

	@Test
	fun `merge does not include rejected new endpoints when caller filters them out`() {
		val project = makeProject(makeEndpoint(path = "/items"))
		val entries = emptyList<ImportEndpointEntry>()

		val result = ImportConverter.merge(
			existingProject = project,
			acceptedEntries = entries,
			updateSelection = UpdateSelection(body = true),
		)

		assertEquals(1, result.endpoints.size)
		assertEquals("get-items", result.endpoints.single().id)
	}

	@Test
	fun `merge skips unchanged entries`() {
		val project = makeProject(makeEndpoint(path = "/items"))
		val entries = listOf(
			ImportEndpointEntry(
				endpoint = parsedEndpoint(path = "/items"),
				accepted = false,
				updateStatus = EndpointUpdateStatus.UNCHANGED,
			),
		)

		val result = ImportConverter.merge(
			existingProject = project,
			acceptedEntries = entries,
			updateSelection = UpdateSelection(body = true),
		)

		assertEquals(1, result.endpoints.size)
		assertEquals(1, result.endpoints.single().variants.size)
	}

	@Test
	fun `merge adds new variants to changed endpoint without overwriting user-authored variants`() {
		val existing = makeEndpoint(
			path = "/items",
			statusCodes = listOf(200),
			userBody = YamlValue.Str("user-carefully-crafted-body"),
		)
		val project = makeProject(existing)
		val newSpec = parsedEndpoint(path = "/items", statusCodes = listOf(200, 201, 500))
		val diff = ImportConverter.diffEndpoint(newSpec, existing)
		val entries = listOf(
			ImportEndpointEntry(
				endpoint = newSpec,
				accepted = true,
				updateStatus = EndpointUpdateStatus.CHANGED,
				specDiff = diff,
			),
		)

		val result = ImportConverter.merge(
			existingProject = project,
			acceptedEntries = entries,
			updateSelection = UpdateSelection(body = true),
		)

		assertEquals(1, result.endpoints.size)
		val endpoint = result.endpoints.single()
		assertEquals(3, endpoint.variants.size)
		assertTrue(endpoint.variants.find { it.status == 200 }?.body != null)
		assertTrue(endpoint.variants.any { it.status == 201 })
		assertTrue(endpoint.variants.any { it.status == 500 })
	}

	@Test
	fun `merge updates auth on changed endpoint`() {
		val existing = makeEndpoint(path = "/items", authType = AuthType.NONE)
		val project = makeProject(existing)
		val newSpec = parsedEndpoint(path = "/items", authType = AuthType.BEARER)
		val diff = ImportConverter.diffEndpoint(newSpec, existing)
		val entries = listOf(
			ImportEndpointEntry(
				endpoint = newSpec,
				accepted = true,
				updateStatus = EndpointUpdateStatus.CHANGED,
				specDiff = diff,
			),
		)

		val result = ImportConverter.merge(
			existingProject = project,
			acceptedEntries = entries,
			updateSelection = UpdateSelection(body = true),
		)

		assertEquals(AuthType.BEARER, result.endpoints.single().auth?.type)
	}

	@Test
	fun `merge preserves existing endpoint alias and description on changed endpoint`() {
		val existing = makeEndpoint(
			path = "/items",
			statusCodes = listOf(200),
		).copy(
			alias = "My Custom Alias",
			description = "My custom description",
		)
		val project = makeProject(existing)
		val newSpec = parsedEndpoint(path = "/items", statusCodes = listOf(200, 404))
		val diff = ImportConverter.diffEndpoint(newSpec, existing)
		val entries = listOf(
			ImportEndpointEntry(
				endpoint = newSpec,
				accepted = true,
				updateStatus = EndpointUpdateStatus.CHANGED,
				specDiff = diff,
			),
		)

		val result = ImportConverter.merge(
			existingProject = project,
			acceptedEntries = entries,
			updateSelection = UpdateSelection(body = true),
		)

		val endpoint = result.endpoints.single()
		assertEquals("My Custom Alias", endpoint.alias)
		assertEquals("My custom description", endpoint.description)
	}
}
