package ru.cheburmail.app.storage

import androidx.datastore.core.Serializer
import ru.cheburmail.app.storage.model.StoredKeyData
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary serializer for [StoredKeyData].
 *
 * Wire format (little-endian):
 * ```
 * [4 bytes pubkey_len][pubkey bytes]
 * [4 bytes privkey_len][privkey bytes]
 * [8 bytes createdAtMillis]
 * ```
 *
 * defaultValue is `null` — meaning no key pair stored yet.
 */
object KeyStorageSerializer : Serializer<StoredKeyData?> {

    override val defaultValue: StoredKeyData? = null

    override suspend fun readFrom(input: InputStream): StoredKeyData? {
        return try {
            val bytes = input.readBytes()
            if (bytes.isEmpty()) return null

            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            val pubLen = buf.getInt()
            if (pubLen < 0 || pubLen > buf.remaining()) return null
            val pubKey = ByteArray(pubLen)
            buf.get(pubKey)

            val privLen = buf.getInt()
            if (privLen < 0 || privLen > buf.remaining()) return null
            val privKey = ByteArray(privLen)
            buf.get(privKey)

            val createdAt = buf.getLong()

            StoredKeyData(pubKey, privKey, createdAt)
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun writeTo(t: StoredKeyData?, output: OutputStream) {
        if (t == null) {
            // Write nothing — empty file signals "no data"
            return
        }

        val size = 4 + t.publicKey.size + 4 + t.privateKey.size + 8
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

        buf.putInt(t.publicKey.size)
        buf.put(t.publicKey)
        buf.putInt(t.privateKey.size)
        buf.put(t.privateKey)
        buf.putLong(t.createdAtMillis)

        output.write(buf.array())
    }
}
