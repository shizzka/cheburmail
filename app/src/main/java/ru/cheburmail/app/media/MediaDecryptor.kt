package ru.cheburmail.app.media

import kotlinx.serialization.json.Json
import ru.cheburmail.app.crypto.MessageDecryptor
import ru.cheburmail.app.crypto.model.EncryptedEnvelope

/**
 * Расшифровывает медиавложения, полученные по email.
 *
 * Ожидает два EncryptedEnvelope — метаданные и payload — созданные [MediaEncryptor].
 * Использует тот же crypto_box_open_easy (X25519 + XSalsa20-Poly1305), что и текстовые сообщения.
 */
class MediaDecryptor(private val decryptor: MessageDecryptor) {

    /**
     * Результат расшифровки медиавложения.
     *
     * @param metadata  десериализованные метаданные файла
     * @param fileBytes сырые байты файла
     */
    data class DecryptedMedia(
        val metadata: MediaMetadata,
        val fileBytes: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DecryptedMedia) return false
            return metadata == other.metadata &&
                fileBytes.contentEquals(other.fileBytes)
        }

        override fun hashCode(): Int {
            var result = metadata.hashCode()
            result = 31 * result + fileBytes.contentHashCode()
            return result
        }
    }

    /**
     * Расшифровать медиавложение.
     *
     * @param metadataEnvelope зашифрованные метаданные
     * @param payloadEnvelope  зашифрованные байты файла
     * @param senderPublicKey  32-байтовый X25519 публичный ключ отправителя
     * @param recipientPrivateKey 32-байтовый X25519 приватный ключ получателя
     * @return [DecryptedMedia] с метаданными и байтами файла
     * @throws ru.cheburmail.app.crypto.CryptoException при ошибке аутентификации или расшифровки
     * @throws kotlinx.serialization.SerializationException при ошибке парсинга JSON метаданных
     */
    fun decrypt(
        metadataEnvelope: EncryptedEnvelope,
        payloadEnvelope: EncryptedEnvelope,
        senderPublicKey: ByteArray,
        recipientPrivateKey: ByteArray
    ): DecryptedMedia {
        val metadataBytes = decryptor.decrypt(metadataEnvelope, senderPublicKey, recipientPrivateKey)
        val metadataJson = metadataBytes.toString(Charsets.UTF_8)
        val metadata = Json.decodeFromString<MediaMetadata>(metadataJson)

        val fileBytes = decryptor.decrypt(payloadEnvelope, senderPublicKey, recipientPrivateKey)

        return DecryptedMedia(metadata, fileBytes)
    }
}
