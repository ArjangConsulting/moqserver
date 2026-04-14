package com.moqserver.studio.projectformat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectValidatorDeepTest {
	private val validator = ProjectValidator()

	// ── Format version ──────────────────────────────────────────────

	@Test
	fun `rejects unsupported format version`() {
		val project = projectWithManifest(version = "99")
		val diagnostics = validator.validate(project)
		assertTrue(
			diagnostics.any {
				it.severity == ValidationDiagnostic.Severity.ERROR && "Unsupported format version" in it.message
			},
		)
	}

	@Test
	fun `accepts current format version`() {
		val project = projectWithManifest(version = MoqProjectFormat.FORMAT_VERSION)
		val diagnostics = validator.validate(project)
		assertTrue(diagnostics.none { "format version" in it.message })
	}

	// ── Empty endpoints ─────────────────────────────────────────────

	@Test
	fun `rejects project with no endpoints`() {
		val project = projectWithEndpoints()
		val diagnostics = validator.validate(project)
		assertTrue(diagnostics.any { "No endpoint files found" in it.message })
	}

	// ── Endpoint ID validation ──────────────────────────────────────

	@Test
	fun `rejects invalid endpoint id with uppercase`() {
		val diagnostics = validator.validate(
			projectWithEndpoints(
				endpoint(id = "GetPets"),
			),
		)
		assertTrue(diagnostics.any { "must be lowercase alphanumeric" in it.message })
	}

	@Test
	fun `rejects invalid endpoint id with special chars`() {
		val diagnostics = validator.validate(
			projectWithEndpoints(
				endpoint(id = "get_pets"),
			),
		)
		assertTrue(diagnostics.any { "must be lowercase alphanumeric" in it.message })
	}

	@Test
	fun `accepts valid endpoint id`() {
		val diagnostics = validator.validate(
			projectWithEndpoints(
				endpoint(id = "get-pets"),
			),
		)
		assertTrue(diagnostics.none { "must be lowercase alphanumeric" in it.message })
	}

	@Test
	fun `rejects duplicate endpoint ids`() {
		val diagnostics = validator.validate(
			projectWithEndpoints(
				endpoint(id = "get-pets", path = "/pets"),
				endpoint(id = "get-pets", path = "/other"),
			),
		)
		assertTrue(diagnostics.any { "Duplicate endpoint id" in it.message })
	}

	// ── Reference name validation ───────────────────────────────────

	@Test
	fun `rejects blank endpoint reference name`() {
		val diagnostics = validator.validate(
			projectWithEndpoints(
				endpoint(referenceName = ""),
			),
		)
		assertTrue(diagnostics.any { "reference_name is required" in it.message })
	}

	@Test
	fun `rejects invalid endpoint reference name`() {
		val diagnostics = validator.validate(
			projectWithEndpoints(
				endpoint(referenceName = "bad name"),
			),
		)
		assertTrue(diagnostics.any { "must start with a letter or underscore" in it.message })
	}

	// ── Reserved paths ──────────────────────────────────────────────

	@Test
	fun `rejects health reserved path`() {
		val diagnostics = validator.validate(
			projectWithEndpoints(
				endpoint(path = "/health"),
			),
		)
		assertTrue(diagnostics.any { "reserved" in it.message })
	}

	@Test
	fun `rejects admin reserved path`() {
		val diagnostics = validator.validate(
			projectWithEndpoints(
				endpoint(path = "/__admin/endpoints"),
			),
		)
		assertTrue(diagnostics.any { "reserved" in it.message })
	}

	// ── Path validation ─────────────────────────────────────────────

	@Test
	fun `rejects path that does not start with slash`() {
		val diagnostics = validator.validate(
			projectWithEndpoints(
				endpoint(path = "pets"),
			),
		)
		assertTrue(diagnostics.any { "Path must start with" in it.message })
	}

	// ── HTTP method validation ──────────────────────────────────────

	@Test
	fun `rejects invalid HTTP method`() {
		val diagnostics = validator.validate(
			projectWithEndpoints(
				endpoint(method = "INVALID"),
			),
		)
		assertTrue(diagnostics.any { "Invalid HTTP method" in it.message })
	}

	@Test
	fun `accepts all valid HTTP methods`() {
		val methods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
		for (method in methods) {
			val diagnostics = validator.validate(
				projectWithEndpoints(
					endpoint(method = method),
				),
			)
			assertTrue(diagnostics.none { "Invalid HTTP method" in it.message }, "Should accept $method")
		}
	}

	// ── Variant validation ──────────────────────────────────────────

	@Test
	fun `rejects endpoint with no variants`() {
		val diagnostics = validator.validate(
			projectWithEndpoints(
				EndpointDocument(
					id = "get-pets",
					method = "GET",
					path = "/pets",
					referenceName = "getPets",
					variants = emptyList(),
				),
			),
		)
		assertTrue(diagnostics.any { "must have at least one variant" in it.message })
	}

	@Test
	fun `rejects multiple default variants`() {
		val diagnostics = validator.validate(
			projectWithEndpoints(
				EndpointDocument(
					id = "get-pets",
					method = "GET",
					path = "/pets",
					referenceName = "getPets",
					variants = listOf(
						ProjectVariant(name = "A", referenceName = "a", status = 200, isDefault = true),
						ProjectVariant(name = "B", referenceName = "b", status = 200, isDefault = true),
					),
				),
			),
		)
		assertTrue(diagnostics.any { "Only one variant may be marked as default" in it.message })
	}

	@Test
	fun `rejects duplicate variant names`() {
		val diagnostics = validator.validate(
			projectWithEndpoints(
				EndpointDocument(
					id = "get-pets",
					method = "GET",
					path = "/pets",
					referenceName = "getPets",
					variants = listOf(
						ProjectVariant(name = "Success", referenceName = "success1", status = 200),
						ProjectVariant(name = "Success", referenceName = "success2", status = 201),
					),
				),
			),
		)
		assertTrue(diagnostics.any { "Duplicate variant name" in it.message })
	}

	@Test
	fun `rejects blank variant reference name`() {
		val diagnostics = validator.validate(
			projectWithEndpoints(
				endpoint(
					variants = listOf(
						ProjectVariant(name = "Success", referenceName = "", status = 200),
					),
				),
			),
		)
		assertTrue(diagnostics.any { "Variant reference_name is required" in it.message })
	}

	// ── Body / body_file mutual exclusivity ─────────────────────────

	@Test
	fun `rejects variant with both body and body_file`() {
		val diagnostics = validator.validate(
			projectWithEndpoints(
				endpoint(
					variants = listOf(
						ProjectVariant(
							name = "Success",
							referenceName = "success",
							status = 200,
							body = YamlValue.Str("inline"),
							bodyFile = "fixtures/response.json",
						),
					),
				),
			),
		)
		assertTrue(diagnostics.any { "both body and body_file" in it.message })
	}

	// ── body_file path validation ───────────────────────────────────

	@Test
	fun `rejects body_file that does not start with fixtures prefix`() {
		val diagnostics = validator.validate(
			projectWithEndpoints(
				endpoint(
					variants = listOf(
						ProjectVariant(
							name = "Success",
							referenceName = "success",
							status = 200,
							bodyFile = "other/response.json",
						),
					),
				),
			),
		)
		assertTrue(diagnostics.any { "must start with" in it.message && "fixtures/" in it.message })
	}

	@Test
	fun `rejects body_file with path traversal`() {
		val diagnostics = validator.validate(
			projectWithEndpoints(
				endpoint(
					variants = listOf(
						ProjectVariant(
							name = "Success",
							referenceName = "success",
							status = 200,
							bodyFile = "fixtures/../etc/passwd",
						),
					),
				),
			),
		)
		assertTrue(diagnostics.any { "path traversal" in it.message })
	}

	@Test
	fun `rejects missing fixture file`() {
		val validatorWithFixtureCheck = ProjectValidator(fixtureExists = { _, _ -> false })
		val diagnostics = validatorWithFixtureCheck.validate(
			projectWithEndpoints(
				endpoint(
					variants = listOf(
						ProjectVariant(
							name = "Success",
							referenceName = "success",
							status = 200,
							bodyFile = "fixtures/response.json",
						),
					),
				),
			),
		)
		assertTrue(diagnostics.any { "Fixture file not found" in it.message })
	}

	// ── Request match validation ────────────────────────────────────

	@Test
	fun `rejects request match with blank query key`() {
		val diagnostics = validator.validate(
			projectWithEndpoints(
				endpoint(
					variants = listOf(
						ProjectVariant(
							name = "Match",
							referenceName = "match",
							status = 200,
							requestMatch = VariantRequestMatch(
								query = mapOf("" to "value"),
							),
						),
					),
				),
			),
		)
		assertTrue(diagnostics.any { "query names must not be blank" in it.message })
	}

	@Test
	fun `rejects request match with blank header key`() {
		val diagnostics = validator.validate(
			projectWithEndpoints(
				endpoint(
					variants = listOf(
						ProjectVariant(
							name = "Match",
							referenceName = "match",
							status = 200,
							requestMatch = VariantRequestMatch(
								headers = mapOf("" to "value"),
							),
						),
					),
				),
			),
		)
		assertTrue(diagnostics.any { "header names must not be blank" in it.message })
	}

	// ── Auth validation ─────────────────────────────────────────────

	@Test
	fun `rejects api-key auth without header_name`() {
		val diagnostics = validator.validate(
			projectWithEndpoints(
				EndpointDocument(
					id = "get-pets",
					method = "GET",
					path = "/pets",
					referenceName = "getPets",
					auth = ProjectAuthConfig(type = AuthType.API_KEY, verify = true, headerName = null),
					variants = listOf(
						ProjectVariant(name = "Success", referenceName = "success", status = 200),
					),
				),
			),
		)
		assertTrue(diagnostics.any { "header_name is required" in it.message })
	}

	@Test
	fun `rejects header auth without header_name`() {
		val diagnostics = validator.validate(
			projectWithEndpoints(
				EndpointDocument(
					id = "get-pets",
					method = "GET",
					path = "/pets",
					referenceName = "getPets",
					auth = ProjectAuthConfig(type = AuthType.HEADER, verify = true, headerName = ""),
					variants = listOf(
						ProjectVariant(name = "Success", referenceName = "success", status = 200),
					),
				),
			),
		)
		assertTrue(diagnostics.any { "header_name is required" in it.message })
	}

	@Test
	fun `accepts bearer auth without header_name`() {
		val diagnostics = validator.validate(
			projectWithEndpoints(
				EndpointDocument(
					id = "get-pets",
					method = "GET",
					path = "/pets",
					referenceName = "getPets",
					auth = ProjectAuthConfig(type = AuthType.BEARER, verify = true),
					variants = listOf(
						ProjectVariant(name = "Success", referenceName = "success", status = 200),
					),
				),
			),
		)
		assertTrue(diagnostics.none { "header_name is required" in it.message })
	}

	// ── GraphQL validation ──────────────────────────────────────────

	@Test
	fun `rejects GraphQL path without operation`() {
		val diagnostics = validator.validate(
			projectWithEndpoints(
				EndpointDocument(
					id = "gql",
					method = "POST",
					path = "/graphql",
					referenceName = "gql",
					variants = listOf(
						ProjectVariant(name = "Success", referenceName = "success", status = 200),
					),
				),
			),
		)
		assertTrue(diagnostics.any { "must define an operation" in it.message })
	}

	@Test
	fun `warns when operation defined on non-graphql path`() {
		val diagnostics = validator.validate(
			projectWithEndpoints(
				EndpointDocument(
					id = "other",
					method = "POST",
					path = "/other",
					referenceName = "other",
					operation = EndpointOperation(type = OperationType.QUERY, name = "test"),
					variants = listOf(
						ProjectVariant(name = "Success", referenceName = "success", status = 200),
					),
				),
			),
		)
		assertTrue(
			diagnostics.any {
				it.severity == ValidationDiagnostic.Severity.WARNING && "path is not /graphql" in it.message
			},
		)
	}

	@Test
	fun `rejects GraphQL operation without name and without document`() {
		val diagnostics = validator.validate(
			projectWithEndpoints(
				EndpointDocument(
					id = "gql",
					method = "POST",
					path = "/graphql",
					referenceName = "gql",
					operation = EndpointOperation(type = OperationType.QUERY),
					variants = listOf(
						ProjectVariant(name = "Success", referenceName = "success", status = 200),
					),
				),
			),
		)
		assertTrue(diagnostics.any { "must define at least one of" in it.message })
	}

	@Test
	fun `rejects GraphQL operation with empty document string`() {
		val diagnostics = validator.validate(
			projectWithEndpoints(
				EndpointDocument(
					id = "gql",
					method = "POST",
					path = "/graphql",
					referenceName = "gql",
					operation = EndpointOperation(type = OperationType.QUERY, name = "test", document = "  "),
					variants = listOf(
						ProjectVariant(name = "Success", referenceName = "success", status = 200),
					),
				),
			),
		)
		assertTrue(diagnostics.any { "must be non-empty after normalization" in it.message })
	}

	// ── Manifest auth validation ────────────────────────────────────

	@Test
	fun `rejects manifest api-key auth without header_name`() {
		val project = MoqProject(
			manifest = ProjectManifest(
				name = "Test",
				defaults = ProjectDefaults(
					auth = ProjectAuthConfig(type = AuthType.API_KEY, verify = true),
					network = NetworkBehavior(),
				),
			),
			endpoints = listOf(endpoint()),
			projectPath = "/tmp/test",
		)
		val diagnostics = validator.validate(project)
		assertTrue(diagnostics.any { it.field?.contains("defaults.auth") == true && "header_name is required" in it.message })
	}

	// ── ValidationDiagnostic toString ───────────────────────────────

	@Test
	fun `diagnostic toString includes all context`() {
		val diagnostic = ValidationDiagnostic(
			severity = ValidationDiagnostic.Severity.ERROR,
			message = "Something bad",
			file = "endpoints/get-pets.yml",
			field = "variants[0].name",
			endpointId = "get-pets",
			endpointLabel = "GET /pets",
			variantName = "Success",
		)
		val result = diagnostic.toString()
		assertTrue("[error]" in result)
		assertTrue("GET /pets" in result)
		assertTrue("(Success)" in result)
		assertTrue("Something bad" in result)
	}

	@Test
	fun `diagnostic without context is concise`() {
		val diagnostic = ValidationDiagnostic(
			severity = ValidationDiagnostic.Severity.WARNING,
			message = "A warning",
		)
		assertEquals("[warning] A warning", diagnostic.toString())
	}

	// ── Count and coverage ──────────────────────────────────────────

	@Test
	fun `valid project with good data produces no diagnostics`() {
		val project = projectWithEndpoints(
			EndpointDocument(
				id = "get-pets",
				method = "GET",
				path = "/pets",
				referenceName = "getPets",
				variants = listOf(
					ProjectVariant(name = "Success", referenceName = "success", status = 200, isDefault = true),
					ProjectVariant(name = "Not Found", referenceName = "notFound", status = 404),
				),
			),
			EndpointDocument(
				id = "post-pets",
				method = "POST",
				path = "/pets",
				referenceName = "createPets",
				variants = listOf(
					ProjectVariant(name = "Created", referenceName = "created", status = 201),
				),
			),
		)
		val diagnostics = validator.validate(project)
		assertEquals(0, diagnostics.size, "Expected zero diagnostics but got: $diagnostics")
	}

	// ── Helpers ─────────────────────────────────────────────────────

	private fun projectWithManifest(version: String): MoqProject {
		return MoqProject(
			manifest = ProjectManifest(
				version = version,
				name = "Test",
				defaults = ProjectDefaults(
					auth = ProjectAuthConfig(type = AuthType.NONE, verify = false),
					network = NetworkBehavior(),
				),
			),
			endpoints = listOf(endpoint()),
			projectPath = "/tmp/test",
		)
	}

	private fun projectWithEndpoints(vararg endpoints: EndpointDocument): MoqProject {
		return MoqProject(
			manifest = ProjectManifest(
				name = "Test",
				defaults = ProjectDefaults(
					auth = ProjectAuthConfig(type = AuthType.NONE, verify = false),
					network = NetworkBehavior(),
				),
			),
			endpoints = endpoints.toList(),
			projectPath = "/tmp/test",
		)
	}

	private fun endpoint(
		id: String = "get-pets",
		method: String = "GET",
		path: String = "/pets",
		referenceName: String = "getPets",
		variants: List<ProjectVariant> = listOf(
			ProjectVariant(name = "Success", referenceName = "success", status = 200),
		),
	): EndpointDocument = EndpointDocument(
		id = id,
		method = method,
		path = path,
		referenceName = referenceName,
		variants = variants,
	)
}
