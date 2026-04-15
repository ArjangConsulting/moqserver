package com.moqserver.studio.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportConverterImportModeTest {
	@Test
	fun `startUpdateFromSpec classifies new endpoints correctly`() {
		val existingEndpoint = makeEndpoint(path = "/items")
		val vm = StudioRootViewModel()
		vm.projectLoaded(makeProject(existingEndpoint))

		val spec = ParsedSpec(
			title = "Test",
			version = "1.0",
			endpoints = listOf(parsedEndpoint(path = "/items"), parsedEndpoint(path = "/users")),
		)

		vm.startUpdateFromSpec(spec, ImportSourceType.OPENAPI, "test.yaml")

		val entries = vm.state.value.importState!!.entries
		val itemsEntry = entries.find { it.endpoint.path == "/items" }!!
		val usersEntry = entries.find { it.endpoint.path == "/users" }!!

		assertEquals(EndpointUpdateStatus.UNCHANGED, itemsEntry.updateStatus)
		assertFalse(itemsEntry.accepted)
		assertEquals(EndpointUpdateStatus.NEW, usersEntry.updateStatus)
		assertTrue(usersEntry.accepted)
	}

	@Test
	fun `startUpdateFromSpec classifies changed endpoints and pre-selects them`() {
		val existingEndpoint = makeEndpoint(path = "/items", statusCodes = listOf(200))
		val vm = StudioRootViewModel()
		vm.projectLoaded(makeProject(existingEndpoint))

		val spec = ParsedSpec(
			title = "Test",
			version = "1.0",
			endpoints = listOf(parsedEndpoint(path = "/items", statusCodes = listOf(200, 404))),
		)

		vm.startUpdateFromSpec(spec, ImportSourceType.OPENAPI, "test.yaml")

		val entry = vm.state.value.importState!!.entries.single()
		assertEquals(EndpointUpdateStatus.CHANGED, entry.updateStatus)
		assertTrue(entry.accepted)
		assertTrue(entry.specDiff?.newStatusCodes?.contains(404) == true)
	}

	@Test
	fun `startUpdateFromSpec respects previously deselected IDs`() {
		val vm = StudioRootViewModel()
		vm.projectLoaded(makeProject(makeEndpoint(path = "/items")))

		val spec = ParsedSpec(title = "Test", version = "1.0", endpoints = listOf(parsedEndpoint(path = "/users")))
		val previouslyDeselected = setOf(ImportConverter.endpointId("GET", "/users"))

		vm.startUpdateFromSpec(spec, ImportSourceType.OPENAPI, "test.yaml", previouslyDeselected)

		val entry = vm.state.value.importState!!.entries.single()
		assertEquals(EndpointUpdateStatus.NEW, entry.updateStatus)
		assertFalse(entry.accepted)
	}

	@Test
	fun `startUpdateFromSpec sets update mode`() {
		val vm = StudioRootViewModel()
		vm.projectLoaded(makeProject(makeEndpoint()))

		vm.startUpdateFromSpec(
			ParsedSpec(title = "T", version = "1", endpoints = emptyList()),
			ImportSourceType.OPENAPI,
			"test.yaml",
		)

		assertTrue(vm.state.value.importState!!.isUpdateMode)
	}

	@Test
	fun `confirmImport in update mode merges into existing project`() {
		val existing = makeEndpoint(path = "/items", statusCodes = listOf(200))
		val vm = StudioRootViewModel()
		vm.projectLoaded(makeProject(existing, path = "/tmp/myproject"))

		val spec = ParsedSpec(title = "T", version = "1", endpoints = listOf(parsedEndpoint(path = "/users")))
		vm.startUpdateFromSpec(spec, ImportSourceType.OPENAPI, "test.yaml")

		val project = vm.confirmImport("/tmp/myproject")
		requireNotNull(project)

		assertEquals(2, project.endpoints.size)
		assertTrue(project.endpoints.any { it.path == "/items" })
		assertTrue(project.endpoints.any { it.path == "/users" })
	}

	@Test
	fun `confirmImport new-project mode creates project from scratch`() {
		val vm = StudioRootViewModel()
		val spec = ParsedSpec(title = "My API", version = "2.0", endpoints = listOf(parsedEndpoint(path = "/pets")))
		vm.startImport(spec, ImportSourceType.OPENAPI, "pets.yaml")

		val project = vm.confirmImport("/tmp/pets-project")

		assertEquals("My API", project?.manifest?.name)
		assertEquals(1, project?.endpoints?.size)
	}
}
