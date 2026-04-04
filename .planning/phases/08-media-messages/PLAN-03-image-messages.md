---
phase: 8
plan: 03
title: "Image messages"
wave: 2
depends_on: [01, 02]
files_modified:
  - app/build.gradle.kts
  - gradle/libs.versions.toml
  - app/src/main/AndroidManifest.xml
  - app/src/main/res/xml/file_paths.xml
  - app/src/main/java/ru/cheburmail/app/media/ImageCompressor.kt
  - app/src/main/java/ru/cheburmail/app/media/MediaFileManager.kt
  - app/src/main/java/ru/cheburmail/app/ui/chat/ImageMessageBubble.kt
  - app/src/main/java/ru/cheburmail/app/ui/chat/FullScreenImageViewer.kt
  - app/src/main/java/ru/cheburmail/app/ui/chat/ChatScreen.kt
  - app/src/main/java/ru/cheburmail/app/ui/chat/ChatViewModel.kt
  - app/src/main/java/ru/cheburmail/app/ui/chat/MessageBubble.kt
  - app/src/main/java/ru/cheburmail/app/transport/ReceiveWorker.kt
  - app/src/main/java/ru/cheburmail/app/transport/SendWorker.kt
  - app/src/main/java/ru/cheburmail/app/transport/TransportService.kt
  - app/src/main/java/ru/cheburmail/app/ui/navigation/AppNavigation.kt
autonomous: true
---

# Plan 03: Image Messages

## Objective
Enable sending and receiving encrypted image messages. Users can pick from gallery or take a photo with camera. Images are compressed (max 1920px, JPEG q85), encrypted as two-part media email (metadata + payload), sent via SMTP, and displayed at the recipient as a thumbnail bubble with tap-to-view full-size. Adds Coil 3.x for image loading, FileProvider for camera captures, and new UI composables.

## Tasks

<task id="1" title="Add Coil dependency to version catalog and build.gradle" file="gradle/libs.versions.toml">
1. In `gradle/libs.versions.toml`, add to `[versions]`:
```toml
coil = "3.1.0"
```

2. Add to `[libraries]`:
```toml
coil-compose = { group = "io.coil-kt.coil3", name = "coil-compose", version.ref = "coil" }
```

Then in `app/build.gradle.kts`, add to `dependencies`:
```kotlin
// Image loading
implementation(libs.coil.compose)
```
</task>

<task id="2" title="Set up FileProvider for camera captures" file="app/src/main/AndroidManifest.xml">
1. Create `app/src/main/res/xml/file_paths.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="camera_photos" path="camera/" />
    <cache-path name="media_cache" path="media/" />
</paths>
```

2. Add the FileProvider inside the `<application>` tag in `AndroidManifest.xml` (before the closing `</application>`):
```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```
</task>

<task id="3" title="Create ImageCompressor utility" file="app/src/main/java/ru/cheburmail/app/media/ImageCompressor.kt">
Create an image compression utility that resizes images and generates thumbnails:

```kotlin
package ru.cheburmail.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Compresses images for sending and generates thumbnails for display.
 *
 * Full image: max 1920px on longest side, JPEG quality 85.
 * Thumbnail: max 256px on longest side, JPEG quality 60.
 */
class ImageCompressor(private val context: Context) {

    data class CompressedImage(
        val fullBytes: ByteArray,
        val thumbnailBytes: ByteArray,
        val width: Int,
        val height: Int,
        val thumbnailWidth: Int,
        val thumbnailHeight: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CompressedImage) return false
            return fullBytes.contentEquals(other.fullBytes) &&
                thumbnailBytes.contentEquals(other.thumbnailBytes) &&
                width == other.width && height == other.height
        }
        override fun hashCode(): Int {
            var result = fullBytes.contentHashCode()
            result = 31 * result + thumbnailBytes.contentHashCode()
            return result
        }
    }

    companion object {
        const val MAX_FULL_SIZE_PX = 1920
        const val MAX_THUMBNAIL_SIZE_PX = 256
        const val FULL_QUALITY = 85
        const val THUMBNAIL_QUALITY = 60
    }

    /**
     * Compress an image from a content URI.
     *
     * @param uri content:// or file:// URI to the source image
     * @return CompressedImage with full and thumbnail JPEG bytes + dimensions
     * @throws java.io.IOException on read failure
     */
    fun compress(uri: Uri): CompressedImage {
        // Decode with inSampleSize for memory efficiency
        val (bitmap, originalWidth, originalHeight) = decodeBitmap(uri)

        // Apply EXIF rotation
        val rotatedBitmap = applyExifRotation(uri, bitmap)

        // Resize for full image
        val fullBitmap = resizeBitmap(rotatedBitmap, MAX_FULL_SIZE_PX)
        val fullBytes = compressToJpeg(fullBitmap, FULL_QUALITY)

        // Resize for thumbnail
        val thumbBitmap = resizeBitmap(rotatedBitmap, MAX_THUMBNAIL_SIZE_PX)
        val thumbBytes = compressToJpeg(thumbBitmap, THUMBNAIL_QUALITY)

        val result = CompressedImage(
            fullBytes = fullBytes,
            thumbnailBytes = thumbBytes,
            width = fullBitmap.width,
            height = fullBitmap.height,
            thumbnailWidth = thumbBitmap.width,
            thumbnailHeight = thumbBitmap.height
        )

        // Recycle bitmaps
        if (rotatedBitmap !== bitmap) rotatedBitmap.recycle()
        if (fullBitmap !== rotatedBitmap) fullBitmap.recycle()
        if (thumbBitmap !== rotatedBitmap) thumbBitmap.recycle()
        bitmap.recycle()

        return result
    }

    private data class DecodedBitmap(val bitmap: Bitmap, val width: Int, val height: Int)

    private fun decodeBitmap(uri: Uri): DecodedBitmap {
        // First pass: get dimensions only
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }

        // Calculate inSampleSize
        val longestSide = maxOf(options.outWidth, options.outHeight)
        var inSampleSize = 1
        while (longestSide / inSampleSize > MAX_FULL_SIZE_PX * 2) {
            inSampleSize *= 2
        }

        // Second pass: decode with inSampleSize
        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: throw java.io.IOException("Failed to decode image from $uri")

        return DecodedBitmap(bitmap, options.outWidth, options.outHeight)
    }

    private fun applyExifRotation(uri: Uri, bitmap: Bitmap): Bitmap {
        val rotation = try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val exif = ExifInterface(inputStream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) {
            0f
        }

        if (rotation == 0f) return bitmap

        val matrix = Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun resizeBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        val longestSide = maxOf(bitmap.width, bitmap.height)
        if (longestSide <= maxSize) return bitmap

        val scale = maxSize.toFloat() / longestSide
        val newWidth = (bitmap.width * scale).toInt()
        val newHeight = (bitmap.height * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun compressToJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        return bos.toByteArray()
    }
}
```

Note: `ExifInterface` comes from `androidx.exifinterface:exifinterface` which is transitively available through `androidx.activity:activity`. If compilation fails, add explicit dependency `implementation("androidx.exifinterface:exifinterface:1.4.1")` to build.gradle.kts.
</task>

<task id="4" title="Create MediaFileManager for local file storage" file="app/src/main/java/ru/cheburmail/app/media/MediaFileManager.kt">
Create a utility to save/load media files to/from app-internal cache:

```kotlin
package ru.cheburmail.app.media

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/**
 * Manages local media file storage in app cache directories.
 *
 * Directory structure:
 *   cache/media/images/    — full-size images
 *   cache/media/thumbnails/ — image thumbnails
 *   cache/media/voice/     — voice recordings
 *   cache/media/files/     — arbitrary files
 *   cache/camera/          — temporary camera captures
 */
class MediaFileManager(private val context: Context) {

    private val mediaDir get() = File(context.cacheDir, "media").also { it.mkdirs() }
    private val imagesDir get() = File(mediaDir, "images").also { it.mkdirs() }
    private val thumbnailsDir get() = File(mediaDir, "thumbnails").also { it.mkdirs() }
    private val voiceDir get() = File(mediaDir, "voice").also { it.mkdirs() }
    private val filesDir get() = File(mediaDir, "files").also { it.mkdirs() }
    private val cameraDir get() = File(context.cacheDir, "camera").also { it.mkdirs() }

    /**
     * Save image bytes and return the local file URI string.
     */
    fun saveImage(messageId: String, bytes: ByteArray): String {
        val file = File(imagesDir, "$messageId.jpg")
        file.writeBytes(bytes)
        return Uri.fromFile(file).toString()
    }

    /**
     * Save thumbnail bytes and return the local file URI string.
     */
    fun saveThumbnail(messageId: String, bytes: ByteArray): String {
        val file = File(thumbnailsDir, "${messageId}_thumb.jpg")
        file.writeBytes(bytes)
        return Uri.fromFile(file).toString()
    }

    /**
     * Save voice recording bytes and return the local file URI string.
     */
    fun saveVoice(messageId: String, bytes: ByteArray): String {
        val file = File(voiceDir, "$messageId.m4a")
        file.writeBytes(bytes)
        return Uri.fromFile(file).toString()
    }

    /**
     * Save an arbitrary file and return the local file URI string.
     */
    fun saveFile(messageId: String, fileName: String, bytes: ByteArray): String {
        // Use messageId prefix to avoid name collisions
        val safeFileName = "${messageId}_${sanitizeFileName(fileName)}"
        val file = File(filesDir, safeFileName)
        file.writeBytes(bytes)
        return Uri.fromFile(file).toString()
    }

    /**
     * Read file bytes from a local URI string.
     */
    fun readBytes(uriString: String): ByteArray {
        val uri = Uri.parse(uriString)
        return if (uri.scheme == "file") {
            File(uri.path!!).readBytes()
        } else {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw java.io.IOException("Cannot read from $uriString")
        }
    }

    /**
     * Create a temporary file URI for camera capture via FileProvider.
     * Returns a pair of (content URI for camera intent, File for later reading).
     */
    fun createCameraUri(): Pair<Uri, File> {
        val file = File(cameraDir, "photo_${UUID.randomUUID()}.jpg")
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Pair(contentUri, file)
    }

    /**
     * Read bytes from a content:// URI (from gallery picker or SAF).
     */
    fun readContentUri(uri: Uri): ByteArray {
        return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw java.io.IOException("Cannot read from $uri")
    }

    /**
     * Get the file name from a content URI.
     */
    fun getFileName(uri: Uri): String {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        return cursor.getString(nameIndex)
                    }
                }
            }
        }
        return uri.lastPathSegment ?: "unknown"
    }

    /**
     * Get the file size from a content URI.
     */
    fun getFileSize(uri: Uri): Long {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIndex >= 0) {
                        return cursor.getLong(sizeIndex)
                    }
                }
            }
        }
        return 0L
    }

    /**
     * Get the MIME type of a content URI.
     */
    fun getMimeType(uri: Uri): String {
        return context.contentResolver.getType(uri) ?: "application/octet-stream"
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(100)
    }
}
```
</task>

<task id="5" title="Create ImageMessageBubble composable" file="app/src/main/java/ru/cheburmail/app/ui/chat/ImageMessageBubble.kt" depends_on="1">
Create a composable for rendering image message bubbles with Coil:

```kotlin
package ru.cheburmail.app.ui.chat

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import ru.cheburmail.app.db.MediaDownloadStatus
import ru.cheburmail.app.db.MessageStatus
import ru.cheburmail.app.db.entity.MessageEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bubble for image messages.
 * Shows thumbnail/full image with rounded corners.
 * Tap opens full-screen viewer.
 */
@Composable
fun ImageMessageBubble(
    message: MessageEntity,
    onImageClick: (messageId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isOutgoing = message.isOutgoing
    val alignment = if (isOutgoing) Arrangement.End else Arrangement.Start
    val bubbleShape = if (isOutgoing) {
        RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    }
    val bubbleColor = if (isOutgoing) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isOutgoing) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    // Use thumbnail for incoming if available, else local_media_uri
    val imageUri = message.thumbnailUri ?: message.localMediaUri

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = alignment
    ) {
        Surface(
            shape = bubbleShape,
            color = bubbleColor,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(4.dp)) {
                when {
                    message.mediaDownloadStatus == MediaDownloadStatus.DOWNLOADING ||
                    message.mediaDownloadStatus == MediaDownloadStatus.PENDING -> {
                        Box(
                            modifier = Modifier
                                .size(200.dp)
                                .clip(RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        }
                    }
                    imageUri != null -> {
                        AsyncImage(
                            model = Uri.parse(imageUri),
                            contentDescription = "Image",
                            modifier = Modifier
                                .widthIn(max = 272.dp)
                                .heightIn(max = 300.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onImageClick(message.id) },
                            contentScale = ContentScale.Fit
                        )
                    }
                    else -> {
                        Text(
                            text = "[Image]",
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                // Caption if present
                if (message.plaintext.isNotBlank()) {
                    Text(
                        text = message.plaintext,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // Timestamp + status
                Row(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.6f)
                    )
                    if (isOutgoing) {
                        Spacer(modifier = Modifier.width(4.dp))
                        MessageStatusIcon(status = message.status)
                    }
                }
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private fun formatTime(timestamp: Long): String = timeFormat.format(Date(timestamp))
```
</task>

