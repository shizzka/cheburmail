package ru.cheburmail.app.crypto.model

import ru.cheburmail.app.crypto.CryptoConstants

/**
 * X25519 key pair: 32-byte public key + 32-byte private key.
 */
class KeyPair(
    val publicKey: ByteArray,
    private var privateKey: ByteArray
) {
    init {
        require(publicKey.size == CryptoConstants.PUBLIC_KEY_BYTES) {
            "Public key must be ${CryptoConstants.PUBLIC_KEY_BYTES} bytes, got ${publicKey.size}"
        }
        require(privateKey.size == CryptoConstants.PRIVATE_KEY_BYTES) {
            "Private key must be ${CryptoConstants.PRIVATE_KEY_BYTES} bytes, got ${privateKey.size}"
        }
    }

    fun getPrivateKey(): ByteArray = privateKey.copyOf()

    /**
     * Securely wipe the private key from memory.
     */
    fun wipePrivateKey() {
        privateKey.fill(0)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KeyPair) return false
        return publicKey.contentEquals(other.publicKey) &&
            privateKey.contentEquals(other.privateKey)
    }

    override fun hashCode(): Int {
        var result = publicKey.contentHashCode()
        result = 31 * result + privateKey.contentHashCode()
        return result
    }
}
