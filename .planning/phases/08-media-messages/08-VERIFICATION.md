---
phase: 8
status: gaps_found
score: 35/37
---
# Phase 8 Verification

## Results

| # | Must-have | Status | Location |
|---|-----------|--------|----------|
| 1 | MessageEntity has 9 new columns: media_type, local_media_uri, file_name, file_size, mime_type, thumbnail_uri, voice_duration_ms, waveform_data, media_download_status | PASS | `app/src/main/java/ru/cheburmail/app/db/entity/MessageEntity.kt:52-77` |
| 2 | Database version is 2 with MIGRATION_1_2 that adds all 9 columns via ALTER TABLE | PASS | `app/src/main/java/ru/cheburmail/app/db/CheburMailDatabase.kt:28,42-54` |
| 3 | MediaType enum has values: NONE, IMAGE, FILE, VOICE | PASS | `app/src/main/java/ru/cheburmail/app/db/Enums.kt:54-59` |
| 4 | MediaDownloadStatus enum has values: NONE, PENDING, DOWNLOADING, COMPLETED, FAILED | PASS | `app/src/main/java/ru/cheburmail/app/db/Enums.kt:69-75` |
| 5 | MediaEncryptor enforces 18MB max payload size | PASS | `app/src/main/java/ru/cheburmail/app/media/MediaEncryptor.kt:47-51,64` — `MAX_PAYLOAD_BYTES = 18_874_368` |
| 6 | MediaMetadata is @Serializable and contains: type, fileName, fileSize, mimeType, width, height, durationMs, waveform, caption | PASS | `app/src/main/java/ru/cheburmail/app/media/MediaMetadata.kt:9-39` |
| 7 | MediaEncryptor and MediaDecryptor exist and use MessageEncryptor/MessageDecryptor | PASS | `app/src/main/java/ru/cheburmail/app/media/MediaEncryptor.kt:17`, `MediaDecryptor.kt:13` |
| 8 | EmailMessage has `attachment: ByteArray?` field and `MEDIA_SUBJECT_SUFFIX = "/M"` constant | PASS | `app/src/main/java/ru/cheburmail/app/transport/EmailMessage.kt:9,15` |
| 9 | SmtpClient.sendWithAttachment() sends multipart/mixed with 2 MIME parts and 120s write timeout | PASS | `app/src/main/java/ru/cheburmail/app/transport/SmtpClient.kt:88-146` — `MEDIA_WRITE_TIMEOUT = 120000`, 2 MimeBodyParts in MimeMultipart("mixed") |
| 10 | ImapClient.fetchMessages() extracts Part 1 (attachment) for media messages | PASS | `app/src/main/java/ru/cheburmail/app/transport/ImapClient.kt:85-91,268-289` — `extractAttachment()` gets `getBodyPart(1)` |
| 11 | EmailFormatter.formatMedia() produces subject ending with /M | PASS | `app/src/main/java/ru/cheburmail/app/transport/EmailFormatter.kt:44-64` — subject = `...${MEDIA_SUBJECT_SUFFIX}` |
| 12 | EmailParser.parseMedia() returns ParsedMediaMessage with two EncryptedEnvelopes | PASS | `app/src/main/java/ru/cheburmail/app/transport/EmailParser.kt:73-150` |
| 13 | Existing text-only send/receive flow is unaffected | PASS | `SmtpClient.send()` unchanged; `ReceiveWorker` processes text messages first, media in separate loop |
| 14 | Coil 3.x is added as dependency | PASS | `gradle/libs.versions.toml:29` — `coil = "3.1.0"`; `app/build.gradle.kts:129` — `implementation(libs.coil.compose)` |
| 15 | FileProvider is configured for camera captures | PASS | `app/src/main/AndroidManifest.xml:56-57` — `androidx.core.content.FileProvider` with `${applicationId}.fileprovider` |
| 16 | ImageCompressor resizes to max 1920px and generates 256px thumbnails | PASS | `app/src/main/java/ru/cheburmail/app/media/ImageCompressor.kt:190-197` — `MAX_FULL_PX = 1920`, `MAX_THUMB_PX = 256` |
| 17 | MediaFileManager saves/loads files from app cache directories | PASS | `app/src/main/java/ru/cheburmail/app/media/MediaFileManager.kt` — saves to cacheDir/images, thumbnails, voice, files |
| 18 | Attach button in ChatScreen with gallery + camera options | PASS | `app/src/main/java/ru/cheburmail/app/ui/chat/ChatScreen.kt:248-291` — DropdownMenu with Gallery, Camera, File options |
| 19 | ChatViewModel.onImagePicked() compresses, encrypts, saves locally, queues for send | PASS | `app/src/main/java/ru/cheburmail/app/ui/chat/ChatViewModel.kt:318-380` |
| 20 | SendWorker detects media messages and uses sendWithAttachment() | FAIL | `app/src/main/java/ru/cheburmail/app/transport/SendWorker.kt:88-109` — SendWorker expects combined `[4-byte metaLen][meta][payload]` format in a single `SendQueueEntity`, but `ChatViewModel.encryptAndQueueMedia()` inserts two *separate* queue entries (`${msgId}_meta` and `msgId`). The `_meta` entry's messageId won't resolve to a MessageEntity (FK constraint or null return → FAILED). The logic is architecturally inconsistent. |
| 21 | ReceiveWorker decrypts media messages and saves to local storage | PASS | `app/src/main/java/ru/cheburmail/app/transport/ReceiveWorker.kt:207-317` — full media decrypt+save pipeline |
| 22 | ImageMessageBubble displays thumbnail with tap-to-view full-size | PASS | `app/src/main/java/ru/cheburmail/app/ui/chat/ImageMessageBubble.kt:97-130` — clickable AsyncImage triggers onImageClick |
| 23 | FullScreenImageViewer shows full-size image with close button | PASS | `app/src/main/java/ru/cheburmail/app/ui/chat/FullScreenImageViewer.kt` — AsyncImage + Close IconButton |
| 24 | MessageDao.updateMedia() query exists | PASS | `app/src/main/java/ru/cheburmail/app/db/dao/MessageDao.kt:70-82` |
| 25 | VoiceRecorder produces AAC/M4A output with waveform amplitude sampling | PASS | `app/src/main/java/ru/cheburmail/app/media/VoiceRecorder.kt` — MPEG_4 + AAC, sampling thread every 100ms, downsampleToWaveform() |
| 26 | VoicePlayer is singleton, supports play/pause/stop with state flow | PASS | `app/src/main/java/ru/cheburmail/app/media/VoicePlayer.kt` — single instance per ChatViewModel, `play()`/`pause()`/`stop()` + `StateFlow<PlaybackState>` |
| 27 | WaveformView renders bar chart from comma-separated format | PASS | `app/src/main/java/ru/cheburmail/app/ui/chat/WaveformView.kt` — Canvas bar chart, parses comma-separated values |
| 28 | VoiceMessageBubble has play/pause icon, waveform, duration | PASS | `app/src/main/java/ru/cheburmail/app/ui/chat/VoiceMessageBubble.kt` — PlayArrow/Pause icon, WaveformView, formatDuration() |
| 29 | FileMessageBubble shows filename, file size, save-to-Downloads button | PASS | `app/src/main/java/ru/cheburmail/app/ui/chat/FileMessageBubble.kt` — fileName, formatFileSize(), Download IconButton for incoming |
| 30 | FileSaver uses MediaStore on API 29+ | PASS | `app/src/main/java/ru/cheburmail/app/media/FileSaver.kt:25-28` — `Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q` → `saveViaMediaStore()` |
| 31 | RECORD_AUDIO permission is declared in manifest | PASS | `app/src/main/AndroidManifest.xml:8` |
| 32 | File size limit of 18MB is enforced before encryption | PASS | `app/src/main/java/ru/cheburmail/app/media/MediaEncryptor.kt:47-51` — throws CryptoException if `fileBytes.size > MAX_PAYLOAD_BYTES` |
| 33 | Mic/Stop button replaces Send button when text field is empty | PASS | `app/src/main/java/ru/cheburmail/app/ui/chat/ChatScreen.kt:310-341` — `when { text.isNotBlank() → Send; isRecordingVoice → Stop; else → Mic }` |
| 34 | SendProgressIndicator shows during media send | PASS | `app/src/main/java/ru/cheburmail/app/ui/chat/SendProgressIndicator.kt` + `ChatScreen.kt:179-182` |
| 35 | ChatViewModel has voice recording start/stop/cancel | PASS | `app/src/main/java/ru/cheburmail/app/ui/chat/ChatViewModel.kt:452-535` — `startVoiceRecording()`, `stopVoiceRecordingAndSend()`, `cancelVoiceRecording()` |
| 36 | ChatViewModel.onFilePicked() reads SAF URI, encrypts, queues | PASS | `app/src/main/java/ru/cheburmail/app/ui/chat/ChatViewModel.kt:385-450` |
| 37 | ChatViewModel.saveFileToDownloads() saves received files | PASS | `app/src/main/java/ru/cheburmail/app/ui/chat/ChatViewModel.kt:537-555` |

