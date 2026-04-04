---
phase: 8
plan: 01
title: "DB migration + MediaEncryptor"
wave: 1
depends_on: []
files_modified:
  - app/src/main/java/ru/cheburmail/app/db/Enums.kt
  - app/src/main/java/ru/cheburmail/app/db/Converters.kt
  - app/src/main/java/ru/cheburmail/app/db/entity/MessageEntity.kt
  - app/src/main/java/ru/cheburmail/app/db/CheburMailDatabase.kt
  - app/src/main/java/ru/cheburmail/app/media/MediaMetadata.kt
  - app/src/main/java/ru/cheburmail/app/media/MediaEncryptor.kt
  - app/src/main/java/ru/cheburmail/app/media/MediaDecryptor.kt
autonomous: true
---

# Plan 01: DB Migration + MediaEncryptor

## Objective
Extend the Room database schema to support media messages (images, files, voice) by adding 9 new columns to the `messages` table with a version 1->2 migration. Create `MediaType` and `MediaDownloadStatus` enums. Build `MediaEncryptor` and `MediaDecryptor` classes that encrypt/decrypt arbitrary byte arrays using `crypto_box_easy`, plus a `MediaMetadata` serializable data class for structured attachment metadata transmitted in the email.

## Tasks

<task id="1" title="Add MediaType and MediaDownloadStatus enums" file="app/src/main/java/ru/cheburmail/app/db/Enums.kt">
Add two new enums to the existing `Enums.kt` file:

```kotlin
/**
 * Тип медиа-вложения сообщения.
 */
enum class MediaType {
    NONE,
    IMAGE,
    FILE,
    VOICE
}

/**
 * Статус загрузки/выгрузки медиа-файла.
 */
enum class MediaDownloadStatus {
    NONE,
    PENDING,
    DOWNLOADING,
    COMPLETED,
    FAILED
}
```
</task>

<task id="2" title="Add TypeConverters for new enums" file="app/src/main/java/ru/cheburmail/app/db/Converters.kt" depends_on="1">
Add converter methods for `MediaType` and `MediaDownloadStatus` to the existing `Converters` class:

```kotlin
// --- MediaType ---
@TypeConverter
fun fromMediaType(value: MediaType): String = value.name

@TypeConverter
fun toMediaType(value: String): MediaType = MediaType.valueOf(value)

// --- MediaDownloadStatus ---
@TypeConverter
fun fromMediaDownloadStatus(value: MediaDownloadStatus): String = value.name

@TypeConverter
fun toMediaDownloadStatus(value: String): MediaDownloadStatus = MediaDownloadStatus.valueOf(value)
```
</task>

<task id="3" title="Add 9 media columns to MessageEntity" file="app/src/main/java/ru/cheburmail/app/db/entity/MessageEntity.kt" depends_on="1">
Add 9 new nullable columns to `MessageEntity` after the existing `expiresAt` field. All columns must have default values for the migration:

```kotlin
@ColumnInfo(name = "media_type", defaultValue = "NONE")
val mediaType: MediaType = MediaType.NONE,

@ColumnInfo(name = "local_media_uri")
val localMediaUri: String? = null,

@ColumnInfo(name = "file_name")
val fileName: String? = null,

@ColumnInfo(name = "file_size")
val fileSize: Long? = null,

@ColumnInfo(name = "mime_type")
val mimeType: String? = null,

@ColumnInfo(name = "thumbnail_uri")
val thumbnailUri: String? = null,

@ColumnInfo(name = "voice_duration_ms")
val voiceDurationMs: Long? = null,

@ColumnInfo(name = "waveform_data")
val waveformData: String? = null,

@ColumnInfo(name = "media_download_status", defaultValue = "NONE")
val mediaDownloadStatus: MediaDownloadStatus = MediaDownloadStatus.NONE
```

The `waveform_data` field stores a comma-separated list of integer amplitude values (0-100), e.g. "10,25,40,80,55,30". This avoids adding a complex TypeConverter for `List<Int>`.
</task>

<task id="4" title="Add MIGRATION_1_2 and bump database version" file="app/src/main/java/ru/cheburmail/app/db/CheburMailDatabase.kt" depends_on="3">
1. Change `version = 1` to `version = 2` in the `@Database` annotation.

2. Add a migration object inside the companion object:

