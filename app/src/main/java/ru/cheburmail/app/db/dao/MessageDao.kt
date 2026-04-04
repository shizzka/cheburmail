package ru.cheburmail.app.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ru.cheburmail.app.db.MessageStatus
import ru.cheburmail.app.db.entity.MessageEntity

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: String): MessageEntity?

    /**
     * Сообщения чата, упорядоченные по времени (ASC) для отображения в UI.
     */
    @Query("SELECT * FROM messages WHERE chat_id = :chatId ORDER BY timestamp ASC")
    fun getForChat(chatId: String): Flow<List<MessageEntity>>

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateStatus(messageId: String, status: MessageStatus)

    /**
     * Одноразовое чтение сообщения (не Flow) — для проверок в бизнес-логике.
     */
    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getByIdOnce(id: String): MessageEntity?

    /**
     * Удаление исчезающих сообщений с истёкшим таймером.
     */
    @Query("DELETE FROM messages WHERE expires_at IS NOT NULL AND expires_at <= :now")
    suspend fun deleteExpired(now: Long): Int

    /**
     * Проверка существования сообщения по UUID — для дедупликации (MSG-05).
     */
    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE id = :id)")
    suspend fun existsById(id: String): Boolean

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    suspend fun getAllOnce(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE chat_id = :chatId ORDER BY timestamp ASC")
    suspend fun getForChatOnce(chatId: String): List<MessageEntity>

    /**
     * Пометить все входящие RECEIVED сообщения в чате как READ.
     */
    @Query("UPDATE messages SET status = 'READ' WHERE chat_id = :chatId AND status = 'RECEIVED' AND is_outgoing = 0")
    suspend fun markChatAsRead(chatId: String)
}
