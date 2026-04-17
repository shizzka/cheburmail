package ru.cheburmail.app.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ru.cheburmail.app.crypto.CryptoProvider
import ru.cheburmail.app.crypto.KeyPairGenerator
import ru.cheburmail.app.crypto.MessageDecryptor
import ru.cheburmail.app.crypto.MessageEncryptor
import ru.cheburmail.app.crypto.NonceGenerator
import ru.cheburmail.app.db.CheburMailDatabase
import ru.cheburmail.app.repository.AccountRepository
import ru.cheburmail.app.storage.SecureKeyStorage
import ru.cheburmail.app.transport.EmailFormatter
import ru.cheburmail.app.transport.EmailParser
import ru.cheburmail.app.transport.ImapClient
import ru.cheburmail.app.transport.ReceiveWorker
import ru.cheburmail.app.transport.RetryStrategy
import ru.cheburmail.app.transport.SendWorker
import ru.cheburmail.app.transport.SmtpClient
import ru.cheburmail.app.transport.TransportService
import ru.cheburmail.app.messaging.KeyExchangeManager
import ru.cheburmail.app.storage.AppSettings
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Периодический WorkManager worker для фоновой синхронизации.
 *
 * Выполняется каждые 15 минут при наличии сети:
 * 1. Получение новых сообщений (ReceiveWorker.pollAndProcess)
 * 2. Отправка очереди (SendWorker.processQueue)
 *
 * Политика: KEEP — не создавать дубликаты, если уже запланирован.
 */
class PeriodicSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Периодическая синхронизация запущена")

        val accountRepo = AccountRepository.create(applicationContext)
        val config = accountRepo.getActive()
        if (config == null) {
            Log.w(TAG, "Нет активного аккаунта, пропускаем синхронизацию")
            return Result.success()
        }

        val db = CheburMailDatabase.getInstance(applicationContext)
        val ls = CryptoProvider.lazySodium
        val keyPairGenerator = KeyPairGenerator(ls)
        val keyStorage = SecureKeyStorage.create(applicationContext, keyPairGenerator)
        val keyPair = keyStorage.getOrCreateKeyPair()

        try {
            // Получение входящих
            val imapClient = ImapClient()
            val emailParser = EmailParser()
            val emailFormatter = EmailFormatter()
            val smtpClient = SmtpClient()
            val retryStrategy = RetryStrategy()
            val nonceGenerator = NonceGenerator(ls)
            val encryptor = MessageEncryptor(ls, nonceGenerator)
            val decryptor = MessageDecryptor(ls)

            val transportService = TransportService(
                smtpClient = smtpClient,
                imapClient = imapClient,
                emailFormatter = emailFormatter,
                emailParser = emailParser,
                encryptor = encryptor,
                decryptor = decryptor
            )

            val notifHelper = ru.cheburmail.app.notification.NotificationHelper(applicationContext)

            val keyExchangeManager = KeyExchangeManager(
                smtpClient = smtpClient,
                contactDao = db.contactDao(),
                keyStorage = keyStorage,
                notificationHelper = notifHelper,
                processedDao = db.processedKeyExchangeDao(),
                imapClient = imapClient
            )

            val receiveWorker = ReceiveWorker(
                transportService = transportService,
                decryptor = decryptor,
                retryStrategy = retryStrategy,
                messageDao = db.messageDao(),
                contactDao = db.contactDao(),
                chatDao = db.chatDao(),
                notificationHelper = notifHelper,
                recipientPrivateKey = keyPair.getPrivateKey(),
                keyExchangeManager = keyExchangeManager,
                emailConfig = config,
                controlMessageHandler = ru.cheburmail.app.group.ControlMessageHandler(
                    chatDao = db.chatDao(),
                    contactDao = db.contactDao(),
                    selfEmail = config.email
                )
            )

            val received: Int
            try {
                received = receiveWorker.pollAndProcess(config)
            } finally {
                receiveWorker.wipePrivateKey()
            }
            Log.i(TAG, "Получено $received новых сообщений")

            // Отправка очереди
            val sendWorker = SendWorker(
                smtpClient = smtpClient,
                emailFormatter = emailFormatter,
                retryStrategy = retryStrategy,
                sendQueueDao = db.sendQueueDao(),
                messageDao = db.messageDao(),
                contactDao = db.contactDao(),
                emailConfig = config
            )

            sendWorker.processQueue()
            Log.i(TAG, "Очередь отправки обработана")

            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка синхронизации: ${e.message}")
            return Result.retry()
        }
    }

    companion object {
        private const val TAG = "PeriodicSyncWorker"
        const val WORK_NAME = "cheburmail_periodic_sync"

        /**
         * Запланировать периодическую синхронизацию.
         * Интервал берётся из AppSettings (минимум WorkManager — 15 минут).
         */
        suspend fun schedule(context: Context) {
            val settings = AppSettings.getInstance(context)
            val intervalMin = settings.backgroundSyncIntervalMin.first()
                .coerceAtLeast(15L) // WorkManager minimum

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<PeriodicSyncWorker>(
                intervalMin, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )

            Log.i(TAG, "Периодическая синхронизация запланирована ($intervalMin мин)")
        }

        /**
         * Отменить периодическую синхронизацию.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Периодическая синхронизация отменена")
        }
    }
}