```kotlin
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN media_type TEXT NOT NULL DEFAULT 'NONE'")
        db.execSQL("ALTER TABLE messages ADD COLUMN local_media_uri TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE messages ADD COLUMN file_name TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE messages ADD COLUMN file_size INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE messages ADD COLUMN mime_type TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE messages ADD COLUMN thumbnail_uri TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE messages ADD COLUMN voice_duration_ms INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE messages ADD COLUMN waveform_data TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE messages ADD COLUMN media_download_status TEXT NOT NULL DEFAULT 'NONE'")
    }
}
```

3. Add the migration to the database builder:

```kotlin
Room.databaseBuilder(
    context.applicationContext,
    CheburMailDatabase::class.java,
    DB_NAME
).addMigrations(MIGRATION_1_2)
 .build().also { INSTANCE = it }
```

4. Add the required import: `import androidx.room.migration.Migration` and `import androidx.sqlite.db.SupportSQLiteDatabase`.
</task>

<task id="5" title="Create MediaMetadata data class" file="app/src/main/java/ru/cheburmail/app/media/MediaMetadata.kt">
Create the `media/` package and a `@Serializable` data class used as the JSON metadata part in multipart media emails:

```kotlin
package ru.cheburmail.app.media

import kotlinx.serialization.Serializable

/**
 * Metadata for a media attachment, serialized as JSON and sent
 * as Part 0 of the multipart email (encrypted).
 *
 * Part 0: encrypt(JSON.encode(MediaMetadata))
 * Part 1: encrypt(raw file bytes)
 */
@Serializable
data class MediaMetadata(
    /** Type: "image", "file", "voice" */
    val type: String,
    /** Original file name (e.g. "photo_20260404.jpg") */
    val fileName: String,
    /** File size in bytes (of the original unencrypted file) */
    val fileSize: Long,
    /** MIME type (e.g. "image/jpeg", "audio/mp4", "application/pdf") */
    val mimeType: String,
    /** Image/thumbnail width in pixels (images only) */
    val width: Int? = null,
    /** Image/thumbnail height in pixels (images only) */
    val height: Int? = null,
    /** Voice duration in milliseconds (voice only) */
    val durationMs: Long? = null,
    /** Waveform amplitudes 0-100, comma-separated (voice only) */
    val waveform: String? = null,
    /** Optional text caption accompanying the media */
    val caption: String? = null
) {
    companion object {
        const val TYPE_IMAGE = "image"
        const val TYPE_FILE = "file"
        const val TYPE_VOICE = "voice"
    }
}
```
</task>

<task id="6" title="Create MediaEncryptor" file="app/src/main/java/ru/cheburmail/app/media/MediaEncryptor.kt" depends_on="5">
Create `MediaEncryptor` that encrypts both metadata JSON and file bytes using `crypto_box_easy`. It delegates to `MessageEncryptor` but wraps the two-part pattern:

```kotlin
package ru.cheburmail.app.media

import kotlinx.serialization.json.Json
import ru.cheburmail.app.crypto.MessageEncryptor
import ru.cheburmail.app.crypto.model.EncryptedEnvelope

/**
 * Encrypts media messages as two EncryptedEnvelopes:
 *   1. metadata envelope — JSON-encoded MediaMetadata
 *   2. payload envelope  — raw file bytes
 *
 * Both use the same crypto_box_easy (X25519 + XSalsa20-Poly1305)
 * with independent nonces.
 *
 * Max payload size: 18 MB (18_874_368 bytes) — leaves room for
 * Base64 overhead within the 25 MB email limit.
 */
class MediaEncryptor(
    private val encryptor: MessageEncryptor
) {
    data class EncryptedMedia(
        val metadataEnvelope: EncryptedEnvelope,
        val payloadEnvelope: EncryptedEnvelope
    )

    companion object {
        /** Safe binary limit: 25MB email - Base64 overhead (~33%) - headers */
        const val MAX_PAYLOAD_BYTES = 18_874_368L // 18 MB
    }

    /**
     * Encrypt media metadata + file bytes.
     *
     * @param metadata MediaMetadata describing the attachment
     * @param payload raw file bytes (must be <= MAX_PAYLOAD_BYTES)
     * @param recipientPublicKey 32-byte X25519 public key
     * @param senderPrivateKey 32-byte X25519 private key
     * @return EncryptedMedia with two envelopes
     * @throws IllegalArgumentException if payload exceeds MAX_PAYLOAD_BYTES
     * @throws ru.cheburmail.app.crypto.CryptoException on encryption failure
     */
    fun encrypt(
        metadata: MediaMetadata,
        payload: ByteArray,
        recipientPublicKey: ByteArray,
        senderPrivateKey: ByteArray
    ): EncryptedMedia {
        require(payload.size <= MAX_PAYLOAD_BYTES) {
            "Payload size ${payload.size} exceeds maximum $MAX_PAYLOAD_BYTES bytes"
        }

        val metadataJson = Json.encodeToString(MediaMetadata.serializer(), metadata)
        val metadataEnvelope = encryptor.encrypt(
            metadataJson.toByteArray(Charsets.UTF_8),
            recipientPublicKey,
            senderPrivateKey
        )

        val payloadEnvelope = encryptor.encrypt(
            payload,
            recipientPublicKey,
            senderPrivateKey
        )

        return EncryptedMedia(metadataEnvelope, payloadEnvelope)
    }
}
```
</task>

