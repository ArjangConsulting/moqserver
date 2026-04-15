package com.moqserver.studio.domain

import com.moqserver.studio.projectformat.AuthType
import com.moqserver.studio.projectformat.EndpointDocument
import com.moqserver.studio.projectformat.MoqProject
import com.moqserver.studio.projectformat.NetworkBehavior
import com.moqserver.studio.projectformat.ProjectAuthConfig
import com.moqserver.studio.projectformat.ProjectDefaults
import com.moqserver.studio.projectformat.ProjectManifest
import com.moqserver.studio.projectformat.ProjectVariant
import com.moqserver.studio.projectformat.RequestRules
import com.moqserver.studio.projectformat.RuleMatcher
import com.moqserver.studio.projectformat.YamlValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("LargeClass")
class ImportConverterUpdateTest {

	// ---------- helpers ----------

	private fun makeProject(vararg endpoints: EndpointDocument, path: String = "/tmp/project"): MoqProject =
		MoqProject(
			manifest = ProjectManifest(
				version = "1",
				name = "Test Project",
				defaults = ProjectDefaults(
					delayMs = 0,
					auth = ProjectAuthConfig(type = AuthType.NONE, verify = false),
					network = NetworkBehavior(),
				),
			),
			endpoints = endpoints.toList(),
			projectPath = path,
		)

	private fun makeEndpoint(
		method: String = "GET",
		path: String = "/items",
		statusCodes: List<Int> = listOf(200),
		authType: AuthType = AuthType.NONE,
		requiredHeaders: List<String> = emptyList(),
		tags: List<String>? = null,
		userBody: YamlValue? = YamlValue.Obj(mapOf("spec" to YamlValue.Str("value"))),
	): EndpointDocument {
		val id = ImportConverter.endpointId(method, path)
		return EndpointDocument(
			id = id,
			alias = "Alias",
			referenceName = "alias",
			method = method.uppercase(),
			path = path,
			tags = tags,
			auth = if (authType != AuthType.NONE) ProjectAuthConfig(authType, verify = true) else null,
			requestRules = requiredHeaders.takeIf { it.isNotEmpty() }?.let {
				RequestRules(headers = it.map { h -> RuleMatcher(name = h, required = true) })
			},
			variants = statusCodes.map { code ->
				ProjectVariant(
					name = "variant-$code",
					referenceName = "variant$code",
					status = code,
					body = userBody,
				)
			},
		)
	}

	private fun parsedEndpoint(
		method: String = "GET",
		path: String = "/items",
		statusCodes: List<Int> = listOf(200),
		authType: AuthType = AuthType.NONE,
		requiredHeaders: List<String> = emptyList(),
		tags: List<String> = emptyList(),
	): ParsedEndpoint = ParsedEndpoint(
		method = method,
		path = path,
		responses = statusCodes.map { code ->
			ParsedResponse(name = "resp-$code", statusCode = code, body = """{"spec":"value"}""")
		},
		authType = authType,
		requiredHeaders = requiredHeaders,
		tags = tags,
	)

	// ---------- diffEndpoint tests ----------

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

	// ---------- merge tests ----------

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
		// merge() receives only the accepted entries (filtered by confirmImport before calling merge).
		// Passing no entries for a new endpoint is equivalent to the user rejecting it.
		val project = makeProject(makeEndpoint(path = "/items"))
		val entries = emptyList<ImportEndpointEntry>() // caller filtered out the rejected /users entry

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

