package ru.cheburmail.app.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Хранит ID удалённых сообщений, чтобы ReceiveWorker не вставлял их повторно из IMAP.
 */
@Entity(tableName = "deleted_messages")
data class DeletedMessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "message_id")
    val messageId: String,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long = System.currentTimeMillis()
)
