package ru.cheburmail.app.transport

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ru.cheburmail.app.repository.AccountRepository
import java.util.concurrent.TimeUnit

/**
 * WorkManager-обёртка для EmailCleanupWorker.
 * Запускается раз в сутки, удаляет обработанные email старше 7 дней с IMAP-сервера.
 */
class ScheduledCleanupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val accountRepo = AccountRepository.create(applicationContext)
            val config = accountRepo.getActive() ?: return Result.success()

            val cleaner = EmailCleanupWorker()
            val deleted = cleaner.cleanup(config)
            Log.i(TAG, "Scheduled cleanup: $deleted emails deleted")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Scheduled cleanup failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ScheduledCleanup"
        private const val WORK_NAME = "cheburmail_imap_cleanup"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ScheduledCleanupWorker>(
                1, TimeUnit.DAYS
            ).setInitialDelay(2, TimeUnit.HOURS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            Log.i(TAG, "IMAP cleanup scheduled (daily)")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "IMAP cleanup cancelled")
        }
    }
}