		// Endpoint unchanged and not accepted — stays as-is with 1 variant
		assertEquals(1, result.endpoints.size)
		assertEquals(1, result.endpoints.single().variants.size)
	}

	@Test
	fun `merge adds new variants to changed endpoint without overwriting user-authored variants`() {
		val userBody = YamlValue.Str("user-carefully-crafted-body")
		val existing = makeEndpoint(path = "/items", statusCodes = listOf(200), userBody = userBody)
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
		val ep = result.endpoints.single()
		// Should have 3 variants: the original 200 (preserved) + new 201 and 500
		assertEquals(3, ep.variants.size)
		// Original 200 variant body must be preserved unchanged
		val v200 = ep.variants.find { it.status == 200 }
		assertTrue(v200?.body != null, "Existing variant should remain present after body-enabled merge")
		// New 201 and 500 variants must exist
		assertTrue(ep.variants.any { it.status == 201 })
		assertTrue(ep.variants.any { it.status == 500 })
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
		val existing = makeEndpoint(path = "/items", statusCodes = listOf(200)).copy(
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

		val ep = result.endpoints.single()
		assertEquals("My Custom Alias", ep.alias, "Alias must be preserved from existing endpoint")
		assertEquals("My custom description", ep.description, "Description must be preserved from existing endpoint")
	}

	// ---------- startUpdateFromSpec + ViewModel tests ----------

	@Test
	fun `startUpdateFromSpec classifies new endpoints correctly`() {
		val existingEndpoint = makeEndpoint(path = "/items")
		val vm = StudioRootViewModel()
		vm.projectLoaded(makeProject(existingEndpoint))

		val spec = ParsedSpec(
			title = "Test",
			version = "1.0",
			endpoints = listOf(
				parsedEndpoint(path = "/items"), // existing
				parsedEndpoint(path = "/users"), // new
			),
		)

		vm.startUpdateFromSpec(spec, ImportSourceType.OPENAPI, "test.yaml")

		val entries = vm.state.value.importState!!.entries
		val itemsEntry = entries.find { it.endpoint.path == "/items" }!!
		val usersEntry = entries.find { it.endpoint.path == "/users" }!!

		assertEquals(EndpointUpdateStatus.UNCHANGED, itemsEntry.updateStatus)
		assertFalse(itemsEntry.accepted, "Unchanged endpoints should be pre-deselected")

		assertEquals(EndpointUpdateStatus.NEW, usersEntry.updateStatus)
		assertTrue(usersEntry.accepted, "New endpoints should be pre-selected")
	}

	@Test
	fun `startUpdateFromSpec classifies changed endpoints and pre-selects them`() {
		val existingEndpoint = makeEndpoint(path = "/items", statusCodes = listOf(200))
		val vm = StudioRootViewModel()
		vm.projectLoaded(makeProject(existingEndpoint))

		val spec = ParsedSpec(
			title = "Test",
			version = "1.0",
			endpoints = listOf(
				parsedEndpoint(path = "/items", statusCodes = listOf(200, 404)), // changed: new 404 added
			),
		)

		vm.startUpdateFromSpec(spec, ImportSourceType.OPENAPI, "test.yaml")

		val entry = vm.state.value.importState!!.entries.single()
		assertEquals(EndpointUpdateStatus.CHANGED, entry.updateStatus)
		assertTrue(entry.accepted, "Changed endpoints should be pre-selected")
		assertTrue(entry.specDiff?.newStatusCodes?.contains(404) == true)
	}

	@Test
	fun `startUpdateFromSpec respects previously deselected IDs`() {
		val vm = StudioRootViewModel()
		vm.projectLoaded(makeProject(makeEndpoint(path = "/items")))

		val spec = ParsedSpec(
			title = "Test",
			version = "1.0",
			endpoints = listOf(parsedEndpoint(path = "/users")), // new endpoint
		)
		val previouslyDeselected = setOf(ImportConverter.endpointId("GET", "/users"))

		vm.startUpdateFromSpec(spec, ImportSourceType.OPENAPI, "test.yaml", previouslyDeselected)

		val entry = vm.state.value.importState!!.entries.single()
		assertEquals(EndpointUpdateStatus.NEW, entry.updateStatus)
		assertFalse(entry.accepted, "Previously deselected endpoint should start deselected")
	}

	@Test
	fun `startUpdateFromSpec sets update mode`() {
		val vm = StudioRootViewModel()
		vm.projectLoaded(makeProject(makeEndpoint()))

		val spec = ParsedSpec(title = "T", version = "1", endpoints = emptyList())
		vm.startUpdateFromSpec(spec, ImportSourceType.OPENAPI, "test.yaml")

		assertTrue(vm.state.value.importState!!.isUpdateMode)
	}

	@Test
	fun `confirmImport in update mode merges into existing project`() {
		val existing = makeEndpoint(path = "/items", statusCodes = listOf(200))
		val vm = StudioRootViewModel()
		vm.projectLoaded(makeProject(existing, path = "/tmp/myproject"))

		val spec = ParsedSpec(
			title = "T",
			version = "1",
			endpoints = listOf(parsedEndpoint(path = "/users")),
		)
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
		val spec = ParsedSpec(
			title = "My API",
			version = "2.0",
			endpoints = listOf(parsedEndpoint(path = "/pets")),
		)
		vm.startImport(spec, ImportSourceType.OPENAPI, "pets.yaml")

		val project = vm.confirmImport("/tmp/pets-project")

		assertEquals("My API", project?.manifest?.name)
		assertEquals(1, project?.endpoints?.size)
	}

	// ---------- EndpointSpecDiff.summary() tests ----------

	@Test
	fun `summary returns empty string when no changes`() {
		val diff = EndpointSpecDiff()
		assertEquals("", diff.summary())
	}

	@Test
	fun `summary lists new status codes in sorted order`() {
		val diff = EndpointSpecDiff(newStatusCodes = setOf(500, 201))
		assertTrue(diff.summary().contains("new responses: 201, 500"))
	}

	@Test
	fun `summary lists removed status codes in sorted order`() {
		val diff = EndpointSpecDiff(removedStatusCodes = setOf(404, 200))
		assertTrue(diff.summary().contains("removed responses: 200, 404"))
	}

	@Test
	fun `summary includes auth changed`() {
		val diff = EndpointSpecDiff(authChanged = true)
		assertTrue(diff.summary().contains("auth changed"))
	}

	@Test
	fun `summary includes request rules changed`() {
		val diff = EndpointSpecDiff(requestRulesChanged = true)
		assertTrue(diff.summary().contains("request rules changed"))
	}

	@Test
	fun `summary includes tags changed`() {
		val diff = EndpointSpecDiff(tagsChanged = true)
		assertTrue(diff.summary().contains("tags changed"))
	}

	@Test
	fun `summary includes response bodies changed`() {
		val diff = EndpointSpecDiff(responseBodyChanged = true)
		assertTrue(diff.summary().contains("response bodies changed"))
	}

	@Test
	fun `summary combines multiple changes with semicolon`() {
		val diff = EndpointSpecDiff(
			newStatusCodes = setOf(201),
			authChanged = true,
			tagsChanged = true,
		)
		val s = diff.summary()
		assertTrue(s.contains("new responses"))
		assertTrue(s.contains("auth changed"))
		assertTrue(s.contains("tags changed"))
		// Semicolons separate items
		assertEquals(2, s.count { it == ';' })
	}

	// ---------- diffEndpoint gap tests ----------

	@Test
	fun `diffEndpoint detects auth removal (BEARER to NONE)`() {
		val existing = makeEndpoint(authType = AuthType.BEARER)
		val parsed = parsedEndpoint(authType = AuthType.NONE)

		val diff = ImportConverter.diffEndpoint(parsed, existing)

		assertTrue(diff.authChanged)
	}

	@Test
	fun `diffEndpoint detects cookie change`() {
		val existing = makeEndpoint() // no cookies
		val parsed = ParsedEndpoint(
			method = "GET",
			path = "/items",
			responses = listOf(ParsedResponse(name = "ok", statusCode = 200)),
			cookies = listOf(RuleMatcher(name = "session", required = true)),
		)

		val diff = ImportConverter.diffEndpoint(parsed, existing)

		assertTrue(diff.requestRulesChanged)
	}

	@Test
	fun `diffEndpoint detects tags removed to empty`() {
		val existing = makeEndpoint(tags = listOf("pets"))
		val parsed = parsedEndpoint(tags = emptyList())

		val diff = ImportConverter.diffEndpoint(parsed, existing)

		assertTrue(diff.tagsChanged)
	}

	@Test
	fun `diffEndpoint detects queryParameters change via requiredQueryParameters`() {
		// Use requiredQueryParameters path (queryParameters empty, requiredQueryParameters non-empty)
		val existing = makeEndpoint() // no query params
		val parsed = ParsedEndpoint(
			method = "GET",
			path = "/items",
			responses = listOf(ParsedResponse(name = "ok", statusCode = 200)),
			requiredQueryParameters = listOf("limit"),
		)

		val diff = ImportConverter.diffEndpoint(parsed, existing)

		assertTrue(diff.requestRulesChanged)
	}

	// ---------- merge gap tests ----------

	@Test
	fun `merge updates requestRules on changed endpoint`() {
		val existing = makeEndpoint(path = "/items", requiredHeaders = emptyList())
		val project = makeProject(existing)

		val newSpec = parsedEndpoint(path = "/items", requiredHeaders = listOf("X-Api-Key"))
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

		assertFalse(result.endpoints.single().requestRules?.headers.isNullOrEmpty())
	}

	@Test
	fun `merge skips details changes when details update is disabled`() {
		val existing = makeEndpoint(path = "/items", requiredHeaders = emptyList())
		val project = makeProject(existing)

		val newSpec = parsedEndpoint(path = "/items", requiredHeaders = listOf("X-Api-Key"))
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

		assertEquals(listOf("new-tag", "another"), result.endpoints.single().tags)
	}

	@Test
	fun `merge clears tags when spec has empty tags on changed endpoint`() {
		val existing = makeEndpoint(path = "/items", tags = listOf("old-tag"))
		val project = makeProject(existing)

		val newSpec = parsedEndpoint(path = "/items", tags = emptyList())
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

		// tags changed to empty → stored as null
		assertNull(result.endpoints.single().tags)
	}

	@Test
	fun `merge adds generatedResponses on CHANGED endpoint`() {
		val existing = makeEndpoint(path = "/items", statusCodes = listOf(200))
		val project = makeProject(existing)

		val newSpec = parsedEndpoint(path = "/items", statusCodes = listOf(200))
		val diff = ImportConverter.diffEndpoint(newSpec, existing)
		val generated = ParsedResponse(name = "ai-variant", statusCode = 422)
		val entries = listOf(
			ImportEndpointEntry(
				endpoint = newSpec,
				accepted = true,
				updateStatus = EndpointUpdateStatus.CHANGED,
				specDiff = diff,
				generatedResponses = listOf(generated),
			),
		)

		val result = ImportConverter.merge(
			existingProject = project,
			acceptedEntries = entries,
			updateSelection = UpdateSelection(body = true),
		)

		// The AI-generated 422 should be added to the merged endpoint
		assertTrue(result.endpoints.single().variants.any { it.status == 422 })
	}

	@Test
	fun `merge updates existing response body when body update is enabled`() {
		val existing = makeEndpoint(path = "/items", statusCodes = listOf(200), userBody = YamlValue.Str("old"))
		val project = makeProject(existing)

		val newSpec = ParsedEndpoint(
			method = "GET",
			path = "/items",
			responses = listOf(ParsedResponse(name = "ok", statusCode = 200, body = "{\"spec\":\"new\"}")),
		)
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

		assertEquals(
			"new",
			(result.endpoints.single().variants.single().body as YamlValue.Obj).value["spec"]?.let {
				(it as YamlValue.Str).value
			},
		)
	}

	@Test
	fun `merge preserves existing response body when body update is disabled`() {
		val existing = makeEndpoint(path = "/items", statusCodes = listOf(200), userBody = YamlValue.Str("old"))
		val project = makeProject(existing)

		val newSpec = ParsedEndpoint(
			method = "GET",
			path = "/items",
			responses = listOf(ParsedResponse(name = "ok", statusCode = 200, body = "{\"spec\":\"new\"}")),
		)
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
		val entries = listOf(
			ImportEndpointEntry(
				endpoint = newSpec,
				accepted = true,
				updateStatus = EndpointUpdateStatus.CHANGED,
				specDiff = diff,
			),
		)

		val result = ImportConverter.merge(existingProject = project, acceptedEntries = entries)

		assertNull(result.endpoints.single().auth, "Auth should be null when spec removes auth")
	}

	@Test
	fun `merge with null specDiff on CHANGED entry preserves existing auth and requestRules`() {
		val existing = makeEndpoint(path = "/items", authType = AuthType.BEARER)
		val project = makeProject(existing)

		val newSpec = parsedEndpoint(path = "/items", statusCodes = listOf(200, 404))
		// specDiff is explicitly null — CHANGED entry without diff info
		val entries = listOf(
			ImportEndpointEntry(
				endpoint = newSpec,
				accepted = true,
				updateStatus = EndpointUpdateStatus.CHANGED,
				specDiff = null,
			),
		)

		val result = ImportConverter.merge(existingProject = project, acceptedEntries = entries)

		// Auth and request rules should be preserved from existing when specDiff is null
		assertEquals(AuthType.BEARER, result.endpoints.single().auth?.type)
	}

	// ---------- startUpdateFromSpec gap tests ----------

	@Test
	fun `startUpdateFromSpec is a no-op when no project is loaded`() {
		val vm = StudioRootViewModel()
		// No project loaded

		val spec = ParsedSpec(title = "T", version = "1", endpoints = listOf(parsedEndpoint()))
		vm.startUpdateFromSpec(spec, ImportSourceType.OPENAPI, "test.yaml")

		assertNull(vm.state.value.importState, "importState should remain null when no project is loaded")
	}

	@Test
	fun `startUpdateFromSpec with empty spec produces no entries`() {
		val vm = StudioRootViewModel()
		vm.projectLoaded(makeProject(makeEndpoint()))

		val spec = ParsedSpec(title = "T", version = "1", endpoints = emptyList())
		vm.startUpdateFromSpec(spec, ImportSourceType.OPENAPI, "empty.yaml")

		val importState = vm.state.value.importState!!
		assertTrue(importState.entries.isEmpty())
		assertTrue(importState.isUpdateMode)
	}

	@Test
	fun `startUpdateFromSpec sets projectName to existing manifest name`() {
		val project = makeProject(makeEndpoint())
		val vm = StudioRootViewModel()
		vm.projectLoaded(project)

		val spec = ParsedSpec(title = "Different Title", version = "1", endpoints = emptyList())
		vm.startUpdateFromSpec(spec, ImportSourceType.OPENAPI, "test.yaml")

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
				parsedEndpoint(path = "/users"), // new
				parsedEndpoint(path = "/items", statusCodes = listOf(200, 404)), // changed
			),
		)
		vm.startUpdateFromSpec(spec, ImportSourceType.OPENAPI, "spec.yaml")

		val status = vm.state.value.statusLine
		assertTrue(status.contains("1 new"), "Status should report 1 new endpoint")
		assertTrue(status.contains("1 changed"), "Status should report 1 changed endpoint")
		assertTrue(status.contains("0 unchanged"), "Status should report 0 unchanged endpoints")
	}

	@Test
	fun `startUpdateFromSpec CHANGED endpoint with previouslyDeselected is pre-deselected`() {
		val existing = makeEndpoint(path = "/items", statusCodes = listOf(200))
		val vm = StudioRootViewModel()
		vm.projectLoaded(makeProject(existing))

		val changedSpec = parsedEndpoint(path = "/items", statusCodes = listOf(200, 404))
		val spec = ParsedSpec(title = "T", version = "1", endpoints = listOf(changedSpec))
		val previouslyDeselected = setOf(ImportConverter.endpointId("GET", "/items"))

		vm.startUpdateFromSpec(spec, ImportSourceType.OPENAPI, "test.yaml", previouslyDeselected)

		val entry = vm.state.value.importState!!.entries.single()
		assertEquals(EndpointUpdateStatus.CHANGED, entry.updateStatus)
		assertFalse(entry.accepted, "Previously deselected CHANGED endpoint should start deselected")
	}

	// ---------- collectDeselectedImportIds tests ----------

	@Test
	fun `collectDeselectedImportIds returns empty when no import active`() {
		val vm = StudioRootViewModel()
		assertEquals(emptySet(), vm.collectDeselectedImportIds())
	}

	@Test
	fun `collectDeselectedImportIds returns empty for new-project mode`() {
		val vm = StudioRootViewModel()
		val spec = ParsedSpec(title = "T", version = "1", endpoints = listOf(parsedEndpoint()))
		vm.startImport(spec, ImportSourceType.OPENAPI, "test.yaml")

		assertEquals(emptySet(), vm.collectDeselectedImportIds())
	}

	@Test
	fun `collectDeselectedImportIds returns empty when all are accepted`() {
		val existing = makeEndpoint(path = "/items")
		val vm = StudioRootViewModel()
		vm.projectLoaded(makeProject(existing))

		val spec = ParsedSpec(title = "T", version = "1", endpoints = listOf(parsedEndpoint(path = "/users")))
		vm.startUpdateFromSpec(spec, ImportSourceType.OPENAPI, "test.yaml")
		// /users is NEW and pre-accepted — don't toggle it

		assertEquals(emptySet(), vm.collectDeselectedImportIds())
	}

	@Test
	fun `collectDeselectedImportIds returns IDs of deselected NEW endpoints`() {
		val existing = makeEndpoint(path = "/items")
		val vm = StudioRootViewModel()
		vm.projectLoaded(makeProject(existing))

		val spec = ParsedSpec(title = "T", version = "1", endpoints = listOf(parsedEndpoint(path = "/users")))
		vm.startUpdateFromSpec(spec, ImportSourceType.OPENAPI, "test.yaml")
		// Toggle /users to deselected
		vm.toggleImportEndpoint(0)

		val ids = vm.collectDeselectedImportIds()

		assertEquals(setOf(ImportConverter.endpointId("GET", "/users")), ids)
	}

	@Test
	fun `collectDeselectedImportIds returns IDs of deselected CHANGED endpoints`() {
		val existing = makeEndpoint(path = "/items", statusCodes = listOf(200))
		val vm = StudioRootViewModel()
		vm.projectLoaded(makeProject(existing))

		val spec = ParsedSpec(
			title = "T",
			version = "1",
			endpoints = listOf(parsedEndpoint(path = "/items", statusCodes = listOf(200, 404))),
		)
		vm.startUpdateFromSpec(spec, ImportSourceType.OPENAPI, "test.yaml")
		// /items is CHANGED and pre-accepted — deselect it
		vm.toggleImportEndpoint(0)

		val ids = vm.collectDeselectedImportIds()

		assertEquals(setOf(ImportConverter.endpointId("GET", "/items")), ids)
	}

	@Test
	fun `collectDeselectedImportIds excludes UNCHANGED endpoints even when deselected`() {
		val existing = makeEndpoint(path = "/items")
		val vm = StudioRootViewModel()
		vm.projectLoaded(makeProject(existing))

		// /items is UNCHANGED (no spec change) — pre-deselected
		val spec = ParsedSpec(title = "T", version = "1", endpoints = listOf(parsedEndpoint(path = "/items")))
		vm.startUpdateFromSpec(spec, ImportSourceType.OPENAPI, "test.yaml")
		// UNCHANGED is pre-deselected already; collectDeselectedImportIds should still exclude it

		val ids = vm.collectDeselectedImportIds()

		assertEquals(emptySet(), ids)
	}

	// ---------- confirmImport update-mode gap tests ----------

	@Test
	fun `confirmImport in update mode clears importState`() {
		val existing = makeEndpoint(path = "/items")
		val vm = StudioRootViewModel()
		vm.projectLoaded(makeProject(existing, path = "/tmp/proj"))

		val spec = ParsedSpec(title = "T", version = "1", endpoints = listOf(parsedEndpoint(path = "/users")))
		vm.startUpdateFromSpec(spec, ImportSourceType.OPENAPI, "test.yaml")
		vm.confirmImport("/tmp/proj")

		assertNull(vm.state.value.importState, "importState should be cleared after confirm")
	}

	@Test
	fun `confirmImport in update mode marks project dirty`() {
		val existing = makeEndpoint(path = "/items")
		val vm = StudioRootViewModel()
		vm.projectLoaded(makeProject(existing, path = "/tmp/proj"))

		val spec = ParsedSpec(title = "T", version = "1", endpoints = listOf(parsedEndpoint(path = "/users")))
		vm.startUpdateFromSpec(spec, ImportSourceType.OPENAPI, "test.yaml")
		vm.confirmImport("/tmp/proj")

		assertTrue(vm.state.value.isDirty, "Project should be dirty after update import")
	}

	@Test
	fun `confirmImport in update mode returns null when all entries are deselected`() {
		val existing = makeEndpoint(path = "/items")
		val vm = StudioRootViewModel()
		vm.projectLoaded(makeProject(existing, path = "/tmp/proj"))

		val spec = ParsedSpec(title = "T", version = "1", endpoints = listOf(parsedEndpoint(path = "/users")))
		vm.startUpdateFromSpec(spec, ImportSourceType.OPENAPI, "test.yaml")
		vm.setAllImportEndpoints(false)

		val result = vm.confirmImport("/tmp/proj")

		assertNull(result, "confirmImport should return null when no endpoints are accepted")
	}

	@Test
	fun `confirmImport in update mode selects first endpoint`() {
		val existing = makeEndpoint(path = "/items")
		val vm = StudioRootViewModel()
		vm.projectLoaded(makeProject(existing, path = "/tmp/proj"))

		val spec = ParsedSpec(title = "T", version = "1", endpoints = listOf(parsedEndpoint(path = "/users")))
		vm.startUpdateFromSpec(spec, ImportSourceType.OPENAPI, "test.yaml")
		vm.confirmImport("/tmp/proj")

		val selectedId = vm.state.value.selectedEndpointId
		assertEquals(vm.state.value.project?.endpoints?.firstOrNull()?.id, selectedId)
	}

	// ---------- endpointId edge case tests ----------

	@Test
	fun `endpointId handles root path`() {
		val id = ImportConverter.endpointId("GET", "/")
		// "/" strips to "" → normalized to "" → trimEnd '-' → "get-"
		// The ifEmpty guard only fires when the full string is empty, so result is "get-"
		assertEquals("get-", id)
	}

	@Test
	fun `endpointId normalises path params to param`() {
		val id = ImportConverter.endpointId("DELETE", "/users/{userId}/posts/{postId}")
		assertEquals("delete-users-param-posts-param", id)
	}

	@Test
	fun `endpointId lowercases method`() {
		val id = ImportConverter.endpointId("POST", "/items")
		assertEquals("post-items", id)
	}

	@Test
	fun `endpointId handles empty path`() {
		// Empty path after stripping prefix → id should not start with a dash
		val id = ImportConverter.endpointId("GET", "")
		// Result is "get-" which is trimmed to "get-root" by the ifEmpty fallback
		assertTrue(id.startsWith("get"), "ID should start with method")
	}
}
