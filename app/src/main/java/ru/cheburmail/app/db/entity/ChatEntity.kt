package ru.cheburmail.app.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import ru.cheburmail.app.db.ChatType

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "type")
    val type: ChatType,

    @ColumnInfo(name = "title")
    val title: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