<task id="7" title="Create MediaDecryptor" file="app/src/main/java/ru/cheburmail/app/media/MediaDecryptor.kt" depends_on="5">
Create `MediaDecryptor` that decrypts two envelopes back to `MediaMetadata` + raw bytes:

```kotlin
package ru.cheburmail.app.media

import kotlinx.serialization.json.Json
import ru.cheburmail.app.crypto.MessageDecryptor
import ru.cheburmail.app.crypto.model.EncryptedEnvelope

/**
 * Decrypts media messages from two EncryptedEnvelopes back to
 * MediaMetadata + raw file bytes.
 */
class MediaDecryptor(
    private val decryptor: MessageDecryptor
) {
    data class DecryptedMedia(
        val metadata: MediaMetadata,
        val payload: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DecryptedMedia) return false
            return metadata == other.metadata && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = metadata.hashCode()
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    /**
     * Decrypt media metadata + file bytes.
     *
     * @param metadataEnvelope encrypted metadata envelope
     * @param payloadEnvelope encrypted file bytes envelope
     * @param senderPublicKey 32-byte X25519 public key of the sender
     * @param recipientPrivateKey 32-byte X25519 private key of the recipient
     * @return DecryptedMedia with metadata and raw file bytes
     * @throws ru.cheburmail.app.crypto.CryptoException on decryption failure
     * @throws kotlinx.serialization.SerializationException on invalid metadata JSON
     */
    fun decrypt(
        metadataEnvelope: EncryptedEnvelope,
        payloadEnvelope: EncryptedEnvelope,
        senderPublicKey: ByteArray,
        recipientPrivateKey: ByteArray
    ): DecryptedMedia {
        val metadataBytes = decryptor.decrypt(
            metadataEnvelope,
            senderPublicKey,
            recipientPrivateKey
        )
        val metadata = Json.decodeFromString(
            MediaMetadata.serializer(),
            String(metadataBytes, Charsets.UTF_8)
        )

        val payload = decryptor.decrypt(
            payloadEnvelope,
            senderPublicKey,
            recipientPrivateKey
        )

        return DecryptedMedia(metadata, payload)
    }
}
```
</task>

## Verification
- [ ] Project compiles with `./gradlew assembleDebug --no-daemon`
- [ ] Room schema export in `app/schemas/` shows version 2 with all 9 new columns
- [ ] `MediaMetadata` serializes to JSON and deserializes back correctly (manual test or unit test)
- [ ] `MediaEncryptor.encrypt()` + `MediaDecryptor.decrypt()` round-trip produces identical metadata and payload bytes
- [ ] `MIGRATION_1_2` runs all 9 ALTER TABLE statements without error (can test with `room-testing` MigrationTestHelper)
- [ ] Existing text-only messages still work (default values `NONE` for media_type and media_download_status)

## must_haves
- MessageEntity has 9 new columns: media_type, local_media_uri, file_name, file_size, mime_type, thumbnail_uri, voice_duration_ms, waveform_data, media_download_status
- Database version is 2 with MIGRATION_1_2 that adds all 9 columns via ALTER TABLE
- MediaType enum has values: NONE, IMAGE, FILE, VOICE
- MediaDownloadStatus enum has values: NONE, PENDING, DOWNLOADING, COMPLETED, FAILED
- MediaEncryptor enforces 18MB max payload size
- MediaMetadata is @Serializable and contains: type, fileName, fileSize, mimeType, width, height, durationMs, waveform, caption
- MediaEncryptor and MediaDecryptor round-trip correctly