<task id="6" title="Create FullScreenImageViewer" file="app/src/main/java/ru/cheburmail/app/ui/chat/FullScreenImageViewer.kt" depends_on="1">
Create a full-screen image viewer composable that shows the full-size image with zoom support (basic pinch-to-zoom can be added later; for now, just a full-screen display with a close button):

```kotlin
package ru.cheburmail.app.ui.chat

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * Full-screen image viewer.
 * Shows the full-size image (from local_media_uri) with a close button.
 */
@Composable
fun FullScreenImageViewer(
    imageUri: String,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AsyncImage(
            model = Uri.parse(imageUri),
            contentDescription = "Full-size image",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )

        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close",
                tint = Color.White
            )
        }
    }
}
```
</task>

<task id="7" title="Update MessageBubble to dispatch by media type" file="app/src/main/java/ru/cheburmail/app/ui/chat/MessageBubble.kt" depends_on="5">
Modify `MessageBubble` to accept an `onImageClick` callback and dispatch to `ImageMessageBubble` when the message has `mediaType == MediaType.IMAGE`:

1. Change the signature to add the callback:
```kotlin
@Composable
fun MessageBubble(
    message: MessageEntity,
    onImageClick: (messageId: String) -> Unit = {},
    modifier: Modifier = Modifier
)
```

2. At the beginning of the function body, add a dispatch check:
```kotlin
if (message.mediaType == MediaType.IMAGE) {
    ImageMessageBubble(
        message = message,
        onImageClick = onImageClick,
        modifier = modifier
    )
    return
}
```

3. Add the import: `import ru.cheburmail.app.db.MediaType`
</task>

<task id="8" title="Add image sending flow to ChatViewModel" file="app/src/main/java/ru/cheburmail/app/ui/chat/ChatViewModel.kt" depends_on="3,4">
Add image picking/sending capabilities to `ChatViewModel`:

1. Add new imports:
```kotlin
import android.net.Uri
import ru.cheburmail.app.db.MediaDownloadStatus
import ru.cheburmail.app.db.MediaType
import ru.cheburmail.app.media.ImageCompressor
import ru.cheburmail.app.media.MediaEncryptor
import ru.cheburmail.app.media.MediaFileManager
import ru.cheburmail.app.media.MediaMetadata
import ru.cheburmail.app.transport.EmailMessage
```

2. Add lazy-initialized helpers as class properties:
```kotlin
private val imageCompressor by lazy { ImageCompressor(appContext) }
private val mediaFileManager by lazy { MediaFileManager(appContext) }
```

3. Add a StateFlow for tracking the camera URI:
```kotlin
private val _cameraUri = MutableStateFlow<Uri?>(null)
val cameraUri: StateFlow<Uri?> = _cameraUri.asStateFlow()
```

4. Add method to create a camera URI:
```kotlin
fun createCameraUri(): Uri {
    val (uri, _) = mediaFileManager.createCameraUri()
    _cameraUri.value = uri
    return uri
}
```