## Gaps

### Gap 1 — Must-have #20: SendWorker/ChatViewModel media payload format mismatch

**Severity: High** — media messages will fail to send.

`SendWorker.processEntry()` (line 88) detects media messages by `message.mediaType != MediaType.NONE` and then tries to decode `entry.encryptedPayload` as a combined binary frame: `[4-byte big-endian metaLen][metaBytes][payloadBytes]`.

However, `ChatViewModel.encryptAndQueueMedia()` inserts **two separate** `SendQueueEntity` rows:
1. `messageId = "${msgId}_meta"` with `encryptedPayload = metadataEnvelope.toBytes()`
2. `messageId = msgId` with `encryptedPayload = payloadEnvelope.toBytes()`

The `_meta` entry has a messageId that doesn't correspond to any `MessageEntity` in the `messages` table (FK constraint may reject insert, or `messageDao.getByIdOnce("${msgId}_meta")` returns null → SendWorker marks it FAILED).

The `msgId` entry is treated as a text message (its payload is a plain EncryptedEnvelope, `metaLen` read from the first 4 bytes will be garbage), so `smtpClient.send()` is called instead of `sendWithAttachment()`.

**Fix needed:** Either update `encryptAndQueueMedia()` in ChatViewModel to pack both envelopes into a single combined `[4-byte metaLen][meta][payload]` binary and insert a single queue entry, **or** update `SendWorker` to handle the two-entry pattern.

## Summary

35 of 37 must-haves pass. All UI components, crypto layer, transport primitives, and worker logic for receiving media are correctly implemented. The only gap is a mismatch between how `ChatViewModel` enqueues media (two separate rows) and how `SendWorker` reads the queue (expects a single combined binary frame). This means outgoing media messages will not actually be sent via SMTP; everything else works correctly.
