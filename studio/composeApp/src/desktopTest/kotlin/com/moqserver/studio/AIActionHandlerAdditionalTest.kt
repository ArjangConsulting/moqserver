package com.moqserver.studio

import com.moqserver.studio.projectformat.AuthType
import com.moqserver.studio.projectformat.EndpointDocument
import com.moqserver.studio.projectformat.MoqProject
import com.moqserver.studio.projectformat.NetworkBehavior
import com.moqserver.studio.projectformat.ProjectAuthConfig
import com.moqserver.studio.projectformat.ProjectDefaults
import com.moqserver.studio.projectformat.ProjectManifest
import com.moqserver.studio.projectformat.ProjectVariant
import com.moqserver.studio.projectformat.YamlValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AIActionHandlerAdditionalTest {

	// ── buildProjectContext ──────────────────────────────────────────

	@Test
	fun `buildProjectContext maps project to ProjectContext`() {
		val project = MoqProject(
			manifest = ProjectManifest(
				name = "Pet Store",
				version = "1",
				defaults = ProjectDefaults(
					auth = ProjectAuthConfig(type = AuthType.BEARER, verify = true),
					network = NetworkBehavior(),
				),
			),
			endpoints = listOf(
				EndpointDocument(
					id = "get-pets",
					method = "GET",
					path = "/pets",
					referenceName = "getPets",
					auth = ProjectAuthConfig(type = AuthType.BEARER, verify = true),
					tags = listOf("pets", "animals"),
					variants = listOf(
						ProjectVariant(name = "Success", referenceName = "success", status = 200),
						ProjectVariant(name = "Error", referenceName = "error", status = 500),
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
			),
			projectPath = "/tmp/test",
		)

		val ctx = buildProjectContext(project)

		assertEquals("Pet Store", ctx.title)
		assertEquals("1", ctx.version)
		assertEquals(2, ctx.endpoints?.size)
		val ep1 = ctx.endpoints!![0]
		assertEquals("GET", ep1.method)
		assertEquals("/pets", ep1.path)
		assertEquals(2, ep1.variantCount)
		assertTrue(ep1.hasAuth ?: false)
		assertEquals(listOf("pets", "animals"), ep1.tags)
		val ep2 = ctx.endpoints!![1]
		assertEquals("POST", ep2.method)
		assertEquals(false, ep2.hasAuth)
		assertEquals(1, ep2.variantCount)
	}

	// ── mergeGeneratedBodyIfNeeded (additional edge cases) ──────────

	@Test
	fun `mergeGeneratedBodyIfNeeded handles single non-array item appended to existing array`() {
		val existing = YamlValue.Array(listOf(YamlValue.Str("a"), YamlValue.Str("b")))
		val generated = YamlValue.Str("c")

		val merged = mergeGeneratedBodyIfNeeded(existing, generated, "add one more item")

		val result = merged as YamlValue.Array
		assertEquals(3, result.value.size)
		assertEquals("c", (result.value[2] as YamlValue.Str).value)
	}

	@Test
	fun `mergeGeneratedBodyIfNeeded does not attempt merge when prompt has no additive keywords`() {
		val existing = YamlValue.Array(listOf(YamlValue.Int(1)))
		val generated = YamlValue.Array(listOf(YamlValue.Int(99)))

		val merged = mergeGeneratedBodyIfNeeded(existing, generated, "replace everything with this")

		assertEquals(generated, merged)
	}

	@Test
	fun `mergeGeneratedBodyIfNeeded returns generated when existing is null`() {
		val generated = YamlValue.Str("new body")

		val merged = mergeGeneratedBodyIfNeeded(null, generated, "add content")

		assertEquals(generated, merged)
	}

	@Test
	fun `mergeGeneratedBodyIfNeeded returns generated for mismatched types`() {
		val existing = YamlValue.Str("old text")
		val generated = YamlValue.Array(listOf(YamlValue.Int(1)))

		val merged = mergeGeneratedBodyIfNeeded(existing, generated, "add items")

		assertEquals(generated, merged)
	}

	@Test
	fun `mergeGeneratedBodyIfNeeded returns generated for objects without shared array keys`() {
		val existing = YamlValue.Obj(mapOf("a" to YamlValue.Str("x")))
		val generated = YamlValue.Obj(mapOf("b" to YamlValue.Str("y")))

		val merged = mergeGeneratedBodyIfNeeded(existing, generated, "add more data")

		assertEquals(generated, merged)
	}
}
