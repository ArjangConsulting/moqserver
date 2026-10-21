package com.moqserver.studio.data

import com.maniramezan.credentialkeychain.CredentialKeychain

/**
 * Secret storage for Studio credentials. Implementations throw
 * [com.maniramezan.credentialkeychain.KeychainUnavailableException] when secure persistence
 * cannot be provided; callers must not fall back to plaintext.
 */
interface SecureCredentialStore {
    fun read(key: String): String?

    /** Stores [value] under [key]; a blank [value] deletes the entry instead. */
    fun write(key: String, value: String)

    fun delete(key: String)

    companion object {
        private const val SERVICE_NAME = "com.moqserver.studio"

        fun default(): SecureCredentialStore =
            KeychainCredentialStore(CredentialKeychain.forCurrentPlatform(serviceName = SERVICE_NAME))
    }
}

/** Backs [SecureCredentialStore] with the platform keychain from `credential-keychain-kotlin`. */
internal class KeychainCredentialStore(
    private val keychain: CredentialKeychain,
) : SecureCredentialStore {
    override fun read(key: String): String? = keychain.read(key)

    override fun write(key: String, value: String) {
        if (value.isBlank()) {
            delete(key)
            return
        }
        keychain.write(key, value)
    }

    override fun delete(key: String) = keychain.delete(key)
}
