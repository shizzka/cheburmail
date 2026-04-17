package ru.cheburmail.app.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import ru.cheburmail.app.db.ChatWithLastMessage
import ru.cheburmail.app.db.entity.ChatEntity
import ru.cheburmail.app.db.entity.ChatMemberEntity

@Dao
interface ChatDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(chat: ChatEntity)

    @Query("SELECT * FROM chats WHERE id = :chatId")
    suspend fun getById(chatId: String): ChatEntity?

    /**
     * Список чатов с превью последнего сообщения.
     * unreadCount считает входящие (RECEIVED) сообщения — заглушка до реализации
     * полноценного трекинга прочитанных.
     */
    @Query("""
        SELECT
            c.id AS chatId,
            c.type AS chatType,
            c.title AS title,
            m.plaintext AS lastMessageText,
            m.timestamp AS lastMessageTime,
            COALESCE(unread.cnt, 0) AS unreadCount
        FROM chats c
        LEFT JOIN messages m ON m.chat_id = c.id
            AND m.id NOT LIKE 'ctrl-%'
            AND m.timestamp = (SELECT MAX(m2.timestamp) FROM messages m2 WHERE m2.chat_id = c.id AND m2.id NOT LIKE 'ctrl-%')
        LEFT JOIN (
            SELECT chat_id, COUNT(*) AS cnt
            FROM messages
            WHERE status = 'RECEIVED' AND is_outgoing = 0 AND id NOT LIKE 'ctrl-%'
            GROUP BY chat_id
        ) unread ON unread.chat_id = c.id
        ORDER BY COALESCE(m.timestamp, c.created_at) DESC
    """)
    fun getAllWithLastMessage(): Flow<List<ChatWithLastMessage>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMember(member: ChatMemberEntity)

    @Query("SELECT * FROM chat_members WHERE chat_id = :chatId")
    suspend fun getMembersForChat(chatId: String): List<ChatMemberEntity>

    @Query("DELETE FROM chat_members WHERE chat_id = :chatId AND contact_id = :contactId")
    suspend fun deleteMember(chatId: String, contactId: Long)

    @Update
    suspend fun update(chat: ChatEntity)

    @Delete
    suspend fun delete(chat: ChatEntity)

    /**
     * Удалить чат по ID.
     */
    @Query("DELETE FROM chats WHERE id = :chatId")
    suspend fun deleteById(chatId: String)
}
