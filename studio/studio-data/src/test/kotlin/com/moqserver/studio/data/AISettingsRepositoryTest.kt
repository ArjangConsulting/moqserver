package com.moqserver.studio.data

import com.moqserver.studio.domain.VariantReferenceSyncPreference
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun `save refuses plaintext fallback when secure storage is unavailable`() {
        val settingsFile = File.createTempFile("ai-settings", ".json")
        val repository = AISettingsRepository(
            settingsFile = settingsFile,
            credentialStore = UnavailableCredentialStore(),
        )

        val error = assertFailsWith<IllegalStateException> {
            repository.save(AISettings(openai = OpenAISettings(apiKey = "openai-secret")))
        }

        val persisted = settingsFile.readText()
        assertFalse(persisted.contains("openai-secret"))
        assertTrue(error.message.orEmpty().contains("secure credential storage is unavailable"))

        settingsFile.delete()
    }

    @Test
    fun `load removes and migrates legacy plaintext keys`() {
        val settingsFile = File.createTempFile("ai-settings", ".json")
        val credentialStore = FakeSecureCredentialStore()
        val repository = AISettingsRepository(settingsFile, credentialStore)
        settingsFile.writeText("""{"openai":{"apiKey":"legacy-secret"}}""")

        val loaded = repository.load()

        assertEquals("legacy-secret", loaded.openai.apiKey)
        assertEquals("legacy-secret", credentialStore.read("openai.api-key"))
        assertFalse(settingsFile.readText().contains("legacy-secret"))
        settingsFile.delete()
    }

    @Test
    fun `load retains legacy plaintext key when secure migration fails`() {
        val settingsFile = File.createTempFile("ai-settings", ".json")
        settingsFile.writeText("""{"openai":{"apiKey":"legacy-secret"}}""")
        val repository = AISettingsRepository(settingsFile, UnavailableCredentialStore())

        val loaded = repository.load()

        assertEquals("legacy-secret", loaded.openai.apiKey)
        assertTrue(settingsFile.readText().contains("legacy-secret"))
        settingsFile.delete()
    }

    @Test
    fun `save restores credentials when a later credential write fails`() {
        val settingsFile = File.createTempFile("ai-settings", ".json")
        val credentialStore = FailingCredentialStore("anthropic.api-key").apply {
            write("openai.api-key", "old-openai")
        }
        val repository = AISettingsRepository(settingsFile, credentialStore)

        assertFailsWith<IllegalStateException> {
            repository.save(
                AISettings(
                    openai = OpenAISettings(apiKey = "new-openai"),
                    anthropic = AnthropicSettings(apiKey = "new-anthropic"),
                ),
            )
        }

        assertEquals("old-openai", credentialStore.read("openai.api-key"))
        assertEquals(null, credentialStore.read("anthropic.api-key"))
        settingsFile.delete()
    }
}

private class UnavailableCredentialStore : SecureCredentialStore {
    override fun read(key: String): String? = null

    override fun write(key: String, value: String) {
        if (value.isBlank()) return
        throw SecureStorageUnavailableException("TestOS")
    }

    override fun delete(key: String) = Unit
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

private class FailingCredentialStore(
    private val failingKey: String,
) : SecureCredentialStore {
    private val values = mutableMapOf<String, String>()

    override fun read(key: String): String? = values[key]

    override fun write(key: String, value: String) {
        if (key == failingKey) error("simulated write failure")
        values[key] = value
    }

    override fun delete(key: String) {
        values.remove(key)
    }
}
