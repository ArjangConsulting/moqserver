package com.moqserver.studio.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SecureCredentialStoreTest {

	@Test
	fun `default factory returns a non-null store`() {
		val store = SecureCredentialStore.default()
		// On macOS this returns MacOSKeychainCredentialStore,
		// on other platforms it returns UnsupportedSecureCredentialStore.
		// Either way, it should be non-null.
		assertNotNull(store)
	}

	@Test
	fun `in-memory fake store reads and writes correctly`() {
		val store = InMemoryCredentialStore()
		assertNull(store.read("missing"))

		store.write("key1", "value1")
		assertEquals("value1", store.read("key1"))

		store.write("key1", "updated")
		assertEquals("updated", store.read("key1"))
	}

	@Test
	fun `in-memory fake store delete removes the key`() {
		val store = InMemoryCredentialStore()
		store.write("key1", "value1")
		assertEquals("value1", store.read("key1"))

		store.delete("key1")
		assertNull(store.read("key1"))
	}

	@Test
	fun `in-memory fake store delete of nonexistent key is safe`() {
		val store = InMemoryCredentialStore()
		store.delete("nonexistent") // should not throw
		assertNull(store.read("nonexistent"))
	}

	@Test
	fun `in-memory fake store writing blank value deletes the key`() {
		val store = InMemoryCredentialStore()
		store.write("key1", "value1")
		store.write("key1", "")
		assertNull(store.read("key1"))
	}

	/**
	 * A simple in-memory credential store for testing the interface contract.
	 */
	private class InMemoryCredentialStore : SecureCredentialStore {
		private val storage = mutableMapOf<String, String>()

		override fun read(key: String): String? = storage[key]

		override fun write(key: String, value: String) {
			if (value.isBlank()) {
				delete(key)
				return
			}
			storage[key] = value
		}

		override fun delete(key: String) {
			storage.remove(key)
		}
	}
}
