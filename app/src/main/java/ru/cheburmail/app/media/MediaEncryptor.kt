package ru.cheburmail.app.media

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.cheburmail.app.crypto.MessageEncryptor
import ru.cheburmail.app.crypto.model.EncryptedEnvelope

/**
 * Шифрует медиавложения перед отправкой по email.
 *
 * Каждое медиасообщение отправляется как два EncryptedEnvelope:
 *   1. metadataEnvelope — зашифрованный JSON с MediaMetadata
 *   2. payloadEnvelope  — зашифрованные байты самого файла
 *
 * Использует тот же crypto_box_easy (X25519 + XSalsa20-Poly1305), что и текстовые сообщения.
 */
class MediaEncryptor(private val encryptor: MessageEncryptor) {

    /**
     * Результат шифрования медиафайла.
     *
     * @param metadataEnvelope зашифрованные метаданные (JSON → bytes → EncryptedEnvelope)
     * @param payloadEnvelope  зашифрованные байты файла
     */
    data class EncryptedMedia(
        val metadataEnvelope: EncryptedEnvelope,
        val payloadEnvelope: EncryptedEnvelope
    )

    /**
     * Зашифровать медиавложение.
     *
     * @param metadata        метаданные файла (тип, имя, размер, MIME и др.)
     * @param fileBytes       сырые байты файла
     * @param recipientPublicKey 32-байтовый X25519 публичный ключ получателя
     * @param senderPrivateKey   32-байтовый X25519 приватный ключ отправителя
     * @return [EncryptedMedia] с двумя конвертами
     * @throws ru.cheburmail.app.crypto.CryptoException если размер превышает [MAX_PAYLOAD_BYTES]
     *         или при ошибке шифрования
     */
    fun encrypt(
        metadata: MediaMetadata,
        fileBytes: ByteArray,
        recipientPublicKey: ByteArray,
        senderPrivateKey: ByteArray
    ): EncryptedMedia {
        if (fileBytes.size > MAX_PAYLOAD_BYTES) {
            throw ru.cheburmail.app.crypto.CryptoException(
                "Media payload too large: ${fileBytes.size} bytes (max $MAX_PAYLOAD_BYTES)"
            )
        }

        val metadataJson = Json.encodeToString(metadata)
        val metadataBytes = metadataJson.toByteArray(Charsets.UTF_8)

        val metadataEnvelope = encryptor.encrypt(metadataBytes, recipientPublicKey, senderPrivateKey)
        val payloadEnvelope = encryptor.encrypt(fileBytes, recipientPublicKey, senderPrivateKey)

        return EncryptedMedia(metadataEnvelope, payloadEnvelope)
    }

    companion object {
        /** Максимальный размер файла: 18 МБ (18 * 1024 * 1024). */
        const val MAX_PAYLOAD_BYTES = 18_874_368
    }
}
