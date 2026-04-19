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
    val updatedAt: Long,

    @ColumnInfo(name = "disappear_timer_ms")
    val disappearTimerMs: Long? = null,

    /**
     * Email создателя группы (admin). NULL для старых групп до MIGRATION_7_8 и для
     * direct-чатов. Только этот email может одобрять MEMBER_ADD_REQUEST и напрямую
     * добавлять/удалять участников.
     */
    @ColumnInfo(name = "created_by")
    val createdBy: String? = null
)
