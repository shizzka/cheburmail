package ru.cheburmail.app.crypto

import kotlinx.serialization.json.Json

/**
 * Парсер QR-кода обмена ключами.
 * Извлекает публичный ключ и email из JSON-payload.
 */
object QrCodeParser {

    private val SUPPORTED_VERSIONS = setOf(1, 2)
    /** Защита от QR-мегапейлоадов: ограничиваем число алиасов на парсинге. */
    private const val MAX_ALIASES = 16

    /**
     * Результат парсинга QR-кода.
     */
    data class QrData(
        val publicKey: ByteArray,
        val email: String,
        val version: Int,
        val aliases: List<String> = emptyList()
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is QrData) return false
            return publicKey.contentEquals(other.publicKey) &&
                email == other.email &&
                version == other.version &&
                aliases == other.aliases
        }

        override fun hashCode(): Int {
            var result = publicKey.contentHashCode()
            result = 31 * result + email.hashCode()
            result = 31 * result + version
            result = 31 * result + aliases.hashCode()
            return result
        }
    }

    /**
     * Парсит JSON-строку из QR-кода. Поддерживает v1 (legacy, без алиасов) и
     * v2 (с алиасами). Неизвестный major-version отклоняется.
     */
    fun parse(content: String): QrData {
        try {
            val payload = Json.decodeFromString<QrCodeGenerator.QrPayload>(content)

            if (payload.v !in SUPPORTED_VERSIONS) {
                throw QrParseException(
                    "Неподдерживаемая версия QR-кода: ${payload.v} (поддерживаются $SUPPORTED_VERSIONS)"
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

            val aliases = payload.aliases.orEmpty()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.equals(payload.email, ignoreCase = true) }
                .distinctBy { it.lowercase() }
                .take(MAX_ALIASES)

            return QrData(
                publicKey = publicKey,
                email = payload.email,
                version = payload.v,
                aliases = aliases
            )
        } catch (e: QrParseException) {
            throw e
        } catch (e: Exception) {
            throw QrParseException("Невалидный QR-код: ${e.message}", e)
        }
    }

    class QrParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
}
