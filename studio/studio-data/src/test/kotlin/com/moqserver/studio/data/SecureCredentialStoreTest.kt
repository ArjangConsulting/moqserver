package com.moqserver.studio.data

import com.maniramezan.credentialkeychain.CredentialKeychain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SecureCredentialStoreTest {

	@Test
	fun `default factory returns a non-null store`() {
		assertNotNull(SecureCredentialStore.default())
	}

	@Test
	fun `keychain store reads and writes through the keychain`() {
		val keychain = InMemoryKeychain()
		val store = KeychainCredentialStore(keychain)
		assertNull(store.read("missing"))

		store.write("key1", "value1")
		assertEquals("value1", store.read("key1"))
		assertEquals("value1", keychain.values["key1"])

		store.write("key1", "updated")
		assertEquals("updated", store.read("key1"))
	}

	@Test
	fun `keychain store delete removes the key`() {
		val store = KeychainCredentialStore(InMemoryKeychain())
		store.write("key1", "value1")

		store.delete("key1")
		assertNull(store.read("key1"))
	}

	@Test
	fun `keychain store writing blank value deletes the key instead of writing`() {
		val keychain = InMemoryKeychain()
		val store = KeychainCredentialStore(keychain)
		store.write("key1", "value1")

		store.write("key1", "  ")
		assertNull(store.read("key1"))
		assertEquals(emptyMap(), keychain.values)
	}

	/** Mirrors the library contract: blank values are rejected rather than stored. */
	private class InMemoryKeychain : CredentialKeychain {
		val values = mutableMapOf<String, String>()

		override fun read(key: String): String? = values[key]

		override fun write(key: String, value: String) {
			require(value.isNotBlank()) { "Credential values must not be blank." }
			values[key] = value
		}

		override fun delete(key: String) {
			values.remove(key)
		}

		override fun clear() = values.clear()
	}
}
