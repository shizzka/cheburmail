package ru.cheburmail.app.storage

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.cheburmail.app.crypto.CryptoProvider
import ru.cheburmail.app.crypto.KeyPairGenerator
import ru.cheburmail.app.storage.model.StoredKeyData
import java.io.File

/**
 * Instrumented tests for [SecureKeyStorage].
 *
 * Uses an unencrypted DataStore (plain [KeyStorageSerializer]) to avoid
 * Android Keystore dependency in test — the encryption layer is tested
 * separately on real devices. The logic under test is the storage CRUD.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SecureKeyStorageTest {

    private lateinit var dataStoreFile: File
    private lateinit var dataStore: DataStore<StoredKeyData?>
    private lateinit var storage: SecureKeyStorage
    private lateinit var keyPairGenerator: KeyPairGenerator

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        dataStoreFile = File(context.filesDir, "test_keys_${System.nanoTime()}.pb")

        dataStore = DataStoreFactory.create(
            serializer = KeyStorageSerializer,
            produceFile = { dataStoreFile }
        )

        keyPairGenerator = KeyPairGenerator(CryptoProvider.lazySodium)
        storage = SecureKeyStorage(dataStore, keyPairGenerator)
    }

    @After
    fun tearDown() {
        dataStoreFile.delete()
    }

    @Test
    fun hasKeyPair_returnsFalseInitially() = runTest {
        assertFalse(storage.hasKeyPair())
    }

    @Test
    fun getOrCreateKeyPair_generatesAndStores() = runTest {
        val keyPair = storage.getOrCreateKeyPair()

        assertEquals(32, keyPair.publicKey.size)
        assertEquals(32, keyPair.getPrivateKey().size)
        assertTrue(storage.hasKeyPair())
    }

    @Test
    fun getOrCreateKeyPair_returnsSameKeyOnSecondCall() = runTest {
        val first = storage.getOrCreateKeyPair()
        val second = storage.getOrCreateKeyPair()

        assertArrayEquals(first.publicKey, second.publicKey)
        assertArrayEquals(first.getPrivateKey(), second.getPrivateKey())
    }

    @Test
    fun getPublicKey_returnsStoredKey() = runTest {
        val keyPair = storage.getOrCreateKeyPair()
        val pubKey = storage.getPublicKey()

        assertNotNull(pubKey)
        assertArrayEquals(keyPair.publicKey, pubKey)
    }

    @Test
    fun getPrivateKey_returnsStoredKey() = runTest {
        val keyPair = storage.getOrCreateKeyPair()
        val privKey = storage.getPrivateKey()

        assertNotNull(privKey)
        assertArrayEquals(keyPair.getPrivateKey(), privKey)
    }

    @Test
    fun getPublicKey_returnsNullWhenEmpty() = runTest {
        assertNull(storage.getPublicKey())
    }

    @Test
    fun getPrivateKey_returnsNullWhenEmpty() = runTest {
        assertNull(storage.getPrivateKey())
    }

    @Test
    fun deleteKeyPair_removesStoredKey() = runTest {
        storage.getOrCreateKeyPair()
        assertTrue(storage.hasKeyPair())

        storage.deleteKeyPair()
        assertFalse(storage.hasKeyPair())
        assertNull(storage.getPublicKey())
        assertNull(storage.getPrivateKey())
    }

    @Test
    fun observeHasKeyPair_emitsCorrectValues() = runTest {
        // Initial: no key
        val initialValue = storage.observeHasKeyPair().first()
        assertFalse(initialValue)

        // After creation
        storage.getOrCreateKeyPair()
        val afterCreate = storage.observeHasKeyPair().first()
        assertTrue(afterCreate)

        // After deletion
        storage.deleteKeyPair()
        val afterDelete = storage.observeHasKeyPair().first()
        assertFalse(afterDelete)
    }
}
