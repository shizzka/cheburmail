package ru.cheburmail.app.crypto

import kotlinx.serialization.json.Json

/**
 * Парсер QR-кода обмена ключами.
 * Извлекает публичный ключ и email из JSON-payload.
 */
object QrCodeParser {

    private const val EXPECTED_VERSION = 1

    /**
     * Результат парсинга QR-кода.
     */
    data class QrData(
        val publicKey: ByteArray,
        val email: String,
        val version: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is QrData) return false
            return publicKey.contentEquals(other.publicKey) &&
                email == other.email &&
                version == other.version
        }

        override fun hashCode(): Int {
            var result = publicKey.contentHashCode()
            result = 31 * result + email.hashCode()
            result = 31 * result + version
            return result
        }
    }

    /**
     * Парсит JSON-строку из QR-кода.
     *
     * @param content строка из QR-кода
     * @return QrData с публичным ключом и email
     * @throws QrParseException при ошибке парсинга или невалидных данных
     */
    fun parse(content: String): QrData {
        try {
            val payload = Json.decodeFromString<QrCodeGenerator.QrPayload>(content)

            if (payload.v != EXPECTED_VERSION) {
                throw QrParseException(
                    "Неподдерживаемая версия QR-кода: ${payload.v} (ожидается $EXPECTED_VERSION)"
                )
            }

            if (payload.email.isBlank()) {
                throw QrParseException("Email не может быть пустым")
            }

            val publicKey = android.util.Base64.decode(
                payload.pk,
                android.util.Base64.NO_WRAP
            )

            if (publicKey.size != CryptoConstants.PUBLIC_KEY_BYTES) {
                throw QrParseException(
                    "Невалидный публичный ключ: ожидается ${CryptoConstants.PUBLIC_KEY_BYTES} байт, " +
                        "получено ${publicKey.size}"
                )
            }

            return QrData(
                publicKey = publicKey,
                email = payload.email,
                version = payload.v
            )
        } catch (e: QrParseException) {
            throw e
        } catch (e: Exception) {
            throw QrParseException("Невалидный QR-код: ${e.message}", e)
        }
    }

    class QrParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
}
