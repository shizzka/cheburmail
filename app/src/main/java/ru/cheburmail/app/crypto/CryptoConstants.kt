package ru.cheburmail.app.crypto

/**
 * Constants for NaCl crypto_box (X25519 + XSalsa20-Poly1305).
 */
object CryptoConstants {
    const val PUBLIC_KEY_BYTES = 32
    const val PRIVATE_KEY_BYTES = 32
    const val NONCE_BYTES = 24
    const val MAC_BYTES = 16
}
