package com.moqserver.studio

import com.moqserver.studio.data.AISettings
import com.moqserver.studio.data.OpenAISettings
import io.github.maniramezan.kmpcomponents.isValid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderConfigurationMappingTest {
	@Test
	fun `shared provider forms preserve all settings on a round trip`() {
		val settings = AISettings(openai = OpenAISettings(apiKey = "saved-secret"))
		for (provider in AIProviderTab.entries) {
			assertEquals(settings, settings.withProviderConfiguration(provider, provider.configuration(settings)))
		}
	}

	@Test
	fun `editing one provider preserves other providers and preferences`() {
		val settings = AISettings(openai = OpenAISettings(apiKey = "saved-secret"))
		val draft = AIProviderTab.ANTHROPIC.configuration(settings).copy(apiKey = "draft-secret", model = "draft-model")
		val updated = settings.withProviderConfiguration(AIProviderTab.ANTHROPIC, draft)

		assertEquals("draft-secret", updated.anthropic.apiKey)
		assertEquals("draft-model", updated.anthropic.defaultModel)
		assertEquals(settings.copy(anthropic = updated.anthropic), updated)
		assertFalse(draft.toString().contains("draft-secret"))
	}

	@Test
	fun `shared validation permits local providers without keys and rejects malformed URLs`() {
		val settings = AISettings()
		assertTrue(AIProviderTab.OLLAMA.configuration(settings).isValid(apiKeyRequired = false))
		assertFalse(AIProviderTab.OPENAI.configuration(settings).isValid())
		assertFalse(AIProviderTab.OLLAMA.configuration(settings).copy(baseUrl = "invalid").isValid(apiKeyRequired = false))
	}
}