5. Add method `onImagePicked(uri: Uri)`:
```kotlin
/**
 * Handle image picked from gallery or captured from camera.
 * Compresses, encrypts, saves locally, queues for send.
 */
fun onImagePicked(uri: Uri) {
    viewModelScope.launch {
        try {
            val email = recipientEmail ?: run {
                Log.e(TAG, "Recipient email not resolved")
                return@launch
            }

            val msgId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()

            // Create placeholder message immediately for UI feedback
            val placeholderMsg = MessageEntity(
                id = msgId,
                chatId = chatId,
                isOutgoing = true,
                plaintext = "",
                status = MessageStatus.SENDING,
                timestamp = now,
                mediaType = MediaType.IMAGE,
                mediaDownloadStatus = MediaDownloadStatus.PENDING
            )
            messageDao.insert(placeholderMsg)

            // Ensure chat exists
            val existingChat = chatDao.getById(chatId)
            if (existingChat == null) {
                chatDao.insert(ChatEntity(
                    id = chatId, type = ChatType.DIRECT,
                    title = _chatTitle.value, createdAt = now, updatedAt = now
                ))
            }

            withContext(Dispatchers.IO) {
                // 1. Compress
                val compressed = imageCompressor.compress(uri)

                // 2. Save locally
                val localUri = mediaFileManager.saveImage(msgId, compressed.fullBytes)
                val thumbUri = mediaFileManager.saveThumbnail(msgId, compressed.thumbnailBytes)

                // 3. Update message with local file info
                messageDao.updateMedia(
                    id = msgId,
                    localMediaUri = localUri,
                    thumbnailUri = thumbUri,
                    fileName = "image_$msgId.jpg",
                    fileSize = compressed.fullBytes.size.toLong(),
                    mimeType = "image/jpeg",
                    mediaDownloadStatus = MediaDownloadStatus.COMPLETED
                )

                // 4. Encrypt
                val contact = contactDao.getByEmail(email) ?: run {
                    Log.e(TAG, "Contact not found: $email")
                    messageDao.updateStatus(msgId, MessageStatus.FAILED)
                    return@withContext
                }
                val keyPair = keyStorage.getOrCreateKeyPair()
                val ls = CryptoProvider.lazySodium
                val msgEncryptor = MessageEncryptor(ls, NonceGenerator(ls))
                val mediaEncryptor = MediaEncryptor(msgEncryptor)

                val metadata = MediaMetadata(
                    type = MediaMetadata.TYPE_IMAGE,
                    fileName = "image_$msgId.jpg",
                    fileSize = compressed.fullBytes.size.toLong(),
                    mimeType = "image/jpeg",
                    width = compressed.width,
                    height = compressed.height
                )

                val encrypted = mediaEncryptor.encrypt(
                    metadata, compressed.fullBytes,
                    contact.publicKey, keyPair.getPrivateKey()
                )

                // 5. Queue for send — store both envelopes concatenated
                // Format: [4-byte metadata length][metadata envelope bytes][payload envelope bytes]
                val metaBytes = encrypted.metadataEnvelope.toBytes()
                val payloadBytes = encrypted.payloadEnvelope.toBytes()
                val combined = ByteArray(4 + metaBytes.size + payloadBytes.size)
                // Big-endian int for metadata length
                combined[0] = (metaBytes.size shr 24).toByte()
                combined[1] = (metaBytes.size shr 16).toByte()
                combined[2] = (metaBytes.size shr 8).toByte()
                combined[3] = metaBytes.size.toByte()
                metaBytes.copyInto(combined, 4)
                payloadBytes.copyInto(combined, 4 + metaBytes.size)

                sendQueueDao.insert(SendQueueEntity(
                    messageId = msgId,
                    recipientEmail = email,
                    encryptedPayload = combined,
                    status = QueueStatus.QUEUED,
                    createdAt = now,
                    updatedAt = now
                ))

                OutboxDrainWorker.enqueue(appContext)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending image: ${e.message}", e)
        }
    }
}
```

6. Add a helper to `MessageDao` interface — a new query method `updateMedia`:
```kotlin
@Query("""
    UPDATE messages SET 
        local_media_uri = :localMediaUri,
        thumbnail_uri = :thumbnailUri,
        file_name = :fileName,
        file_size = :fileSize,
        mime_type = :mimeType,
        media_download_status = :mediaDownloadStatus
    WHERE id = :id
""")
suspend fun updateMedia(
    id: String,
    localMediaUri: String?,
    thumbnailUri: String?,
    fileName: String?,
    fileSize: Long?,
    mimeType: String?,
    mediaDownloadStatus: MediaDownloadStatus
)
```

This query must be added to `app/src/main/java/ru/cheburmail/app/db/dao/MessageDao.kt`.
</task>

<task id="9" title="Update SendWorker to handle media messages" file="app/src/main/java/ru/cheburmail/app/transport/SendWorker.kt" depends_on="8">
Modify `SendWorker.processEntry()` to detect media messages and use `sendWithAttachment()`:

In `processEntry()`, after retrieving the message entity, check if it has a media type:

