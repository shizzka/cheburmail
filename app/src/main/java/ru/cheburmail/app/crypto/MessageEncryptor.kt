package ru.cheburmail.app.crypto

import com.goterl.lazysodium.interfaces.Box
import ru.cheburmail.app.crypto.model.EncryptedEnvelope

/**
 * Encrypts messages using crypto_box_easy (X25519 + XSalsa20-Poly1305).
 */
class MessageEncryptor(
    private val box: Box.Native,
    private val nonceGenerator: NonceGenerator
) {

    /**
     * Encrypt a plaintext message.
     *
     * @param message plaintext bytes
     * @param recipientPublicKey 32-byte X25519 public key of the recipient
     * @param senderPrivateKey 32-byte X25519 private key of the sender
     * @return EncryptedEnvelope containing nonce and ciphertext
     * @throws CryptoException on invalid key sizes or encryption failure
     */
    fun encrypt(
        message: ByteArray,
        recipientPublicKey: ByteArray,
        senderPrivateKey: ByteArray
    ): EncryptedEnvelope {
        if (recipientPublicKey.size != CryptoConstants.PUBLIC_KEY_BYTES) {
            throw CryptoException(
                "Recipient public key must be ${CryptoConstants.PUBLIC_KEY_BYTES} bytes, got ${recipientPublicKey.size}"
            )
        }
        if (senderPrivateKey.size != CryptoConstants.PRIVATE_KEY_BYTES) {
            throw CryptoException(
                "Sender private key must be ${CryptoConstants.PRIVATE_KEY_BYTES} bytes, got ${senderPrivateKey.size}"
            )
        }

        val nonce = nonceGenerator.generate()
        val ciphertext = ByteArray(message.size + CryptoConstants.MAC_BYTES)

        val success = box.cryptoBoxEasy(
            ciphertext,
            message,
            message.size.toLong(),
            nonce,
            recipientPublicKey,
            senderPrivateKey
        )

        if (!success) {
            throw CryptoException("crypto_box_easy failed")
        }

        return EncryptedEnvelope(nonce, ciphertext)
    }
}
