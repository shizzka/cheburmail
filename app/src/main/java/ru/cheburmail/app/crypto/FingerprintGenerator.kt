package ru.cheburmail.app.crypto

import java.security.MessageDigest

/**
 * Генератор safety numbers (отпечатков ключей).
 *
 * Safety number — это хэш SHA-256 от конкатенации двух публичных ключей,
 * отсортированных лексикографически. Это гарантирует, что оба участника
 * видят одинаковый отпечаток.
 *
 * Формат отображения: 12 групп по 5 цифр (60 цифр), например:
 * 12345 67890 12345 67890 12345 67890
 * 12345 67890 12345 67890 12345 67890
 */
object FingerprintGenerator {

    private const val DIGEST_ALGORITHM = "SHA-256"
    private const val GROUP_SIZE = 5
    private const val TOTAL_DIGITS = 60

    /**
     * Генерирует safety number из двух публичных ключей.
     *
     * @param localPublicKey публичный ключ локального пользователя
     * @param remotePublicKey публичный ключ контакта
     * @return строка из 60 цифр, разбитая на 12 групп по 5
     */
    fun generate(localPublicKey: ByteArray, remotePublicKey: ByteArray): String {
        require(localPublicKey.size == CryptoConstants.PUBLIC_KEY_BYTES) {
            "Local public key must be ${CryptoConstants.PUBLIC_KEY_BYTES} bytes"
        }
        require(remotePublicKey.size == CryptoConstants.PUBLIC_KEY_BYTES) {
            "Remote public key must be ${CryptoConstants.PUBLIC_KEY_BYTES} bytes"
        }

        // Сортируем ключи лексикографически для детерминированного результата
        val (first, second) = sortKeys(localPublicKey, remotePublicKey)

        val digest = MessageDigest.getInstance(DIGEST_ALGORITHM)
        digest.update(first)
        digest.update(second)
        val hash = digest.digest()

        return formatFingerprint(hash)
    }

    /**
     * Генерирует короткий хэш для хранения в БД.
     * Hex-строка SHA-256 (64 символа).
     */
    fun generateHex(localPublicKey: ByteArray, remotePublicKey: ByteArray): String {
        val (first, second) = sortKeys(localPublicKey, remotePublicKey)

        val digest = MessageDigest.getInstance(DIGEST_ALGORITHM)
        digest.update(first)
        digest.update(second)
        val hash = digest.digest()

        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun sortKeys(a: ByteArray, b: ByteArray): Pair<ByteArray, ByteArray> {
        for (i in a.indices) {
            val cmp = (a[i].toInt() and 0xFF).compareTo(b[i].toInt() and 0xFF)
            if (cmp < 0) return a to b
            if (cmp > 0) return b to a
        }
        return a to b // ключи идентичны
    }

    private fun formatFingerprint(hash: ByteArray): String {
        // Преобразуем байты хэша в цифры
        val digits = StringBuilder()
        for (byte in hash) {
            val value = byte.toInt() and 0xFF
            digits.append("%03d".format(value % 1000))
            if (digits.length >= TOTAL_DIGITS) break
        }

        // Обрезаем до нужной длины
        val trimmed = digits.substring(0, TOTAL_DIGITS)

        // Разбиваем на группы по 5 цифр
        return trimmed.chunked(GROUP_SIZE).joinToString(" ")
    }
}
