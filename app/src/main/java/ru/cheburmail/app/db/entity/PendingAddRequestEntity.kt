package ru.cheburmail.app.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Запрос на добавление нового участника в группу от не-админа.
 *
 * Создаётся у админа при получении MEMBER_ADD_REQUEST от verified-участника.
 * После approve/reject — удаляется. TTL очистка по createdAt.
 *
 * Composite PK (chatId, targetEmail) гарантирует дедуп: повторный запрос
 * на того же target в тот же чат заменяет предыдущий (используем REPLACE).
 */
@Entity(
    tableName = "pending_add_requests",
    primaryKeys = ["chat_id", "target_email"],
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chat_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["created_at"], name = "idx_pending_add_at")]
)
data class PendingAddRequestEntity(
    @ColumnInfo(name = "chat_id")
    val chatId: String,

    @ColumnInfo(name = "target_email")
    val targetEmail: String,

    @ColumnInfo(name = "requester_email")
    val requesterEmail: String,

    @ColumnInfo(name = "target_public_key", typeAffinity = ColumnInfo.BLOB)
    val targetPublicKey: ByteArray,

    @ColumnInfo(name = "target_display_name")
    val targetDisplayName: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PendingAddRequestEntity) return false
        return chatId == other.chatId &&
            targetEmail == other.targetEmail &&
            requesterEmail == other.requesterEmail &&
            targetPublicKey.contentEquals(other.targetPublicKey) &&
            targetDisplayName == other.targetDisplayName &&
            createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = chatId.hashCode()
        result = 31 * result + targetEmail.hashCode()
        result = 31 * result + requesterEmail.hashCode()
        result = 31 * result + targetPublicKey.contentHashCode()
        result = 31 * result + targetDisplayName.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }
}
