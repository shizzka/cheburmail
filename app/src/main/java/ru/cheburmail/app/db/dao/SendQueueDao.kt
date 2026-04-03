package ru.cheburmail.app.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ru.cheburmail.app.db.QueueStatus
import ru.cheburmail.app.db.entity.SendQueueEntity

@Dao
interface SendQueueDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: SendQueueEntity): Long

    /**
     * Элементы в статусе QUEUED, упорядоченные по времени создания.
     */
    @Query("SELECT * FROM send_queue WHERE status = 'QUEUED' ORDER BY created_at ASC")
    suspend fun getQueued(): List<SendQueueEntity>

    @Query("SELECT * FROM send_queue WHERE message_id = :messageId")
    suspend fun getByMessageId(messageId: String): List<SendQueueEntity>

    @Query("""
        UPDATE send_queue
        SET status = :status,
            retry_count = COALESCE(:retryCount, retry_count),
            next_retry_at = :nextRetryAt,
            updated_at = :updatedAt
        WHERE id = :id
    """)
    suspend fun updateStatus(
        id: Long,
        status: QueueStatus,
        retryCount: Int? = null,
        nextRetryAt: Long? = null,
        updatedAt: Long = System.currentTimeMillis()
    )

    /**
     * Элементы для повторной отправки: FAILED + время retry наступило.
     */
    @Query("""
        SELECT * FROM send_queue
        WHERE status = 'FAILED' AND next_retry_at IS NOT NULL AND next_retry_at <= :now
        ORDER BY next_retry_at ASC
    """)
    suspend fun getRetryable(now: Long): List<SendQueueEntity>

    /**
     * Очистка успешно отправленных элементов.
     */
    @Query("DELETE FROM send_queue WHERE status = 'SENT'")
    suspend fun deleteSent(): Int

    /**
     * Количество элементов, ожидающих отправки (QUEUED + SENDING).
     */
    @Query("SELECT COUNT(*) FROM send_queue WHERE status IN ('QUEUED', 'SENDING')")
    suspend fun countPending(): Int
}
