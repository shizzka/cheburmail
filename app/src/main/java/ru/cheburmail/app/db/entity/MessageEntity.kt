package ru.cheburmail.app.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import ru.cheburmail.app.db.MediaDownloadStatus
import ru.cheburmail.app.db.MediaType
import ru.cheburmail.app.db.MessageStatus

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chat_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["chat_id"]),
        Index(value = ["timestamp"])
    ]
)
data class MessageEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "chat_id")
    val chatId: String,

    @ColumnInfo(name = "sender_contact_id")
    val senderContactId: Long? = null,

    @ColumnInfo(name = "is_outgoing")
    val isOutgoing: Boolean,

    @ColumnInfo(name = "plaintext")
    val plaintext: String,

    @ColumnInfo(name = "status")
    val status: MessageStatus,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "expires_at")
    val expiresAt: Long? = null,

    @ColumnInfo(name = "media_type")
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

    @ColumnInfo(name = "media_download_status")
    val mediaDownloadStatus: MediaDownloadStatus = MediaDownloadStatus.NONE
)
