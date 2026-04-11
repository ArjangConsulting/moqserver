package com.moqserver.studio.data

import com.moqserver.studio.domain.VariantReferenceSyncPreference
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AISettingsRepositoryTest {

    @Test
    fun `save redacts hosted api keys from the settings file`() {
        val settingsFile = File.createTempFile("ai-settings", ".json")
        val credentialStore = FakeSecureCredentialStore()
        val repository = AISettingsRepository(
            settingsFile = settingsFile,
            credentialStore = credentialStore,
        )

        repository.save(
            AISettings(
                themeMode = ThemePreference.DARK,
                variantReferenceSyncByProject = mapOf(
                    "/tmp/test.moqproj" to VariantReferenceSyncPreference.ALWAYS_UPDATE,
                ),
                openai = OpenAISettings(apiKey = "openai-secret"),
                anthropic = AnthropicSettings(apiKey = "anthropic-secret"),
                gemini = GeminiSettings(apiKey = "gemini-secret"),
            ),
        )

        val persisted = settingsFile.readText()
        assertFalse(persisted.contains("openai-secret"))
        assertFalse(persisted.contains("anthropic-secret"))
        assertFalse(persisted.contains("gemini-secret"))
        assertTrue(persisted.contains("\"themeMode\": \"DARK\""))
        assertTrue(persisted.contains("/tmp/test.moqproj"))
        assertEquals("openai-secret", credentialStore.read("openai.api-key"))
        assertEquals("anthropic-secret", credentialStore.read("anthropic.api-key"))
        assertEquals("gemini-secret", credentialStore.read("gemini.api-key"))

        settingsFile.delete()
    }

    @Test
    fun `load merges credentials from secure storage`() {
        val settingsFile = File.createTempFile("ai-settings", ".json")
        val credentialStore = FakeSecureCredentialStore().apply {
            write("openai.api-key", "openai-secret")
            write("anthropic.api-key", "anthropic-secret")
            write("gemini.api-key", "gemini-secret")
        }
        val repository = AISettingsRepository(
            settingsFile = settingsFile,
            credentialStore = credentialStore,
        )

        settingsFile.writeText(
            """
            {
              "selectedProviderId": "openai",
              "themeMode": "LIGHT",
              "variantReferenceSyncByProject": {
                "/tmp/test.moqproj": "NEVER_UPDATE"
              },
              "openai": { "apiKey": "", "baseUrl": "https://example.com/openai", "defaultModel": "gpt-test" },
              "anthropic": { "apiKey": "", "baseUrl": "https://example.com/anthropic", "defaultModel": "claude-test" },
              "gemini": { "apiKey": "", "baseUrl": "https://example.com/gemini", "defaultModel": "gemini-test" }
            }
            """.trimIndent(),
        )

        val loaded = repository.load()

        assertEquals("openai-secret", loaded.openai.apiKey)
        assertEquals("anthropic-secret", loaded.anthropic.apiKey)
        assertEquals("gemini-secret", loaded.gemini.apiKey)
        assertEquals("openai", loaded.selectedProviderId)
        assertEquals(ThemePreference.LIGHT, loaded.themeMode)
        assertEquals(
            VariantReferenceSyncPreference.NEVER_UPDATE,
            loaded.variantReferenceSyncByProject["/tmp/test.moqproj"],
        )
        assertEquals("https://example.com/openai", loaded.openai.baseUrl)
        assertEquals("claude-test", loaded.anthropic.defaultModel)

        settingsFile.delete()
    }
}

private class FakeSecureCredentialStore : SecureCredentialStore {
    private val values = mutableMapOf<String, String>()

    override fun read(key: String): String? = values[key]

    override fun write(key: String, value: String) {
        values[key] = value
    }

    override fun delete(key: String) {
        values.remove(key)
    }
}
