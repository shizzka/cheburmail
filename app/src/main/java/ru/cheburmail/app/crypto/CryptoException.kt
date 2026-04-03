package ru.cheburmail.app.crypto

/**
 * Custom exception for crypto operations (key generation, encryption, decryption).
 */
class CryptoException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
