package com.moqserver.studio.data

import com.maniramezan.credentialkeychain.CredentialKeychain
import com.maniramezan.credentialkeychain.KeychainUnavailableException
import io.github.maniramezan.testcommons.CallRecorder
import io.github.maniramezan.testcommons.TemporaryDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CredentialPersistenceTest {
	private val directory = TemporaryDirectory("credential-persistence")

	@AfterTest
	fun tearDown() {
		directory.remove()
	}

	@Test
	fun `saving and reloading uses the platform keychain contract and never writes secrets to settings`() {
		val keychain = RecordingKeychain()
		val settingsFile = directory.write("{}", "settings.json")
		val repository = AISettingsRepository(settingsFile, keychain)
		val settings = AISettings(
			openai = OpenAISettings(apiKey = "first-secret"),
			anthropic = AnthropicSettings(apiKey = "second-secret"),
			gemini = GeminiSettings(apiKey = "third-secret"),
		)

		repository.save(settings)
		assertEquals(settings, repository.load())
		assertEquals(
			listOf("openai.api-key", "anthropic.api-key", "gemini.api-key"),
			keychain.writes.values().map { it.first },
		)
		assertFalse(settingsFile.readText().contains("secret"))

		keychain.writes.reset()
		repository.save(settings.copy(openai = settings.openai.copy(apiKey = " \t")))
		assertEquals(listOf("openai.api-key"), keychain.deletions.values())
		assertFalse(keychain.writes.values().any { it.first == "openai.api-key" })
		assertEquals("", repository.load().openai.apiKey)
		assertEquals("second-secret", repository.load().anthropic.apiKey)
	}

	@Test
	fun `every keychain read failure blocks writes and preserves persisted settings`() {
		for (reason in KeychainUnavailableException.Reason.entries) {
			val failure = KeychainUnavailableException(reason, "Unavailable test keychain")
			val keychain = RecordingKeychain(readFailure = failure)
			val settingsFile = directory.write("{}", "settings.json")
			val repository = AISettingsRepository(settingsFile, keychain)

			val error = assertFailsWith<IllegalStateException> {
				repository.save(AISettings(openai = OpenAISettings(apiKey = "new-secret")))
			}

			assertSame(failure, error.cause)
			assertEquals(credentialFailureMessage(reason), error.message)
			assertEquals("{}", settingsFile.readText())
			assertEquals(0, keychain.writes.count)
			assertEquals(0, keychain.deletions.count)
		}
	}

	@Test
	fun `every keychain write failure retains its reason without leaking the key`() {
		for (reason in KeychainUnavailableException.Reason.entries) {
			val failure = KeychainUnavailableException(reason, "Unavailable test keychain")
			val keychain = RecordingKeychain(writeFailure = failure)
			val settingsFile = directory.write("{}", "settings.json")
			val repository = AISettingsRepository(settingsFile, keychain)

			val error = assertFailsWith<IllegalStateException> {
				repository.save(AISettings(openai = OpenAISettings(apiKey = "new-secret")))
			}

			assertSame(failure, error.cause)
			assertEquals(credentialFailureMessage(reason), error.message)
			assertFalse(error.message.orEmpty().contains("new-secret"))
			assertTrue(keychain.values.isEmpty())
			assertEquals("{}", settingsFile.readText())
		}
	}

	private class RecordingKeychain(
		private val readFailure: KeychainUnavailableException? = null,
		private val writeFailure: KeychainUnavailableException? = null,
	) : CredentialKeychain {
		val values = mutableMapOf<String, String>()
		val writes = CallRecorder<Pair<String, String>>()
		val deletions = CallRecorder<String>()

		override fun read(key: String): String? {
			readFailure?.let { throw it }
			return values[key]
		}

		override fun write(key: String, value: String) {
			require(value.isNotBlank())
			writeFailure?.let { throw it }
			writes.record(key to value)
			values[key] = value
		}

		override fun delete(key: String) {
			deletions.record(key)
			values.remove(key)
		}

		override fun clear() = values.clear()
	}
}
