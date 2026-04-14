package com.moqserver.studio.ai

import com.moqserver.studio.domain.CompanionRequest
import com.moqserver.studio.domain.ProjectContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AIProviderRegistryTest {

	// ── Provider Lookup ─────────────────────────────────────────────

	@Test
	fun `find returns provider by id`() {
		val provider = fakeProvider("ollama")
		val registry = AIProviderRegistry(listOf(provider))

		assertEquals(provider, registry.find("ollama"))
	}

	@Test
	fun `find returns null for unknown id`() {
		val registry = AIProviderRegistry(listOf(fakeProvider("ollama")))

		assertNull(registry.find("unknown-provider"))
	}

	@Test
	fun `allProviders returns all registered providers`() {
		val providers = listOf(fakeProvider("a"), fakeProvider("b"), fakeProvider("c"))
		val registry = AIProviderRegistry(providers)

		assertEquals(3, registry.allProviders().size)
		assertEquals(listOf("a", "b", "c"), registry.allProviders().map { it.id })
	}

	@Test
	fun `allProviders returns empty list for empty registry`() {
		val registry = AIProviderRegistry(emptyList())

		assertTrue(registry.allProviders().isEmpty())
	}

	// ── availableProviders ──────────────────────────────────────────

	@Test
	fun `availableProviders filters by availability`() = runTest {
		val available = fakeProvider("available", available = true)
		val unavailable = fakeProvider("unavailable", available = false)
		val registry = AIProviderRegistry(listOf(available, unavailable))

		val result = registry.availableProviders()

		assertEquals(1, result.size)
		assertEquals("available", result.single().id)
	}

	// ── analyzeSpec ─────────────────────────────────────────────────

	@Test
	fun `analyzeSpec calls provider and parses result`() = runTest {
		val provider = fakeProvider(
			"test",
			completionText = """[{"severity":"warning","category":"naming","message":"Inconsistent naming"}]""",
		)
		val registry = AIProviderRegistry(listOf(provider))

		val request = CompanionRequest(
			providerId = "test",
			projectContext = ProjectContext(title = "Test API"),
		)
		val response = registry.analyzeSpec(provider, request)

		assertNotNull(response.requestId)
		assertTrue(response.requestId.startsWith("req_"))
		assertEquals("test", response.provider.id)
		assertEquals("fake-model", response.provider.model)
		assertEquals(1, response.result.findings.size)
		assertEquals("naming", response.result.findings.single().category)
		assertEquals("Inconsistent naming", response.result.findings.single().message)
	}

	// ── generateVariants ────────────────────────────────────────────

	@Test
	fun `generateVariants calls provider and parses result`() = runTest {
		val provider = fakeProvider(
			"test",
			completionText = """[{"endpointKey":"GET /pets","name":"success","statusCode":200,"contentType":"application/json","body":"{}"}]""",
		)
		val registry = AIProviderRegistry(listOf(provider))

		val request = CompanionRequest(providerId = "test")
		val response = registry.generateVariants(provider, request)

		assertEquals(1, response.result.variants.size)
		assertEquals("GET /pets", response.result.variants.single().endpointKey)
		assertEquals("success", response.result.variants.single().name)
		assertEquals(200, response.result.variants.single().statusCode)
	}

	// ── refineProject ───────────────────────────────────────────────

	@Test
	fun `refineProject calls provider and parses result`() = runTest {
		val provider = fakeProvider(
			"test",
			completionText = """[{"category":"naming","title":"Fix aliases","description":"Improve alias clarity"}]""",
		)
		val registry = AIProviderRegistry(listOf(provider))

		val request = CompanionRequest(providerId = "test")
		val response = registry.refineProject(provider, request)

		assertEquals(1, response.result.suggestions.size)
		assertEquals("naming", response.result.suggestions.single().category)
		assertEquals("Fix aliases", response.result.suggestions.single().title)
	}

	// ── Response envelope ───────────────────────────────────────────

	@Test
	fun `response includes usage info from completion result`() = runTest {
		val provider = fakeProvider(
			"test",
			completionText = """[]""",
			promptTokens = 100,
			completionTokens = 50,
			totalTokens = 150,
			latencyMs = 200L,
		)
		val registry = AIProviderRegistry(listOf(provider))

		val response = registry.analyzeSpec(provider, CompanionRequest(providerId = "test"))

		assertEquals(100, response.usage?.promptTokens)
		assertEquals(50, response.usage?.completionTokens)
		assertEquals(150, response.usage?.totalTokens)
		assertEquals(200, response.usage?.latencyMs)
	}

	// ── Helpers ─────────────────────────────────────────────────────

	private fun fakeProvider(
		id: String,
		available: Boolean = true,
		completionText: String = "[]",
		promptTokens: Int? = null,
		completionTokens: Int? = null,
		totalTokens: Int? = null,
		latencyMs: Long? = null,
	): AIProvider = object : AIProvider {
		override val id = id
		override val displayName = "Fake $id"
		override val kind = AIProviderKind.LOCAL
		override val capabilities = setOf(
			AIProviderCapability.ANALYZE_SPEC,
			AIProviderCapability.GENERATE_VARIANTS,
			AIProviderCapability.REFINE_PROJECT,
		)

		override suspend fun checkAvailability() = available
		override suspend fun validateConfig() = emptyList<String>()
		override suspend fun complete(prompt: String, model: String?, temperature: Double?) =
			AICompletionResult(
				text = completionText,
				model = "fake-model",
				promptTokens = promptTokens,
				completionTokens = completionTokens,
				totalTokens = totalTokens,
				latencyMs = latencyMs,
			)
	}
}
