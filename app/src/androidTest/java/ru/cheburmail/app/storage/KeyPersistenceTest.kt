package ru.cheburmail.app.storage

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.LazySodiumAndroid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.cheburmail.app.crypto.CryptoProvider
import ru.cheburmail.app.crypto.KeyPairGenerator
import ru.cheburmail.app.crypto.MessageDecryptor
import ru.cheburmail.app.crypto.MessageEncryptor
import ru.cheburmail.app.crypto.NonceGenerator
import ru.cheburmail.app.storage.model.StoredKeyData
import java.io.File

/**
 * Integration tests verifying key persistence across DataStore instances.
 *
 * Simulates app restart by creating a new DataStore instance pointing
 * at the same file and verifying keys survive and remain functional.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class KeyPersistenceTest {

    private lateinit var dataStoreFile: File
    private lateinit var keyPairGenerator: KeyPairGenerator

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        dataStoreFile = File(context.filesDir, "test_persist_${System.nanoTime()}.pb")
        keyPairGenerator = KeyPairGenerator(CryptoProvider.lazySodium)
    }

    @After
    fun tearDown() {
        dataStoreFile.delete()
    }

    private fun createDataStore(): DataStore<StoredKeyData?> {
        return DataStoreFactory.create(
            serializer = KeyStorageSerializer,
            produceFile = { dataStoreFile }
        )
    }

    @Test
    fun keyPair_survivesRestart_and_decryptsMessage() = runTest {
        val lazySodium = CryptoProvider.lazySodium

        // Instance 1: generate key pair and encrypt a message
        val storage1 = SecureKeyStorage(createDataStore(), keyPairGenerator)
        val keyPair = storage1.getOrCreateKeyPair()

        val message = "Hello, CheburMail!".toByteArray()
        val encryptor = MessageEncryptor(lazySodium, NonceGenerator(lazySodium))
        val envelope = encryptor.encrypt(
            message = message,
            recipientPublicKey = keyPair.publicKey,
            senderPrivateKey = keyPair.getPrivateKey()
        )

        // Instance 2: simulate restart — new DataStore, same file
        val storage2 = SecureKeyStorage(createDataStore(), keyPairGenerator)
        val restoredPubKey = storage2.getPublicKey()!!
        val restoredPrivKey = storage2.getPrivateKey()!!

        // Verify keys match
        assertArrayEquals(keyPair.publicKey, restoredPubKey)
        assertArrayEquals(keyPair.getPrivateKey(), restoredPrivKey)

        // Decrypt with restored keys
        val decryptor = MessageDecryptor(lazySodium)
        val decrypted = decryptor.decrypt(
            envelope = envelope,
            senderPublicKey = restoredPubKey,
            recipientPrivateKey = restoredPrivKey
        )

        assertArrayEquals(message, decrypted)
    }

    @Test
    fun storedKey_notAllZeros() = runTest {
        val storage = SecureKeyStorage(createDataStore(), keyPairGenerator)
        val keyPair = storage.getOrCreateKeyPair()

        val allZerosPub = ByteArray(keyPair.publicKey.size)
        val allZerosPriv = ByteArray(keyPair.getPrivateKey().size)

        assertFalse(
            "Public key must not be all zeros",
            keyPair.publicKey.contentEquals(allZerosPub)
        )
        assertFalse(
            "Private key must not be all zeros",
            keyPair.getPrivateKey().contentEquals(allZerosPriv)
        )
    }

    @Test
    fun secondInstance_doesNotGenerateNewKey() = runTest {
        // Instance 1: generate
        val storage1 = SecureKeyStorage(createDataStore(), keyPairGenerator)
        val original = storage1.getOrCreateKeyPair()

        // Instance 2: should read, not generate
        val storage2 = SecureKeyStorage(createDataStore(), keyPairGenerator)
        val restored = storage2.getOrCreateKeyPair()

        assertArrayEquals(original.publicKey, restored.publicKey)
        assertArrayEquals(original.getPrivateKey(), restored.getPrivateKey())
    }
}