```kotlin
val isMediaMessage = message.mediaType != null &&
    message.mediaType != MediaType.NONE

if (isMediaMessage) {
    // Parse combined payload: [4-byte meta length][meta envelope][payload envelope]
    val combined = entry.encryptedPayload
    val metaLen = ((combined[0].toInt() and 0xFF) shl 24) or
        ((combined[1].toInt() and 0xFF) shl 16) or
        ((combined[2].toInt() and 0xFF) shl 8) or
        (combined[3].toInt() and 0xFF)
    val metaBytes = combined.copyOfRange(4, 4 + metaLen)
    val payloadBytes = combined.copyOfRange(4 + metaLen, combined.size)

    val metadataEnvelope = EncryptedEnvelope.fromBytes(metaBytes)
    val payloadEnvelope = EncryptedEnvelope.fromBytes(payloadBytes)

    val mediaEmail = emailFormatter.formatMedia(
        metadataEnvelope = metadataEnvelope,
        payloadEnvelope = payloadEnvelope,
        chatId = message.chatId,
        msgUuid = message.id,
        fromEmail = sendConfig.email,
        toEmail = entry.recipientEmail
    )

    smtpClient.sendWithAttachment(sendConfig, mediaEmail)
} else {
    // Existing text-only send logic
    val envelope = EncryptedEnvelope.fromBytes(entry.encryptedPayload)
    val emailMessage = emailFormatter.format(
        envelope = envelope,
        chatId = message.chatId,
        msgUuid = message.id,
        fromEmail = sendConfig.email,
        toEmail = entry.recipientEmail
    )
    smtpClient.send(sendConfig, emailMessage)
}
```

Add imports: `import ru.cheburmail.app.db.MediaType`.
</task>

<task id="10" title="Update ReceiveWorker to handle incoming media messages" file="app/src/main/java/ru/cheburmail/app/transport/ReceiveWorker.kt">
Modify `ReceiveWorker` to handle media messages from `TransportService.receiveAll()`:

1. Add constructor parameters:
```kotlin
private val mediaDecryptor: MediaDecryptor? = null,
private val mediaFileManager: MediaFileManager? = null,
```

2. In `pollAndProcess()`, after `received = transportService.receiveAll(config)`, also process media messages from `received.mediaMessages` (see task 11 for TransportService changes). For now, integrate into the main loop:

After the dedup check and contact lookup, add media detection:

```kotlin
// Check if this is a media message (ParsedMediaMessage)
// Media messages come via a separate list from TransportService
```

Actually, the cleaner approach: Add a separate loop after the existing parsed messages loop to handle `received.mediaMessages`:

```kotlin
// Process media messages
for (mediaMsg in received.mediaMessages) {
    try {
        if (messageDao.existsById(mediaMsg.msgUuid)) {
            Log.d(TAG, "Duplicate media message ${mediaMsg.msgUuid}, skipping")
            continue
        }

        val contact = contactDao.getByEmail(mediaMsg.fromEmail)
        if (contact == null) {
            Log.w(TAG, "Unknown sender ${mediaMsg.fromEmail}, skipping media ${mediaMsg.msgUuid}")
            continue
        }

        if (mediaDecryptor == null || mediaFileManager == null) {
            Log.w(TAG, "MediaDecryptor/MediaFileManager not configured, skipping media")
            continue
        }

        val decrypted = mediaDecryptor.decrypt(
            mediaMsg.metadataEnvelope,
            mediaMsg.payloadEnvelope,
            contact.publicKey,
            recipientPrivateKey
        )

        val now = System.currentTimeMillis()
        val mediaType = when (decrypted.metadata.type) {
            MediaMetadata.TYPE_IMAGE -> MediaType.IMAGE
            MediaMetadata.TYPE_FILE -> MediaType.FILE
            MediaMetadata.TYPE_VOICE -> MediaType.VOICE
            else -> MediaType.FILE
        }

        // Save media to local storage
        val localUri = when (mediaType) {
            MediaType.IMAGE -> mediaFileManager.saveImage(mediaMsg.msgUuid, decrypted.payload)
            MediaType.VOICE -> mediaFileManager.saveVoice(mediaMsg.msgUuid, decrypted.payload)
            else -> mediaFileManager.saveFile(mediaMsg.msgUuid, decrypted.metadata.fileName, decrypted.payload)
        }

        // Generate thumbnail for images
        val thumbUri = if (mediaType == MediaType.IMAGE) {
            // For received images, save the full image and use it as thumbnail too
            // (ImageCompressor can make a thumb, but we already have the bytes)
            mediaFileManager.saveThumbnail(mediaMsg.msgUuid, decrypted.payload)
        } else null

        // Ensure chat exists
        if (chatDao != null) {
            val existingChat = chatDao.getById(mediaMsg.chatId)
            if (existingChat == null) {
                chatDao.insert(ChatEntity(
                    id = mediaMsg.chatId, type = ChatType.DIRECT,
                    title = contact.displayName, createdAt = now, updatedAt = now
                ))
            }
        }

        val entity = MessageEntity(
            id = mediaMsg.msgUuid,
            chatId = mediaMsg.chatId,
            senderContactId = contact.id,
            isOutgoing = false,
            plaintext = decrypted.metadata.caption ?: "",
            status = MessageStatus.RECEIVED,
            timestamp = now,
            mediaType = mediaType,
            localMediaUri = localUri,
            fileName = decrypted.metadata.fileName,
            fileSize = decrypted.metadata.fileSize,
            mimeType = decrypted.metadata.mimeType,
            thumbnailUri = thumbUri,
            voiceDurationMs = decrypted.metadata.durationMs,
            waveformData = decrypted.metadata.waveform,
            mediaDownloadStatus = MediaDownloadStatus.COMPLETED
        )
        messageDao.insert(entity)
        savedCount++

        notificationHelper?.showMessageNotification(
            senderName = contact.displayName,
            preview = when (mediaType) {
                MediaType.IMAGE -> "Sent an image"
                MediaType.VOICE -> "Voice message"
                else -> "File: ${decrypted.metadata.fileName}"
            },
            chatId = mediaMsg.chatId
        )
    } catch (e: Exception) {
        Log.e(TAG, "Error processing media ${mediaMsg.msgUuid}: ${e.message}")
    }
}
```

