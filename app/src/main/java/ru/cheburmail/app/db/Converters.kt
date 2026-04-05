package ru.cheburmail.app.db

import android.util.Base64
import androidx.room.TypeConverter

/**
 * TypeConverter'ы для Room: ByteArray ↔ Base64 String, enum ↔ String.
 */
class Converters {

    // --- ByteArray ↔ Base64 String ---

    @TypeConverter
    fun fromByteArray(value: ByteArray?): String? =
        value?.let { Base64.encodeToString(it, Base64.NO_WRAP) }

    @TypeConverter
    fun toByteArray(value: String?): ByteArray? =
        value?.let { Base64.decode(it, Base64.NO_WRAP) }

    // --- TrustStatus ---

    @TypeConverter
    fun fromTrustStatus(value: TrustStatus): String = value.name

    @TypeConverter
    fun toTrustStatus(value: String): TrustStatus = TrustStatus.valueOf(value)

    // --- ChatType ---

    @TypeConverter
    fun fromChatType(value: ChatType): String = value.name

    @TypeConverter
    fun toChatType(value: String): ChatType = ChatType.valueOf(value)

    // --- MessageStatus ---

    @TypeConverter
    fun fromMessageStatus(value: MessageStatus): String = value.name

    @TypeConverter
    fun toMessageStatus(value: String): MessageStatus = MessageStatus.valueOf(value)

    // --- QueueStatus ---

    @TypeConverter
    fun fromQueueStatus(value: QueueStatus): String = value.name

    @TypeConverter
    fun toQueueStatus(value: String): QueueStatus = QueueStatus.valueOf(value)

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
}
