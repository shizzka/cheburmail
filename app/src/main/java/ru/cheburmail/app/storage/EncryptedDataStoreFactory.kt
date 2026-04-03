package ru.cheburmail.app.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import ru.cheburmail.app.storage.model.StoredKeyData
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Factory for creating an encrypted DataStore backed by Tink AEAD
 * with an Android Keystore master key.
 *
 * The entire DataStore file content is encrypted/decrypted with
 * AES-256-GCM via Tink, whose keyset is protected by the Android Keystore.
 */
object EncryptedDataStoreFactory {

    const val KEYSET_NAME = "cheburmail_master_keyset"
    const val MASTER_KEY_URI = "android-keystore://cheburmail_master_key"
    const val DATASTORE_FILE = "cheburmail_keys.pb"

    private val ASSOCIATED_DATA = "cheburmail_key_storage".toByteArray()

    /**
     * Must be called once at application startup.
     */
    fun initTink() {
        AeadConfig.register()
    }

    /**
     * Obtains an AEAD primitive backed by Android Keystore.
     */
    fun getAead(context: Context): Aead {
        val keysetManager = AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, "cheburmail_keyset_prefs")
            .withKeyTemplate(AeadKeyTemplates.AES256_GCM)
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
        return keysetManager.keysetHandle.getPrimitive(Aead::class.java)
    }

    /**
     * Creates an encrypted [DataStore] for [StoredKeyData].
     *
     * Wraps [KeyStorageSerializer] so that data is encrypted before writing
     * to disk and decrypted after reading.
     */
    fun create(context: Context): DataStore<StoredKeyData?> {
        val aead = getAead(context)
        val encryptedSerializer = EncryptedSerializer(KeyStorageSerializer, aead)

        return DataStoreFactory.create(
            serializer = encryptedSerializer,
            produceFile = { File(context.filesDir, DATASTORE_FILE) }
        )
    }

    /**
     * Wrapping serializer that encrypts the entire payload with [Aead]
     * before writing and decrypts on read.
     */
    internal class EncryptedSerializer(
        private val delegate: Serializer<StoredKeyData?>,
        private val aead: Aead
    ) : Serializer<StoredKeyData?> {

        override val defaultValue: StoredKeyData? = delegate.defaultValue

        override suspend fun readFrom(input: InputStream): StoredKeyData? {
            val encryptedBytes = input.readBytes()
            if (encryptedBytes.isEmpty()) {
                return defaultValue
            }
            val plainBytes = aead.decrypt(encryptedBytes, ASSOCIATED_DATA)
            return delegate.readFrom(ByteArrayInputStream(plainBytes))
        }

        override suspend fun writeTo(t: StoredKeyData?, output: OutputStream) {
            if (t == null) {
                // Write nothing — empty file means no data
                return
            }
            val plainStream = ByteArrayOutputStream()
            delegate.writeTo(t, plainStream)
            val plainBytes = plainStream.toByteArray()
            val encryptedBytes = aead.encrypt(plainBytes, ASSOCIATED_DATA)
            output.write(encryptedBytes)
        }
    }
}