Add imports: `import ru.cheburmail.app.db.MediaDownloadStatus`, `import ru.cheburmail.app.db.MediaType`, `import ru.cheburmail.app.media.MediaDecryptor`, `import ru.cheburmail.app.media.MediaFileManager`, `import ru.cheburmail.app.media.MediaMetadata`.
</task>

<task id="11" title="Update TransportService to separate media messages" file="app/src/main/java/ru/cheburmail/app/transport/TransportService.kt" depends_on="10">
Modify `TransportService.ReceivedMessages` and `receiveAll()` to include parsed media messages:

1. Update the `ReceivedMessages` data class:
```kotlin
data class ReceivedMessages(
    val messages: List<EmailParser.ParsedMessage>,
    val keyExchangeEmails: List<EmailMessage>,
    val mediaMessages: List<EmailParser.ParsedMediaMessage> = emptyList()
)
```

2. In `receiveAll()`, separate media messages and parse them:
```kotlin
fun receiveAll(config: EmailConfig): ReceivedMessages {
    val emails = imapClient.fetchMessages(config)

    val keyExchangeEmails = emails.filter {
        KeyExchangeManager.isKeyExchangeSubject(it.subject)
    }

    val regularEmails = emails.filter {
        emailParser.isCheburMail(it) &&
        !KeyExchangeManager.isKeyExchangeSubject(it.subject) &&
        !emailParser.isCheburMailMedia(it)
    }

    val mediaEmails = emails.filter {
        emailParser.isCheburMailMedia(it)
    }

    val parsedMessages = regularEmails.mapNotNull { email ->
        try {
            emailParser.parse(email)
        } catch (e: TransportException.FormatException) {
            android.util.Log.w("TransportService", "Skipping malformed message: ${e.message}")
            null
        }
    }

    val parsedMediaMessages = mediaEmails.mapNotNull { email ->
        try {
            emailParser.parseMedia(email)
        } catch (e: TransportException.FormatException) {
            android.util.Log.w("TransportService", "Skipping malformed media: ${e.message}")
            null
        }
    }

    return ReceivedMessages(parsedMessages, keyExchangeEmails, parsedMediaMessages)
}
```
</task>

<task id="12" title="Add attach button and image pickers to ChatScreen" file="app/src/main/java/ru/cheburmail/app/ui/chat/ChatScreen.kt" depends_on="5,6,7,8">
Modify the `MessageInput` composable and `ChatScreen` to add an attach button with gallery picker and camera launcher:

1. Add imports:
```kotlin
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.foundation.layout.Arrangement as RowArrangement
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ru.cheburmail.app.db.MediaType
```

2. In `ChatScreen`, before `MessageInput`, add the image picker and camera launchers:
```kotlin
val galleryLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.PickVisualMedia()
) { uri: Uri? ->
    uri?.let { viewModel.onImagePicked(it) }
}

val cameraUri = remember { mutableStateOf<Uri?>(null) }
val cameraLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.TakePicture()
) { success: Boolean ->
    if (success) {
        cameraUri.value?.let { viewModel.onImagePicked(it) }
    }
}
```

3. Update the `MessageBubble` call in LazyColumn to pass `onImageClick`:
```kotlin
MessageBubble(
    message = message,
    onImageClick = { messageId ->
        // Navigate to full screen viewer
        // For now, find the message and show FullScreenImageViewer
    },
    modifier = Modifier.padding(vertical = 4.dp)
)
```

