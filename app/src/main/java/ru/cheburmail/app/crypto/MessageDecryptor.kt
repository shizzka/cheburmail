package ru.cheburmail.app.crypto

import com.goterl.lazysodium.interfaces.Box
import ru.cheburmail.app.crypto.model.EncryptedEnvelope

/**
 * Decrypts messages using crypto_box_open_easy (X25519 + XSalsa20-Poly1305).
 */
open class MessageDecryptor(private val box: Box.Native) {

    /**
     * Decrypt an encrypted envelope.
     *
     * @param envelope the encrypted envelope (nonce + ciphertext)
     * @param senderPublicKey 32-byte X25519 public key of the sender
     * @param recipientPrivateKey 32-byte X25519 private key of the recipient
     * @return decrypted plaintext bytes
     * @throws CryptoException on invalid key sizes, authentication failure, or decryption error
     */
    open fun decrypt(
        envelope: EncryptedEnvelope,
        senderPublicKey: ByteArray,
        recipientPrivateKey: ByteArray
    ): ByteArray {
        if (senderPublicKey.size != CryptoConstants.PUBLIC_KEY_BYTES) {
            throw CryptoException(
                "Sender public key must be ${CryptoConstants.PUBLIC_KEY_BYTES} bytes, got ${senderPublicKey.size}"
            )
        }
        if (recipientPrivateKey.size != CryptoConstants.PRIVATE_KEY_BYTES) {
            throw CryptoException(
                "Recipient private key must be ${CryptoConstants.PRIVATE_KEY_BYTES} bytes, got ${recipientPrivateKey.size}"
            )
        }

        val plaintext = ByteArray(envelope.ciphertext.size - CryptoConstants.MAC_BYTES)

        val success = box.cryptoBoxOpenEasy(
            plaintext,
            envelope.ciphertext,
            envelope.ciphertext.size.toLong(),
            envelope.nonce,
            senderPublicKey,
            recipientPrivateKey
        )

        if (!success) {
            throw CryptoException("crypto_box_open_easy failed: authentication or decryption error")
        }

        return plaintext
    }
}
