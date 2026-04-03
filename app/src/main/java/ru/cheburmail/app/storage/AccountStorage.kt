package ru.cheburmail.app.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import com.google.crypto.tink.Aead
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.cheburmail.app.transport.EmailProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Модель хранения аккаунтов в DataStore.
 */
@Serializable
data class StoredAccount(
    val email: String,
    val password: String,
    val provider: String
)

@Serializable
data class StoredAccountList(
    val accounts: List<StoredAccount> = emptyList()
)

/**
 * Сериализатор списка аккаунтов в JSON.
 */
object AccountListSerializer : Serializer<StoredAccountList> {
    override val defaultValue: StoredAccountList = StoredAccountList()

    override suspend fun readFrom(input: InputStream): StoredAccountList {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return defaultValue
        return Json.decodeFromString(String(bytes, Charsets.UTF_8))
    }

    override suspend fun writeTo(t: StoredAccountList, output: OutputStream) {
        val json = Json.encodeToString(t)
        output.write(json.toByteArray(Charsets.UTF_8))
    }
}

/**
 * Фабрика зашифрованного DataStore для хранения email-аккаунтов.
 * Использует Tink AEAD (AES-256-GCM) + Android Keystore.
 */
object AccountStorage {

    private const val DATASTORE_FILE = "cheburmail_accounts.pb"
    private val ASSOCIATED_DATA = "cheburmail_account_storage".toByteArray()

    fun create(context: Context): DataStore<StoredAccountList> {
        val aead = EncryptedDataStoreFactory.getAead(context)
        val encryptedSerializer = EncryptedAccountSerializer(AccountListSerializer, aead)

        return DataStoreFactory.create(
            serializer = encryptedSerializer,
            produceFile = { File(context.filesDir, DATASTORE_FILE) }
        )
    }

    internal class EncryptedAccountSerializer(
        private val delegate: Serializer<StoredAccountList>,
        private val aead: Aead
    ) : Serializer<StoredAccountList> {

        override val defaultValue: StoredAccountList = delegate.defaultValue

        override suspend fun readFrom(input: InputStream): StoredAccountList {
            val encryptedBytes = input.readBytes()
            if (encryptedBytes.isEmpty()) return defaultValue
            val plainBytes = aead.decrypt(encryptedBytes, ASSOCIATED_DATA)
            return delegate.readFrom(ByteArrayInputStream(plainBytes))
        }

        override suspend fun writeTo(t: StoredAccountList, output: OutputStream) {
            val plainStream = ByteArrayOutputStream()
            delegate.writeTo(t, plainStream)
            val plainBytes = plainStream.toByteArray()
            val encryptedBytes = aead.encrypt(plainBytes, ASSOCIATED_DATA)
            output.write(encryptedBytes)
        }
    }
}
