package ru.cheburmail.app.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ru.cheburmail.app.db.entity.PendingAddRequestEntity

/**
 * DAO для входящих запросов на добавление участника группы (от не-админов).
 * Видны только админу группы (тому, чей email = chats.created_by).
 */
@Dao
interface PendingAddRequestDao {

    /**
     * Сохранить запрос. REPLACE при дубликате (chat_id, target_email):
     * повторный запрос на того же target в том же чате заменяет предыдущий
     * (например, если первый "залип" по сети и его повторили).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(request: PendingAddRequestEntity)

    @Query("SELECT * FROM pending_add_requests WHERE chat_id = :chatId ORDER BY created_at ASC")
    fun observeForChat(chatId: String): Flow<List<PendingAddRequestEntity>>

    @Query("SELECT * FROM pending_add_requests WHERE chat_id = :chatId ORDER BY created_at ASC")
    suspend fun getForChatOnce(chatId: String): List<PendingAddRequestEntity>

    @Query("SELECT * FROM pending_add_requests WHERE chat_id = :chatId AND target_email = :targetEmail")
    suspend fun get(chatId: String, targetEmail: String): PendingAddRequestEntity?

    @Query("DELETE FROM pending_add_requests WHERE chat_id = :chatId AND target_email = :targetEmail")
    suspend fun delete(chatId: String, targetEmail: String)

    /**
     * TTL очистка: удалить запросы старше cutoff (createdAt < cutoff).
     */
    @Query("DELETE FROM pending_add_requests WHERE created_at < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM pending_add_requests WHERE chat_id = :chatId")
    suspend fun countForChat(chatId: String): Int
}
