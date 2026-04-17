package com.moqserver.studio.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportConverterUpdateWorkflowTest {
	@Test
	fun `startUpdateFromSpec is a no-op when no project is loaded`() {
		val vm = StudioRootViewModel()
		vm.startUpdateFromSpec(
			ParsedSpec(title = "T", version = "1", endpoints = listOf(parsedEndpoint())),
			ImportSourceType.OPENAPI,
			"test.yaml",
		)
		assertNull(vm.state.value.importState)
	}

	@Test
	fun `startUpdateFromSpec with empty spec produces no entries`() {
		val vm = StudioRootViewModel()
		vm.projectLoaded(makeProject(makeEndpoint()))
		vm.startUpdateFromSpec(
			ParsedSpec(title = "T", version = "1", endpoints = emptyList()),
			ImportSourceType.OPENAPI,
			"empty.yaml",
		)

		val importState = vm.state.value.importState!!
		assertTrue(importState.entries.isEmpty())
		assertTrue(importState.isUpdateMode)
	}

	@Test
	fun `startUpdateFromSpec sets projectName to existing manifest name`() {
		val vm = StudioRootViewModel()
		vm.projectLoaded(makeProject(makeEndpoint()))
		vm.startUpdateFromSpec(
			ParsedSpec(title = "Different Title", version = "1", endpoints = emptyList()),
			ImportSourceType.OPENAPI,
			"test.yaml",
		)
		assertEquals("Test Project", vm.state.value.importState!!.projectName)
	}

	@Test
	fun `startUpdateFromSpec status line reflects new changed unchanged counts`() {
		val existing = makeEndpoint(path = "/items", statusCodes = listOf(200))
		val vm = StudioRootViewModel()
		vm.projectLoaded(makeProject(existing))

		val spec = ParsedSpec(
			title = "T",
			version = "1",
			endpoints = listOf(
				parsedEndpoint(path = "/users"),
				parsedEndpoint(path = "/items", statusCodes = listOf(200, 404)),
			),
		)
		vm.startUpdateFromSpec(spec, ImportSourceType.OPENAPI, "spec.yaml")

		val status = vm.state.value.statusLine
		assertTrue(status.contains("1 new"))
		assertTrue(status.contains("1 changed"))
		assertTrue(status.contains("0 unchanged"))
	}

	@Test
	fun `startUpdateFromSpec CHANGED endpoint with previouslyDeselected is pre-deselected`() {
		val existing = makeEndpoint(path = "/items", statusCodes = listOf(200))
		val vm = StudioRootViewModel()
		vm.projectLoaded(makeProject(existing))

		val changedSpec = parsedEndpoint(path = "/items", statusCodes = listOf(200, 404))
		val previouslyDeselected = setOf(ImportConverter.endpointId("GET", "/items"))

		vm.startUpdateFromSpec(
			ParsedSpec(title = "T", version = "1", endpoints = listOf(changedSpec)),
			ImportSourceType.OPENAPI,
			"test.yaml",
			previouslyDeselected,
		)

		val entry = vm.state.value.importState!!.entries.single()
		assertEquals(EndpointUpdateStatus.CHANGED, entry.updateStatus)
		assertFalse(entry.accepted)
	}

	@Test
	fun `collectDeselectedImportIds returns empty when no import active`() {
		assertEquals(emptySet(), StudioRootViewModel().collectDeselectedImportIds())
	}

	@Test
	fun `collectDeselectedImportIds returns empty for new-project mode`() {
		val vm = StudioRootViewModel()
		vm.startImport(
			ParsedSpec(title = "T", version = "1", endpoints = listOf(parsedEndpoint())),
			ImportSourceType.OPENAPI,
			"test.yaml",
		)
		assertEquals(emptySet(), vm.collectDeselectedImportIds())
	}

	@Test
	fun `collectDeselectedImportIds returns empty when all are accepted`() {
		val vm = StudioRootViewModel()
		vm.projectLoaded(makeProject(makeEndpoint(path = "/items")))
		vm.startUpdateFromSpec(
			ParsedSpec(title = "T", version = "1", endpoints = listOf(parsedEndpoint(path = "/users"))),
			ImportSourceType.OPENAPI,
			"test.yaml",
		)
		assertEquals(emptySet(), vm.collectDeselectedImportIds())
	}

	@Test
	fun `collectDeselectedImportIds returns IDs of deselected NEW endpoints`() {
		val vm = StudioRootViewModel()
		vm.projectLoaded(makeProject(makeEndpoint(path = "/items")))
		vm.startUpdateFromSpec(
			ParsedSpec(title = "T", version = "1", endpoints = listOf(parsedEndpoint(path = "/users"))),
			ImportSourceType.OPENAPI,
			"test.yaml",
		)
		vm.toggleImportEndpoint(0)
		assertEquals(setOf(ImportConverter.endpointId("GET", "/users")), vm.collectDeselectedImportIds())
	}

	@Test
	fun `collectDeselectedImportIds returns IDs of deselected CHANGED endpoints`() {
		val vm = StudioRootViewModel()
		vm.projectLoaded(makeProject(makeEndpoint(path = "/items", statusCodes = listOf(200))))
		vm.startUpdateFromSpec(
			ParsedSpec(
				title = "T",
				version = "1",
				endpoints = listOf(parsedEndpoint(path = "/items", statusCodes = listOf(200, 404))),
			),
			ImportSourceType.OPENAPI,
			"test.yaml",
		)
		vm.toggleImportEndpoint(0)
		assertEquals(setOf(ImportConverter.endpointId("GET", "/items")), vm.collectDeselectedImportIds())
	}

	@Test
	fun `collectDeselectedImportIds excludes UNCHANGED endpoints even when deselected`() {
		val vm = StudioRootViewModel()
		vm.projectLoaded(makeProject(makeEndpoint(path = "/items")))
		vm.startUpdateFromSpec(
			ParsedSpec(title = "T", version = "1", endpoints = listOf(parsedEndpoint(path = "/items"))),
			ImportSourceType.OPENAPI,
			"test.yaml",
		)
		assertEquals(emptySet(), vm.collectDeselectedImportIds())
	}

	@Test
	fun `confirmImport in update mode clears importState`() {
		val vm = StudioRootViewModel()
		vm.projectLoaded(makeProject(makeEndpoint(path = "/items"), path = "/tmp/proj"))
		vm.startUpdateFromSpec(
			ParsedSpec(title = "T", version = "1", endpoints = listOf(parsedEndpoint(path = "/users"))),
			ImportSourceType.OPENAPI,
			"test.yaml",
		)
		vm.confirmImport("/tmp/proj")
		assertNull(vm.state.value.importState)
	}

	@Test
	fun `confirmImport in update mode marks project dirty`() {
		val vm = StudioRootViewModel()
		vm.projectLoaded(makeProject(makeEndpoint(path = "/items"), path = "/tmp/proj"))
		vm.startUpdateFromSpec(
			ParsedSpec(title = "T", version = "1", endpoints = listOf(parsedEndpoint(path = "/users"))),
			ImportSourceType.OPENAPI,
			"test.yaml",
		)
		vm.confirmImport("/tmp/proj")
		assertTrue(vm.state.value.isDirty)
	}

	@Test
	fun `undo after imported update keeps project dirty`() {
		val vm = StudioRootViewModel()
		val originalProject = makeProject(makeEndpoint(path = "/items"), path = "/tmp/proj")
		vm.projectLoaded(originalProject)
		vm.startUpdateFromSpec(
			ParsedSpec(title = "T", version = "1", endpoints = listOf(parsedEndpoint(path = "/users"))),
			ImportSourceType.OPENAPI,
			"test.yaml",
		)

		vm.confirmImport("/tmp/proj")
		val importedProject = vm.state.value.project!!
		vm.updateManifest(importedProject.manifest.copy(name = "Changed Project"))
		vm.undo()

		assertEquals(importedProject, vm.state.value.project)
		assertTrue(vm.state.value.isDirty)
	}

	@Test
	fun `confirmImport in update mode returns null when all entries are deselected`() {
		val vm = StudioRootViewModel()
		vm.projectLoaded(makeProject(makeEndpoint(path = "/items"), path = "/tmp/proj"))
		vm.startUpdateFromSpec(
			ParsedSpec(title = "T", version = "1", endpoints = listOf(parsedEndpoint(path = "/users"))),
			ImportSourceType.OPENAPI,
			"test.yaml",
		)
		vm.setAllImportEndpoints(false)
		assertNull(vm.confirmImport("/tmp/proj"))
	}

	@Test
	fun `confirmImport in update mode selects first endpoint`() {
		val vm = StudioRootViewModel()
		vm.projectLoaded(makeProject(makeEndpoint(path = "/items"), path = "/tmp/proj"))
		vm.startUpdateFromSpec(
			ParsedSpec(title = "T", version = "1", endpoints = listOf(parsedEndpoint(path = "/users"))),
			ImportSourceType.OPENAPI,
			"test.yaml",
		)
		vm.confirmImport("/tmp/proj")
		assertEquals(vm.state.value.project?.endpoints?.firstOrNull()?.id, vm.state.value.selectedEndpointId)
	}
}
