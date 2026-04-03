package ru.cheburmail.app.crypto

import com.goterl.lazysodium.interfaces.Random

/**
 * Generates 24-byte nonces for crypto_box using libsodium's CSPRNG.
 */
class NonceGenerator(private val random: Random) {

    /**
     * Generate a random 24-byte nonce.
     */
    fun generate(): ByteArray {
        return random.randomBytesBuf(CryptoConstants.NONCE_BYTES)
    }
}
