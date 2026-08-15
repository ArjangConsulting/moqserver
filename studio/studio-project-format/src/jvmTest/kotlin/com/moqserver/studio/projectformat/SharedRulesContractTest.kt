package com.moqserver.studio.projectformat

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the format constants that Swift and Kotlin must agree on until `MoqFormatRules` unifies
 * them into a single generated source (see the "Swift format core + MCP server" plan, phase 1).
 * The Swift half of this contract lives at
 * `server/Tests/MoqFormatTests/SharedRulesContractTests.swift`.
 *
 * Changing a value here without updating the Swift counterpart reintroduces the exact class of
 * drift this test exists to catch (see the reserved-path divergence fixed alongside this test).
 */
class SharedRulesContractTest {
	private val validator = ProjectValidator()

	private fun project(version: String = "1", vararg endpoints: EndpointDocument): MoqProject = MoqProject(
		manifest = ProjectManifest(
			version = version,
			name = "Test",
			defaults = ProjectDefaults(
				auth = ProjectAuthConfig(type = AuthType.NONE, verify = false),
				network = NetworkBehavior(),
			),
		),
		endpoints = endpoints.toList(),
		projectPath = "/tmp/test",
	)

	private fun endpoint(
		id: String = "test-endpoint",
		method: String = "GET",
		path: String = "/test",
		variants: List<ProjectVariant> = listOf(ProjectVariant(name = "default", status = 200)),
	): EndpointDocument = EndpointDocument(id = id, method = method, path = path, variants = variants)

	private fun errors(vararg endpoints: EndpointDocument, version: String = "1") =
		validator.validate(project(version, *endpoints)).filter { it.severity == ValidationDiagnostic.Severity.ERROR }

	// ── Format version ──────────────────────────────────────────────

	@Test
	fun `format version is 1`() {
		assertFalse(errors(endpoint(), version = "1").any { it.field == "version" })
	}

	// ── Endpoint id pattern: ^[a-z0-9][a-z0-9-]*$ ──────────────────────

	@Test
	fun `id pattern accepts lowercase-alphanumeric-with-hyphens`() {
		for (id in listOf("a", "a1", "list-users", "user-2fa-token")) {
			assertFalse(errors(endpoint(id = id)).any { it.field == "id" }, "expected $id to be accepted")
		}
	}

	@Test
	fun `id pattern rejects uppercase, underscores, leading hyphen, camelCase`() {
		for (id in listOf("Users", "list_users", "-users", "listUsers", "")) {
			assertTrue(errors(endpoint(id = id)).any { it.field == "id" }, "expected $id to be rejected")
		}
	}

	// ── Supported HTTP methods ──────────────────────────────────────

	@Test
	fun `supported HTTP methods`() {
		for (method in listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")) {
			assertFalse(errors(endpoint(method = method)).any { it.field == "method" }, "expected $method to be accepted")
		}
	}

	@Test
	fun `TRACE and CONNECT are not supported methods`() {
		for (method in listOf("TRACE", "CONNECT")) {
			assertTrue(errors(endpoint(method = method)).any { it.field == "method" }, "expected $method to be rejected")
		}
	}

	// ── Reserved paths ──────────────────────────────────────────────

	@Test
	fun `reserved paths are rejected`() {
		for (path in listOf("/health", "/_admin", "/_admin/endpoints", "/_auth", "/_auth/token")) {
			assertTrue(errors(endpoint(path = path)).any { "reserved" in it.message }, "expected $path to be rejected")
		}
	}

	@Test
	fun `the old double-underscore admin path is not reserved`() {
		assertFalse(errors(endpoint(path = "/__admin/endpoints")).any { "reserved" in it.message })
	}

	// ── MatchType wire values ────────────────────────────────────────

	@Test
	fun `MatchType wire values`() {
		val expected = setOf(
			"require", "equal_to", "not_equal_to", "contains", "not_contains",
			"begins_with", "ends_with", "matches_regex", "is_empty", "not_empty",
			"gt", "gte", "lt", "lte",
		)
		val actual = MatchType.entries.map { Json.encodeToString(it).trim('"') }.toSet()
		assertEquals(expected, actual)
	}

	// ── AuthType wire values ─────────────────────────────────────────

	@Test
	fun `AuthType wire values`() {
		val expected = setOf("none", "bearer", "basic", "api-key", "header")
		val actual = AuthType.entries.map { Json.encodeToString(it).trim('"') }.toSet()
		assertEquals(expected, actual)
	}
}
