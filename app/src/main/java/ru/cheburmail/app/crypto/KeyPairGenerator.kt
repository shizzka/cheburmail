package ru.cheburmail.app.crypto

import com.goterl.lazysodium.interfaces.Box
import ru.cheburmail.app.crypto.model.KeyPair

/**
 * Generates X25519 key pairs via crypto_box_keypair.
 */
open class KeyPairGenerator(private val box: Box.Lazy) {

    /**
     * Generate a new X25519 key pair.
     * @throws CryptoException if key generation fails
     */
    fun generate(): KeyPair {
        try {
            val kp = box.cryptoBoxKeypair()
            return KeyPair(
                publicKey = kp.publicKey.asBytes,
                privateKey = kp.secretKey.asBytes
            )
        } catch (e: Exception) {
            throw CryptoException("Failed to generate key pair", e)
        }
    }
}
