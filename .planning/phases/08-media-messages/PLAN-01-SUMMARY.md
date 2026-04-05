# PLAN-01 Summary: DB Migration + MediaEncryptor

## Status: COMPLETED

## What was done

### Task 1 — MediaType and MediaDownloadStatus enums
Added two new enums to `app/src/main/java/ru/cheburmail/app/db/Enums.kt`:
- `MediaType`: NONE, IMAGE, FILE, VOICE
- `MediaDownloadStatus`: NONE, PENDING, DOWNLOADING, COMPLETED, FAILED

### Task 2 — TypeConverters for new enums
Extended `Converters.kt` with four new converter methods (from/to) for `MediaType` and `MediaDownloadStatus`, following the existing pattern.

### Task 3 — 9 media columns on MessageEntity
Added the following columns to `MessageEntity` (all with defaults for forward compatibility):
`media_type`, `local_media_uri`, `file_name`, `file_size`, `mime_type`, `thumbnail_uri`, `voice_duration_ms`, `waveform_data`, `media_download_status`.

### Task 4 — MIGRATION_1_2 + version bump
- `CheburMailDatabase.kt`: version 1 → 2
- Added `MIGRATION_1_2` object with 9 `ALTER TABLE messages ADD COLUMN` statements
- Registered migration via `.addMigrations(MIGRATION_1_2)` in the builder
- Imports added: `androidx.room.migration.Migration`, `androidx.sqlite.db.SupportSQLiteDatabase`

### Task 5 — MediaMetadata data class
Created `app/src/main/java/ru/cheburmail/app/media/MediaMetadata.kt`:
- `@Serializable` data class with fields: type, fileName, fileSize, mimeType, width, height, durationMs, waveform, caption
- Companion constants: `TYPE_IMAGE = "image"`, `TYPE_FILE = "file"`, `TYPE_VOICE = "voice"`

### Task 6 — MediaEncryptor
Created `app/src/main/java/ru/cheburmail/app/media/MediaEncryptor.kt`:
- Wraps `MessageEncryptor` (crypto_box_easy)
- Produces two `EncryptedEnvelope`s: one for JSON metadata, one for raw file bytes
- `MAX_PAYLOAD_BYTES = 18_874_368` (18 MB) guard
- `EncryptedMedia` result data class

### Task 7 — MediaDecryptor
Created `app/src/main/java/ru/cheburmail/app/media/MediaDecryptor.kt`:
- Wraps `MessageDecryptor` (crypto_box_open_easy)
- Decrypts two envelopes → `DecryptedMedia(metadata, fileBytes)`
- Proper `equals` / `hashCode` using `ByteArray.contentEquals` / `contentHashCode`

## Build verification
`./gradlew assembleDebug --no-daemon` — BUILD SUCCESSFUL (2m 37s, no errors, warnings pre-exist).

## Files created/modified
- `app/src/main/java/ru/cheburmail/app/db/Enums.kt` — modified
- `app/src/main/java/ru/cheburmail/app/db/Converters.kt` — modified
- `app/src/main/java/ru/cheburmail/app/db/entity/MessageEntity.kt` — modified
- `app/src/main/java/ru/cheburmail/app/db/CheburMailDatabase.kt` — modified
- `app/src/main/java/ru/cheburmail/app/media/MediaMetadata.kt` — created
- `app/src/main/java/ru/cheburmail/app/media/MediaEncryptor.kt` — created
- `app/src/main/java/ru/cheburmail/app/media/MediaDecryptor.kt` — created

## Commits
- `feat(08-01): add MediaType and MediaDownloadStatus enums`
- `feat(08-01): add TypeConverters for MediaType and MediaDownloadStatus`
- `feat(08-01): add 9 media columns to MessageEntity`
- `feat(08-01): add MIGRATION_1_2 and bump database version to 2`
- `feat(08-01): create MediaMetadata serializable data class`
- `feat(08-01): create MediaEncryptor using crypto_box_easy`
- `feat(08-01): create MediaDecryptor with proper equals/hashCode on DecryptedMedia`
