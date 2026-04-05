package ru.cheburmail.app.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import ru.cheburmail.app.db.QueueStatus

@Entity(
    tableName = "send_queue",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["message_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["message_id"]),
        Index(value = ["status"])
    ]
)
data class SendQueueEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "message_id")
    val messageId: String,

    @ColumnInfo(name = "recipient_email")
    val recipientEmail: String,

    @ColumnInfo(name = "encrypted_payload")
    val encryptedPayload: ByteArray,

    /** Path to file with encrypted payload (for large media). If set, encryptedPayload is empty. */
    @ColumnInfo(name = "payload_file_path")
    val payloadFilePath: String? = null,

    @ColumnInfo(name = "status")
    val status: QueueStatus = QueueStatus.QUEUED,

    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,

    @ColumnInfo(name = "next_retry_at")
    val nextRetryAt: Long? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SendQueueEntity) return false
        return id == other.id &&
            messageId == other.messageId &&
            recipientEmail == other.recipientEmail &&
            encryptedPayload.contentEquals(other.encryptedPayload) &&
            status == other.status &&
            retryCount == other.retryCount &&
            nextRetryAt == other.nextRetryAt &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + messageId.hashCode()
        result = 31 * result + recipientEmail.hashCode()
        result = 31 * result + encryptedPayload.contentHashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + retryCount.hashCode()
        result = 31 * result + (nextRetryAt?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}
