package ru.cheburmail.app.storage

import android.content.Context
import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import ru.cheburmail.app.crypto.KeyPairGenerator
import ru.cheburmail.app.crypto.model.KeyPair
import ru.cheburmail.app.storage.model.StoredKeyData

/**
 * High-level API for secure key pair storage.
 *
 * Keys are persisted in an encrypted DataStore (Tink AEAD + Android Keystore).
 * Thread-safe: DataStore serializes all writes internally.
 */
class SecureKeyStorage(
    private val dataStore: DataStore<StoredKeyData?>,
    private val keyPairGenerator: KeyPairGenerator
) {

    /**
     * Returns the existing key pair or generates and stores a new one.
     */
    suspend fun getOrCreateKeyPair(): KeyPair {
        val existing = dataStore.data.first()
        if (existing != null) {
            return KeyPair(
                publicKey = existing.publicKey.copyOf(),
                privateKey = existing.privateKey.copyOf()
            )
        }

        val keyPair = keyPairGenerator.generate()
        dataStore.updateData {
            StoredKeyData(
                publicKey = keyPair.publicKey.copyOf(),
                privateKey = keyPair.getPrivateKey(),
                createdAtMillis = System.currentTimeMillis()
            )
        }
        return keyPair
    }

    /**
     * Returns true if a key pair is currently stored.
     */
    suspend fun hasKeyPair(): Boolean {
        return dataStore.data.first() != null
    }

    /**
     * Returns the stored public key, or null if no key pair exists.
     */
    suspend fun getPublicKey(): ByteArray? {
        return dataStore.data.first()?.publicKey?.copyOf()
    }

    /**
     * Returns the stored private key, or null if no key pair exists.
     */
    suspend fun getPrivateKey(): ByteArray? {
        return dataStore.data.first()?.privateKey?.copyOf()
    }

    /**
     * Observe whether a key pair is stored. Emits on every change.
     */
    fun observeHasKeyPair(): Flow<Boolean> {
        return dataStore.data.map { it != null }
    }

    /**
     * Deletes the stored key pair.
     */
    suspend fun deleteKeyPair() {
        dataStore.updateData { null }
    }

    companion object {
        /**
         * Factory method that creates [SecureKeyStorage] backed by
         * an encrypted DataStore.
         */
        fun create(context: Context, keyPairGenerator: KeyPairGenerator): SecureKeyStorage {
            val dataStore = EncryptedDataStoreFactory.create(context)
            return SecureKeyStorage(dataStore, keyPairGenerator)
        }
    }
}
