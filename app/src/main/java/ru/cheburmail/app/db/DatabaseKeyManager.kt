package ru.cheburmail.app.db

import android.content.Context
import android.util.Log
import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.security.SecureRandom

/**
 * Управление ключом шифрования базы данных.
 * Ключ БД (32 байта) генерируется случайно и шифруется через Tink AEAD
 * (AES256-GCM с мастер-ключом в Android Keystore).
 */
class DatabaseKeyManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        AeadConfig.register()
    }

    /**
     * Получить или создать ключ для SQLCipher.
     * @return passphrase as ByteArray (32 bytes)
     */
    fun getOrCreateKey(): ByteArray {
        val encrypted = prefs.getString(KEY_ENCRYPTED_DB_KEY, null)
        return if (encrypted != null) {
            decryptKey(encrypted)
        } else {
            val newKey = generateKey()
            val encryptedStr = encryptKey(newKey)
            prefs.edit().putString(KEY_ENCRYPTED_DB_KEY, encryptedStr).apply()
            Log.i(TAG, "Database encryption key generated")
            newKey
        }
    }

    private fun generateKey(): ByteArray {
        val key = ByteArray(32)
        SecureRandom().nextBytes(key)
        return key
    }

    private fun getAead(): Aead {
        val keysetManager = AndroidKeysetManager.Builder()
            .withSharedPref(context, TINK_KEYSET_NAME, PREFS_NAME)
            .withKeyTemplate(AeadKeyTemplates.AES256_GCM)
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
        return keysetManager.keysetHandle.getPrimitive(Aead::class.java)
    }

    private fun encryptKey(key: ByteArray): String {
        val aead = getAead()
        val ciphertext = aead.encrypt(key, ASSOCIATED_DATA)
        return android.util.Base64.encodeToString(ciphertext, android.util.Base64.NO_WRAP)
    }

    private fun decryptKey(encrypted: String): ByteArray {
        val aead = getAead()
        val ciphertext = android.util.Base64.decode(encrypted, android.util.Base64.NO_WRAP)
        return aead.decrypt(ciphertext, ASSOCIATED_DATA)
    }

    companion object {
        private const val TAG = "DatabaseKeyManager"
        private const val PREFS_NAME = "cheburmail_db_key"
        private const val KEY_ENCRYPTED_DB_KEY = "encrypted_db_key"
        private const val TINK_KEYSET_NAME = "db_master_keyset"
        private const val MASTER_KEY_URI = "android-keystore://cheburmail_db_master"
        private val ASSOCIATED_DATA = "cheburmail_db".toByteArray()
    }
}
