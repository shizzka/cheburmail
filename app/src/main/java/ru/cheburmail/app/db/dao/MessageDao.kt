package ru.cheburmail.app.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ru.cheburmail.app.db.MediaDownloadStatus
import ru.cheburmail.app.db.MediaType
import ru.cheburmail.app.db.MessageStatus
import ru.cheburmail.app.db.entity.DeletedMessageEntity
import ru.cheburmail.app.db.entity.MessageEntity

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: String): MessageEntity?

    /**
     * Сообщения чата, упорядоченные по времени (DESC) для reverseLayout LazyColumn.
     * Последние сообщения идут первыми — UI отображает их снизу.
     */
    @Query("SELECT * FROM messages WHERE chat_id = :chatId ORDER BY timestamp DESC")
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

    /**
     * Удалить все сообщения чата.
     */
    @Query("DELETE FROM messages WHERE chat_id = :chatId")
    suspend fun deleteByChatId(chatId: String)

    /**
     * Удалить одно сообщение по ID.
     */
    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteById(messageId: String)

    /**
     * Обновить медиа-данные сообщения (URI файла, миниатюра, статус загрузки).
     */
    @Query("""
        UPDATE messages
        SET local_media_uri = :localUri,
            thumbnail_uri   = :thumbnailUri,
            media_download_status = :downloadStatus
        WHERE id = :messageId
    """)
    suspend fun updateMedia(
        messageId: String,
        localUri: String?,
        thumbnailUri: String?,
        downloadStatus: MediaDownloadStatus
    )

    // ── Deleted messages tracking ────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDeleted(deleted: DeletedMessageEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM deleted_messages WHERE message_id = :messageId)")
    suspend fun isDeleted(messageId: String): Boolean
}