4. Update `MessageInput` signature to add attach callbacks:
```kotlin
@Composable
private fun MessageInput(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit
)
```

5. Add an attach button (paper clip icon) to the left of the text field that shows a dropdown with "Gallery" and "Camera" options:
```kotlin
var showAttachMenu by remember { mutableStateOf(false) }

Box {
    IconButton(onClick = { showAttachMenu = true }) {
        Icon(
            imageVector = Icons.Filled.AttachFile,
            contentDescription = "Attach"
        )
    }
    DropdownMenu(
        expanded = showAttachMenu,
        onDismissRequest = { showAttachMenu = false }
    ) {
        DropdownMenuItem(
            text = { Text("Gallery") },
            onClick = {
                showAttachMenu = false
                onPickImage()
            },
            leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null) }
        )
        DropdownMenuItem(
            text = { Text("Camera") },
            onClick = {
                showAttachMenu = false
                onTakePhoto()
            },
            leadingIcon = { Icon(Icons.Filled.CameraAlt, contentDescription = null) }
        )
    }
}
```

6. Wire the callbacks:
```kotlin
MessageInput(
    text = inputText,
    onTextChange = viewModel::updateInputText,
    onSend = viewModel::sendMessage,
    onPickImage = {
        galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    },
    onTakePhoto = {
        val uri = viewModel.createCameraUri()
        cameraUri.value = uri
        cameraLauncher.launch(uri)
    }
)
```

7. Add a full-screen image viewer state and overlay. Use a `remember { mutableStateOf<String?>(null) }` for the selected image URI, and show `FullScreenImageViewer` when non-null as an overlay in the `Scaffold` content.
</task>

<task id="13" title="Update ReceiveWorker instantiation in ChatViewModel.refresh()" file="app/src/main/java/ru/cheburmail/app/ui/chat/ChatViewModel.kt" depends_on="10">
In the `refresh()` method of `ChatViewModel`, update the `ReceiveWorker` constructor call to pass `mediaDecryptor` and `mediaFileManager`:

```kotlin
val mediaDecryptor = ru.cheburmail.app.media.MediaDecryptor(decryptor)
val mediaFileManager = ru.cheburmail.app.media.MediaFileManager(appContext)

val receiveWorker = ReceiveWorker(
    transportService = transportService,
    decryptor = decryptor,
    retryStrategy = RetryStrategy(),
    messageDao = db.messageDao(),
    contactDao = db.contactDao(),
    chatDao = db.chatDao(),
    notificationHelper = NotificationHelper(appContext),
    recipientPrivateKey = keyPair.getPrivateKey(),
    keyExchangeManager = keyExchangeManager,
    emailConfig = config,
    mediaDecryptor = mediaDecryptor,
    mediaFileManager = mediaFileManager
)
```

Also update anywhere else that constructs ReceiveWorker (PeriodicSyncWorker, ImapIdleService) to pass these new parameters.
</task>

## Verification
- [ ] Project compiles with `./gradlew assembleDebug --no-daemon`
- [ ] Gallery picker launches from the attach button and returns a URI
- [ ] Camera launcher creates a photo and returns to the app
- [ ] Picked image is compressed (check that output bytes < original for large photos)
- [ ] Image message appears immediately in chat with a loading indicator
- [ ] After encryption + queue, the message bubble shows the thumbnail
- [ ] SendWorker sends the media email with 2 MIME parts (verify in email client or logs)
- [ ] ReceiveWorker decrypts incoming media email and saves image locally
- [ ] Received image renders in the chat bubble
- [ ] Tapping an image opens FullScreenImageViewer
- [ ] Text-only messages still work correctly

## must_haves
- Coil 3.x is added as dependency and used for AsyncImage rendering
- FileProvider is configured for camera captures
- ImageCompressor resizes to max 1920px and generates 256px thumbnails
- MediaFileManager saves/loads files from app cache directories
- Attach button in MessageInput with gallery + camera options
- ChatViewModel.onImagePicked() compresses, encrypts, saves locally, queues for send
- SendWorker detects media messages and uses sendWithAttachment()
- ReceiveWorker decrypts media messages and saves to local storage with correct MediaType
- ImageMessageBubble displays thumbnail with tap-to-view full-size
- FullScreenImageViewer shows full-size image with close button
- MessageDao.updateMedia() query exists for updating media fields
