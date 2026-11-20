package com.moqserver.studio

import com.moqserver.studio.endpointdetail.BodyEncodingChange
import com.moqserver.studio.endpointdetail.convertingBodyEncoding
import com.moqserver.studio.endpointdetail.isJsonBody
import com.moqserver.studio.endpointdetail.normalize
import com.moqserver.studio.endpointdetail.withAcceptedContentTypes
import com.moqserver.studio.endpointdetail.withVerifyCookies
import com.moqserver.studio.projectformat.AuthType
import com.moqserver.studio.projectformat.BodyEncoding
import com.moqserver.studio.projectformat.EndpointDocument
import com.moqserver.studio.projectformat.GlobalRules
import com.moqserver.studio.projectformat.NetworkBehavior
import com.moqserver.studio.projectformat.ProjectAuthConfig
import com.moqserver.studio.projectformat.ProjectDefaults
import com.moqserver.studio.projectformat.ProjectManifest
import com.moqserver.studio.projectformat.ProjectScenario
import com.moqserver.studio.projectformat.ProjectVariant
import com.moqserver.studio.projectformat.RequestRules
import com.moqserver.studio.projectformat.YamlValue
import com.moqserver.studio.projectsettings.ScenarioNameProblem
import com.moqserver.studio.projectsettings.newScenario
import com.moqserver.studio.projectsettings.nextScenarioName
import com.moqserver.studio.projectsettings.renamingScenario
import com.moqserver.studio.projectsettings.scenarioNameProblem
import com.moqserver.studio.projectsettings.withGlobalRules
import com.moqserver.studio.projectsettings.withScenarios
import com.moqserver.studio.projectsettings.withSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class RequestRulesNormalizationTest {
	@Test
	fun `body rules alone keep the request rules block`() {
		val rules = RequestRules(requiresBody = true, acceptedContentTypes = listOf("application/json"))

		assertEquals(rules, rules.normalize())
	}

	@Test
	fun `an explicit verify_cookies false survives because it overrides the project`() {
		val rules = RequestRules(verifyCookies = false, headers = emptyList())

		assertEquals(RequestRules(verifyCookies = false), rules.normalize())
	}

	@Test
	fun `verify_cookies equal to the project value is written as absent`() {
		assertNull(RequestRules().withVerifyCookies(verify = true, inherited = true).verifyCookies)
		assertEquals(false, RequestRules().withVerifyCookies(verify = false, inherited = true).verifyCookies)
		assertEquals(true, RequestRules().withVerifyCookies(verify = true, inherited = false).verifyCookies)
	}

	@Test
	fun `removing the last content type removes the key`() {
		val rules = RequestRules(acceptedContentTypes = listOf("a/b"))

		assertNull(rules.withAcceptedContentTypes(emptyList()).acceptedContentTypes)
	}
}

class BodyEncodingConversionTest {
	private val text = ProjectVariant(name = "v", status = 200, body = YamlValue.Str("héllo"))

	@Test
	fun `text converts to base64 of its UTF-8 bytes and back`() {
		val encoded = (text.convertingBodyEncoding(BodyEncoding.BASE64) as BodyEncodingChange.Changed).variant

		assertEquals(YamlValue.Str("aMOpbGxv"), encoded.body)
		assertEquals(BodyEncoding.BASE64, encoded.bodyEncoding)
		assertEquals(BodyEncodingChange.Changed(text), encoded.convertingBodyEncoding(BodyEncoding.UTF8))
	}

	@Test
	fun `base64 wrapped across lines decodes like the server decodes it`() {
		val wrapped = text.copy(body = YamlValue.Str("aMOp\nbGxv\n"), bodyEncoding = BodyEncoding.BASE64)

		assertEquals(BodyEncodingChange.Changed(text), wrapped.convertingBodyEncoding(BodyEncoding.UTF8))
	}

	@Test
	fun `binary or malformed base64 stays base64`() {
		val binary = text.copy(body = YamlValue.Str("//4="), bodyEncoding = BodyEncoding.BASE64)
		val malformed = text.copy(body = YamlValue.Str("not base64!"), bodyEncoding = BodyEncoding.BASE64)

		assertEquals(BodyEncodingChange.NotText, binary.convertingBodyEncoding(BodyEncoding.UTF8))
		assertEquals(BodyEncodingChange.InvalidBase64, malformed.convertingBodyEncoding(BodyEncoding.UTF8))
	}

	@Test
	fun `a base64 body is never treated as JSON text`() {
		val variant = text.copy(
			headers = mapOf("Content-Type" to "application/json"),
			body = YamlValue.Str("e30="),
			bodyEncoding = BodyEncoding.BASE64,
		)

		assertFalse(variant.isJsonBody("{}"))
	}
}

class ProjectSettingsHelpersTest {
	private val manifest = ProjectManifest(
		name = "P",
		defaults = ProjectDefaults(
			auth = ProjectAuthConfig(type = AuthType.NONE, verify = false),
			network = NetworkBehavior(),
		),
	)
	private val endpoints = listOf(
		EndpointDocument(
			id = "get-a",
			method = "GET",
			path = "/a",
			variants = listOf(
				ProjectVariant(name = "ok", status = 200),
				ProjectVariant(name = "err", status = 500, isDefault = true),
			),
		),
		EndpointDocument(
			id = "get-b",
			method = "GET",
			path = "/b",
			variants = listOf(ProjectVariant(name = "ok", status = 200)),
		),
	)

	@Test
	fun `renaming keeps scenario order and refuses a name already taken`() {
		val scenarios = mapOf(
			"a" to ProjectScenario(variants = mapOf("get-a" to "ok")),
			"b" to ProjectScenario(variants = mapOf("get-b" to "ok")),
		)

		assertEquals(listOf("z", "b"), scenarios.renamingScenario("a", "z").keys.toList())
		assertEquals(scenarios, scenarios.renamingScenario("a", "b"))
	}

	@Test
	fun `scenario names follow the format limits`() {
		assertEquals(ScenarioNameProblem.BLANK, scenarioNameProblem(" ", "a", setOf("b")))
		assertEquals(ScenarioNameProblem.TOO_LONG, scenarioNameProblem("x".repeat(101), "a", setOf("b")))
		assertEquals(ScenarioNameProblem.DUPLICATE, scenarioNameProblem("b", "a", setOf("b")))
		assertNull(scenarioNameProblem("a", "a", setOf("b")))
		assertEquals("scenario-2", nextScenarioName(setOf("scenario-1")))
	}

	@Test
	fun `a new scenario selects the first endpoint's default variant`() {
		assertEquals(ProjectScenario(variants = mapOf("get-a" to "err")), newScenario(endpoints))
		assertNull(newScenario(emptyList()))
	}

	@Test
	fun `changing a row's endpoint keeps its position`() {
		val scenario = ProjectScenario(variants = mapOf("get-a" to "ok", "get-c" to "x"))

		val updated = scenario.withSelection("get-b", "ok", replacing = "get-a")

		assertEquals(listOf("get-b" to "ok", "get-c" to "x"), updated.variants.toList())
	}

	@Test
	fun `empty scenarios and global rules write no keys`() {
		assertNull(manifest.withScenarios(emptyMap()).scenarios)
		assertNull(manifest.withGlobalRules(GlobalRules(requiredHeaders = emptyList())).globalRules)
		val cookiesOnly = GlobalRules(verifyCookies = true)
		assertEquals(cookiesOnly, manifest.withGlobalRules(cookiesOnly).globalRules)
	}
}
