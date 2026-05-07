package ru.cheburmail.app.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ru.cheburmail.app.db.CheburMailDatabase
import ru.cheburmail.app.db.MessageStatus
import ru.cheburmail.app.db.QueueStatus
import ru.cheburmail.app.repository.AccountRepository

/**
 * OneTimeWork, запускаемый при появлении сетевого соединения.
 *
 * Обрабатывает очередь отправки (send_queue): отправляет все QUEUED-элементы.
 * Используется для немедленной отправки накопленных офлайн-сообщений
 * при восстановлении связи, не дожидаясь периодической синхронизации.
 *
 * Constraint: NetworkType.CONNECTED — WorkManager сам отслеживает появление сети.
 */
class OutboxDrainWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Отправка накопленных сообщений при появлении сети")

        val accountRepo = AccountRepository.create(applicationContext)
        val config = accountRepo.getActive()
        if (config == null) {
            Log.w(TAG, "Нет активного аккаунта")
            return Result.success()
        }

        val db = CheburMailDatabase.getInstance(applicationContext)

        // Repair: queue entries с oversized BLOB (>1MB) — CursorWindow упадёт
        // при чтении. Раньше делали silent DELETE, что приводило к молчаливой
        // потере сообщений. Теперь: находим IDs, помечаем связанные messages
        // как FAILED (видно юзеру), удаляем queue entry.
        try {
            val cursor = db.openHelper.writableDatabase.query(
                "SELECT id, message_id FROM send_queue WHERE LENGTH(encrypted_payload) > 1000000 AND payload_file_path IS NULL"
            )
            val orphanIds = mutableListOf<Pair<Long, String>>()
            cursor.use { c ->
                while (c.moveToNext()) {
                    orphanIds += c.getLong(0) to c.getString(1)
                }
            }
            if (orphanIds.isNotEmpty()) {
                Log.w(TAG, "Repair: обнаружено ${orphanIds.size} oversized queue entries — пометим связанные messages как FAILED")
                for ((qId, msgId) in orphanIds) {
                    db.sendQueueDao().updateStatus(qId, QueueStatus.FAILED)
                    db.messageDao().updateStatus(msgId, MessageStatus.FAILED)
                    Log.w(TAG, "Repair: message $msgId (queue $qId) → FAILED (oversized BLOB)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Repair phase failed: ${e.message}")
        }

        // Проверяем наличие элементов в очереди
        val pendingCount = db.sendQueueDao().countPending()
        if (pendingCount == 0) {
            Log.d(TAG, "Очередь пуста, пропускаем")
            return Result.success()
        }

        Log.i(TAG, "В очереди $pendingCount сообщений, отправляем...")

        try {
            // Используем единую фабрику чтобы получить multi-account fallback
            // и health-tracking (раньше OutboxDrainWorker строил SendWorker
            // напрямую и не имел fallback при SMTP-блокировке).
            val syncFactory = SyncFactory(applicationContext)
            val sendWorker = syncFactory.buildSendWorker(config)

            sendWorker.processQueue()
            Log.i(TAG, "Очередь обработана")

            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка отправки: ${e.message}")
            return Result.retry()
        }
    }

    companion object {
        private const val TAG = "OutboxDrainWorker"
        const val WORK_NAME = "cheburmail_outbox_drain"

        /**
         * Поставить в очередь отправку при появлении сети.
         * REPLACE — перезаписывает предыдущий запрос, чтобы не накапливать дубликаты.
         */
        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<OutboxDrainWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )

            Log.d(TAG, "OutboxDrainWorker поставлен в очередь")
        }
    }
}
