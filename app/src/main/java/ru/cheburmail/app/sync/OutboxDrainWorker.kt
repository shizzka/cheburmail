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
import ru.cheburmail.app.repository.AccountRepository
import ru.cheburmail.app.transport.EmailFormatter
import ru.cheburmail.app.transport.RetryStrategy
import ru.cheburmail.app.transport.SendWorker
import ru.cheburmail.app.transport.SmtpClient

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

        // Cleanup: delete queue entries with oversized BLOBs that crash CursorWindow
        try {
            db.openHelper.writableDatabase.execSQL(
                "DELETE FROM send_queue WHERE LENGTH(encrypted_payload) > 1000000 AND payload_file_path IS NULL"
            )
        } catch (_: Exception) {}

        // Проверяем наличие элементов в очереди
        val pendingCount = db.sendQueueDao().countPending()
        if (pendingCount == 0) {
            Log.d(TAG, "Очередь пуста, пропускаем")
            return Result.success()
        }

        Log.i(TAG, "В очереди $pendingCount сообщений, отправляем...")

        try {
            val sendWorker = SendWorker(
                smtpClient = SmtpClient(),
                emailFormatter = EmailFormatter(),
                retryStrategy = RetryStrategy(),
                sendQueueDao = db.sendQueueDao(),
                messageDao = db.messageDao(),
                contactDao = db.contactDao(),
                emailConfig = config
            )

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
