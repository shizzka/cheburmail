package ru.cheburmail.app.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "chat_members",
    primaryKeys = ["chat_id", "contact_id"],
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chat_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["id"],
            childColumns = ["contact_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["contact_id"])
    ]
)
data class ChatMemberEntity(
    @ColumnInfo(name = "chat_id")
    val chatId: String,

    @ColumnInfo(name = "contact_id")
    val contactId: Long,

    @ColumnInfo(name = "joined_at")
    val joinedAt: Long
)
