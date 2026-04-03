package ru.cheburmail.app.storage.model

/**
 * Persisted key pair data.
 *
 * Uses explicit equals/hashCode because data classes delegate
 * to referential equality for ByteArray fields.
 */
class StoredKeyData(
    val publicKey: ByteArray,
    val privateKey: ByteArray,
    val createdAtMillis: Long
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StoredKeyData) return false
        return publicKey.contentEquals(other.publicKey) &&
            privateKey.contentEquals(other.privateKey) &&
            createdAtMillis == other.createdAtMillis
    }

    override fun hashCode(): Int {
        var result = publicKey.contentHashCode()
        result = 31 * result + privateKey.contentHashCode()
        result = 31 * result + createdAtMillis.hashCode()
        return result
    }

    override fun toString(): String =
        "StoredKeyData(pubKeyLen=${publicKey.size}, privKeyLen=${privateKey.size}, createdAt=$createdAtMillis)"
}
