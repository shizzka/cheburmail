package ru.cheburmail.app.backup

import ru.cheburmail.app.crypto.CryptoConstants
import ru.cheburmail.app.crypto.CryptoException
import ru.cheburmail.app.crypto.model.KeyPair
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Экспорт и импорт зашифрованного бэкапа ключей.
 *
 * Формат бэкапа:
 * [version: 1 byte] [salt: 16 bytes] [nonce: 12 bytes] [encrypted_key: N bytes]
 *
 * Шифрование:
 * 1. Пароль -> PBKDF2-HMAC-SHA256 (100_000 итераций) -> 32-byte symmetric key
 * 2. AES-256-GCM (symmetric key + nonce) -> зашифрованный приватный ключ
 *
 * Экспортируется приватный ключ (32 байта) + публичный ключ (32 байта) = 64 байта.
 */
class KeyBackupManager {

    /**
     * Экспортировать ключевую пару в зашифрованный бэкап.
     *
     * @param keyPair ключевая пара для экспорта
     * @param password пароль для шифрования
     * @return зашифрованный бэкап в виде ByteArray
     * @throws CryptoException если шифрование не удалось
     */
    fun exportKeys(keyPair: KeyPair, password: String): ByteArray {
        require(password.length >= MIN_PASSWORD_LENGTH) {
            "Пароль должен быть не менее $MIN_PASSWORD_LENGTH символов"
        }

        try {
            val salt = ByteArray(SALT_BYTES)
            secureRandom.nextBytes(salt)

            val nonce = ByteArray(NONCE_BYTES)
            secureRandom.nextBytes(nonce)

            // Данные для шифрования: publicKey + privateKey
            val plainData = keyPair.publicKey + keyPair.getPrivateKey()

            // Деривация ключа из пароля
            val derivedKey = deriveKey(password, salt)

            // Шифрование AES-256-GCM
            val encrypted = encryptAesGcm(plainData, derivedKey, nonce)

            // Сборка формата: version + salt + nonce + encrypted
            val buffer = ByteBuffer.allocate(1 + SALT_BYTES + NONCE_BYTES + encrypted.size)
            buffer.put(BACKUP_VERSION)
            buffer.put(salt)
            buffer.put(nonce)
            buffer.put(encrypted)

            return buffer.array()

        } catch (e: CryptoException) {
            throw e
        } catch (e: Exception) {
            throw CryptoException("Ошибка экспорта ключей: ${e.message}", e)
        }
    }

    /**
     * Импортировать ключевую пару из зашифрованного бэкапа.
     *
     * @param data зашифрованный бэкап
     * @param password пароль для расшифровки
     * @return восстановленная ключевая пара
     * @throws CryptoException если расшифровка не удалась (неверный пароль, повреждённые данные)
     */
    fun importKeys(data: ByteArray, password: String): KeyPair {
        val minSize = 1 + SALT_BYTES + NONCE_BYTES + CryptoConstants.PUBLIC_KEY_BYTES + CryptoConstants.PRIVATE_KEY_BYTES
        if (data.size < minSize) {
            throw CryptoException("Невалидный формат бэкапа: слишком короткий (${data.size} байт)")
        }

        try {
            val buffer = ByteBuffer.wrap(data)

            val version = buffer.get()
            if (version != BACKUP_VERSION) {
                throw CryptoException("Неподдерживаемая версия бэкапа: $version")
            }

            val salt = ByteArray(SALT_BYTES)
            buffer.get(salt)

            val nonce = ByteArray(NONCE_BYTES)
            buffer.get(nonce)

            val encrypted = ByteArray(buffer.remaining())
            buffer.get(encrypted)

            // Деривация ключа из пароля
            val derivedKey = deriveKey(password, salt)

            // Расшифровка AES-256-GCM
            val plainData = decryptAesGcm(encrypted, derivedKey, nonce)

            // Ожидаем publicKey(32) + privateKey(32) = 64 байта
            val expectedSize = CryptoConstants.PUBLIC_KEY_BYTES + CryptoConstants.PRIVATE_KEY_BYTES
            if (plainData.size != expectedSize) {
                throw CryptoException(
                    "Невалидные данные ключей: ожидается $expectedSize байт, получено ${plainData.size}"
                )
            }

            val publicKey = plainData.copyOfRange(0, CryptoConstants.PUBLIC_KEY_BYTES)
            val privateKey = plainData.copyOfRange(CryptoConstants.PUBLIC_KEY_BYTES, plainData.size)

            return KeyPair(publicKey, privateKey)

        } catch (e: CryptoException) {
            throw e
        } catch (e: javax.crypto.AEADBadTagException) {
            throw CryptoException("Неверный пароль или повреждённый бэкап")
        } catch (e: Exception) {
            throw CryptoException("Ошибка импорта ключей: ${e.message}", e)
        }
    }

    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(
            password.toCharArray(),
            salt,
            PBKDF2_ITERATIONS,
            DERIVED_KEY_BITS
        )
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        return factory.generateSecret(spec).encoded
    }

    private fun encryptAesGcm(data: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        return cipher.doFinal(data)
    }

    private fun decryptAesGcm(data: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, nonce)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        return cipher.doFinal(data)
    }

    companion object {
        const val BACKUP_VERSION: Byte = 1
        const val SALT_BYTES = 16
        const val NONCE_BYTES = 12 // AES-GCM standard
        const val PBKDF2_ITERATIONS = 100_000
        const val DERIVED_KEY_BITS = 256
        const val GCM_TAG_BITS = 128
        const val MIN_PASSWORD_LENGTH = 8

        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"

        private val secureRandom = SecureRandom()
    }
}
