package ru.cheburmail.app.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import ru.cheburmail.app.crypto.CryptoConstants
import ru.cheburmail.app.crypto.CryptoException
import ru.cheburmail.app.crypto.model.KeyPair

/**
 * Тесты KeyBackupManager.
 * Проверяет экспорт/импорт round-trip, неверный пароль, валидацию.
 */
class KeyBackupManagerTest {

    private lateinit var manager: KeyBackupManager
    private lateinit var testKeyPair: KeyPair

    @Before
    fun setup() {
        manager = KeyBackupManager()
        testKeyPair = KeyPair(
            publicKey = ByteArray(CryptoConstants.PUBLIC_KEY_BYTES) { (it + 1).toByte() },
            privateKey = ByteArray(CryptoConstants.PRIVATE_KEY_BYTES) { (it + 100).toByte() }
        )
    }

    @Test
    fun exportImport_roundTrip_keysMatch() {
        val password = "test-password-123"
        val exported = manager.exportKeys(testKeyPair, password)

        assertNotNull(exported)
        assertTrue(exported.isNotEmpty())

        val imported = manager.importKeys(exported, password)

        assertArrayEquals(testKeyPair.publicKey, imported.publicKey)
        assertArrayEquals(testKeyPair.getPrivateKey(), imported.getPrivateKey())
    }

    @Test
    fun exportImport_differentPasswords_exportedDataDiffers() {
        val exported1 = manager.exportKeys(testKeyPair, "password-one-12345")
        val exported2 = manager.exportKeys(testKeyPair, "password-two-12345")

        // Разные данные из-за случайных salt и nonce
        assertTrue(!exported1.contentEquals(exported2))
    }

    @Test(expected = CryptoException::class)
    fun importKeys_wrongPassword_throwsCryptoException() {
        val exported = manager.exportKeys(testKeyPair, "correct-password")
        manager.importKeys(exported, "wrong-password-xx")
    }

    @Test
    fun importKeys_wrongPassword_errorMessage() {
        val exported = manager.exportKeys(testKeyPair, "correct-password")
        try {
            manager.importKeys(exported, "wrong-password-xx")
            fail("Ожидалось CryptoException")
        } catch (e: CryptoException) {
            assertTrue(
                e.message!!.contains("Неверный пароль") ||
                e.message!!.contains("Ошибка импорта")
            )
        }
    }

    @Test(expected = CryptoException::class)
    fun importKeys_corruptedData_throwsCryptoException() {
        val exported = manager.exportKeys(testKeyPair, "test-password-123")
        // Повреждаем данные
        exported[exported.size / 2] = (exported[exported.size / 2] + 1).toByte()
        manager.importKeys(exported, "test-password-123")
    }

    @Test(expected = CryptoException::class)
    fun importKeys_tooShort_throwsCryptoException() {
        manager.importKeys(ByteArray(10), "test-password-123")
    }

    @Test(expected = CryptoException::class)
    fun importKeys_wrongVersion_throwsCryptoException() {
        val exported = manager.exportKeys(testKeyPair, "test-password-123")
        exported[0] = 99.toByte() // Неверная версия
        manager.importKeys(exported, "test-password-123")
    }

    @Test(expected = IllegalArgumentException::class)
    fun exportKeys_shortPassword_throws() {
        manager.exportKeys(testKeyPair, "short")
    }

    @Test
    fun export_format_startsWithVersion() {
        val exported = manager.exportKeys(testKeyPair, "test-password-123")
        assertEquals(KeyBackupManager.BACKUP_VERSION, exported[0])
    }

    @Test
    fun export_format_hasCorrectMinLength() {
        val exported = manager.exportKeys(testKeyPair, "test-password-123")
        val minLength = 1 + KeyBackupManager.SALT_BYTES + KeyBackupManager.NONCE_BYTES +
            CryptoConstants.PUBLIC_KEY_BYTES + CryptoConstants.PRIVATE_KEY_BYTES
        assertTrue("Exported size ${exported.size} < min $minLength", exported.size >= minLength)
    }

    @Test
    fun exportImport_multipleRoundTrips_allSucceed() {
        val passwords = listOf(
            "password-12345678",
            "another-strong-pw",
            "unicode-pass-12345"
        )

        for (password in passwords) {
            val exported = manager.exportKeys(testKeyPair, password)
            val imported = manager.importKeys(exported, password)
            assertArrayEquals(testKeyPair.publicKey, imported.publicKey)
            assertArrayEquals(testKeyPair.getPrivateKey(), imported.getPrivateKey())
        }
    }
}
