package com.moqserver.studio.ai.prompts

import com.moqserver.studio.domain.CompanionRequest
import com.moqserver.studio.domain.EndpointSummary
import com.moqserver.studio.domain.IntentContext
import com.moqserver.studio.domain.ProjectContext
import com.moqserver.studio.domain.RequestOptions
import com.moqserver.studio.domain.SelectionContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class PromptBuilderTest {

	// ── buildAnalyzePrompt ──────────────────────────────────────────

	@Test
	fun `buildAnalyzePrompt contains instruction and JSON array requirement`() {
		val prompt = PromptBuilder.buildAnalyzePrompt(CompanionRequest(providerId = "test"))
		assertContains(prompt, "API specification analyzer")
		assertContains(prompt, "JSON array")
		assertContains(prompt, "no markdown fences")
	}

	@Test
	fun `buildAnalyzePrompt includes project context when provided`() {
		val request = CompanionRequest(
			providerId = "test",
			projectContext = ProjectContext(
				title = "Pet Store API",
				version = "2.0",
				endpoints = listOf(
					EndpointSummary(method = "GET", path = "/pets", variantCount = 3, hasAuth = true),
					EndpointSummary(method = "POST", path = "/pets", variantCount = 1, hasAuth = false),
				),
			),
		)

		val prompt = PromptBuilder.buildAnalyzePrompt(request)

		assertContains(prompt, "Pet Store API")
		assertContains(prompt, "2.0")
		assertContains(prompt, "GET /pets")
		assertContains(prompt, "(3 variants)")
		assertContains(prompt, "[auth required]")
		assertContains(prompt, "POST /pets")
		assertFalse(prompt.contains("POST /pets (1 variants) [auth required]"))
	}

	@Test
	fun `buildAnalyzePrompt includes spec excerpt`() {
		val request = CompanionRequest(
			providerId = "test",
			projectContext = ProjectContext(
				specExcerpt = "openapi: 3.0.0\ninfo:\n  title: Pets",
			),
		)

		val prompt = PromptBuilder.buildAnalyzePrompt(request)

		assertContains(prompt, "Spec excerpt:")
		assertContains(prompt, "openapi: 3.0.0")
	}

	@Test
	fun `buildAnalyzePrompt includes intent focus`() {
		val request = CompanionRequest(
			providerId = "test",
			intent = IntentContext(description = "Focus on auth coverage"),
		)

		val prompt = PromptBuilder.buildAnalyzePrompt(request)

		assertContains(prompt, "Focus: Focus on auth coverage")
	}

	@Test
	fun `buildAnalyzePrompt without context produces minimal prompt`() {
		val prompt = PromptBuilder.buildAnalyzePrompt(CompanionRequest(providerId = "test"))

		assertContains(prompt, "API specification analyzer")
		assertFalse(prompt.contains("Endpoints:"))
		assertFalse(prompt.contains("Spec excerpt:"))
		assertFalse(prompt.contains("Focus:"))
	}

	// ── buildGenerateVariantsPrompt ─────────────────────────────────

	@Test
	fun `buildGenerateVariantsPrompt contains variant object schema`() {
		val prompt = PromptBuilder.buildGenerateVariantsPrompt(CompanionRequest(providerId = "test"))
		assertContains(prompt, "mock API response generator")
		assertContains(prompt, "endpointKey")
		assertContains(prompt, "statusCode")
		assertContains(prompt, "contentType")
		assertContains(prompt, "body")
	}

	@Test
	fun `buildGenerateVariantsPrompt includes endpoint selection`() {
		val request = CompanionRequest(
			providerId = "test",
			selection = SelectionContext(
				endpointKeys = listOf("GET /users", "POST /users"),
				variantNames = listOf("not-found", "server-error"),
			),
		)

		val prompt = PromptBuilder.buildGenerateVariantsPrompt(request)

		assertContains(prompt, "Generate variants for these endpoints only:")
		assertContains(prompt, "GET /users")
		assertContains(prompt, "POST /users")
		assertContains(prompt, "Use these variant names when applicable:")
		assertContains(prompt, "not-found")
		assertContains(prompt, "server-error")
	}

	@Test
	fun `buildGenerateVariantsPrompt uses default max variants of 3`() {
		val prompt = PromptBuilder.buildGenerateVariantsPrompt(CompanionRequest(providerId = "test"))
		assertContains(prompt, "Generate up to 3 variants per endpoint")
	}

	@Test
	fun `buildGenerateVariantsPrompt respects custom max variants`() {
		val request = CompanionRequest(
			providerId = "test",
			options = RequestOptions(maxVariants = 5),
		)

		val prompt = PromptBuilder.buildGenerateVariantsPrompt(request)

		assertContains(prompt, "Generate up to 5 variants per endpoint")
	}

	@Test
	fun `buildGenerateVariantsPrompt includes body-generation instructions when intent type is body-generation`() {
		val request = CompanionRequest(
			providerId = "test",
			intent = IntentContext(
				type = "body-generation",
				description = "Generate a list of pets",
			),
		)

		val prompt = PromptBuilder.buildGenerateVariantsPrompt(request)

		assertContains(prompt, "generate or update the response body")
		assertContains(prompt, "Return exactly one variant object")
		assertContains(prompt, "Preserve the exact same JSON schema")
		// Should NOT contain max variants instruction for body-generation
		assertFalse(prompt.contains("Generate up to"))
	}

	@Test
	fun `buildGenerateVariantsPrompt includes project context endpoints`() {
		val request = CompanionRequest(
			providerId = "test",
			projectContext = ProjectContext(
				title = "Video API",
				endpoints = listOf(
					EndpointSummary(method = "GET", path = "/videos"),
				),
			),
		)

		val prompt = PromptBuilder.buildGenerateVariantsPrompt(request)

		assertContains(prompt, "API: Video API")
		assertContains(prompt, "GET /videos")
	}

	// ── buildRefineProjectPrompt ────────────────────────────────────

	@Test
	fun `buildRefineProjectPrompt contains suggestion object schema`() {
		val prompt = PromptBuilder.buildRefineProjectPrompt(CompanionRequest(providerId = "test"))
		assertContains(prompt, "API project structure advisor")
		assertContains(prompt, "category")
		assertContains(prompt, "title")
		assertContains(prompt, "description")
		assertContains(prompt, "affectedEndpoints")
	}

	@Test
	fun `buildRefineProjectPrompt includes endpoint listing with tags`() {
		val request = CompanionRequest(
			providerId = "test",
			projectContext = ProjectContext(
				title = "User API",
				endpoints = listOf(
					EndpointSummary(
						method = "GET",
						path = "/users",
						variantCount = 2,
						tags = listOf("users", "admin"),
					),
				),
			),
		)

		val prompt = PromptBuilder.buildRefineProjectPrompt(request)

		assertContains(prompt, "API: User API")
		assertContains(prompt, "Current endpoints (1 total):")
		assertContains(prompt, "GET /users")
		assertContains(prompt, "(2 variants)")
		assertContains(prompt, "[tags: users, admin]")
	}

	@Test
	fun `buildRefineProjectPrompt includes improvement suggestions`() {
		val prompt = PromptBuilder.buildRefineProjectPrompt(CompanionRequest(providerId = "test"))
		assertContains(prompt, "Alias cleanup")
		assertContains(prompt, "Missing error variants")
		assertContains(prompt, "Auth configuration gaps")
	}

	@Test
	fun `buildRefineProjectPrompt includes focus intent`() {
		val request = CompanionRequest(
			providerId = "test",
			intent = IntentContext(description = "Improve naming conventions"),
		)

		val prompt = PromptBuilder.buildRefineProjectPrompt(request)

		assertContains(prompt, "Focus: Improve naming conventions")
	}
}
