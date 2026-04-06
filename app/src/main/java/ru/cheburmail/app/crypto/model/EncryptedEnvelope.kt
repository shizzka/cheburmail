package ru.cheburmail.app.crypto.model

import ru.cheburmail.app.crypto.CryptoConstants

/**
 * Encrypted envelope: 24-byte nonce + ciphertext.
 * Wire format: [nonce (24 bytes)][ciphertext (variable)]
 */
data class EncryptedEnvelope(
    val nonce: ByteArray,
    val ciphertext: ByteArray
) {
    init {
        require(nonce.size == CryptoConstants.NONCE_BYTES) {
            "Nonce must be ${CryptoConstants.NONCE_BYTES} bytes, got ${nonce.size}"
        }
        require(ciphertext.isNotEmpty()) {
            "Ciphertext must not be empty"
        }
    }

    /**
     * Serialize to bytes: nonce || ciphertext.
     */
    fun toBytes(): ByteArray {
        return nonce + ciphertext
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedEnvelope) return false
        return nonce.contentEquals(other.nonce) &&
            ciphertext.contentEquals(other.ciphertext)
    }

    override fun hashCode(): Int {
        var result = nonce.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        return result
    }

    companion object {
        /**
         * Deserialize from bytes: first 24 bytes = nonce, rest = ciphertext.
         */
        fun fromBytes(data: ByteArray): EncryptedEnvelope {
            require(data.size > CryptoConstants.NONCE_BYTES + CryptoConstants.MAC_BYTES) {
                "Data too short: need at least ${CryptoConstants.NONCE_BYTES + CryptoConstants.MAC_BYTES + 1} bytes, got ${data.size}"
            }
            val nonce = data.copyOfRange(0, CryptoConstants.NONCE_BYTES)
            val ciphertext = data.copyOfRange(CryptoConstants.NONCE_BYTES, data.size)
            return EncryptedEnvelope(nonce, ciphertext)
        }
    }
}
