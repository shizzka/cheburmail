package ru.cheburmail.app.sync

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import ru.cheburmail.app.notification.NotificationHelper
import ru.cheburmail.app.repository.AccountRepository
import ru.cheburmail.app.transport.EmailConfig

/**
 * Оркестратор фоновой синхронизации CheburMail.
 *
 * Управляет двумя стратегиями:
 * 1. IMAP IDLE — foreground service для мгновенного получения (push)
 * 2. WorkManager periodic — резервная синхронизация каждые 15 минут
 *
 * Дополнительно:
 * - OutboxDrainWorker — отправка накопленных сообщений при появлении сети
 *
 * Инициализация:
 * - Вызвать initialize() в Application.onCreate() после проверки наличия аккаунта
 * - При логауте вызвать stopAll()
 */
class SyncManager(private val context: Context) {

    private val notificationHelper = NotificationHelper(context)

    /**
     * Инициализация синхронизации при запуске приложения.
     * Если есть сконфигурированный аккаунт — запускает обе стратегии.
     */
    suspend fun initialize() {
        notificationHelper.createNotificationChannels()

        val accountRepo = AccountRepository.create(context)
        val config = accountRepo.getActive()
        if (config != null) {
            Log.i(TAG, "Аккаунт найден (${config.email}), запуск синхронизации")
            startImapIdle(config)
            schedulePeriodicSync()
            enqueueOutboxDrain()
        } else {
            Log.i(TAG, "Аккаунт не настроен, синхронизация отложена")
        }
    }

    /**
     * Запустить IMAP IDLE foreground service.
     *
     * @param config конфигурация email-аккаунта
     */
    fun startImapIdle(config: EmailConfig) {
        val intent = ImapIdleService.createIntent(context, config)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        Log.i(TAG, "IMAP IDLE сервис запущен для ${config.email}")
    }

    /**
     * Остановить IMAP IDLE foreground service.
     */
    fun stopImapIdle() {
        val intent = Intent(context, ImapIdleService::class.java)
        context.stopService(intent)
        Log.i(TAG, "IMAP IDLE сервис остановлен")
    }

    /**
     * Запланировать периодическую синхронизацию через WorkManager.
     */
    fun schedulePeriodicSync() {
        PeriodicSyncWorker.schedule(context)
    }

    /**
     * Отменить периодическую синхронизацию.
     */
    fun cancelPeriodicSync() {
        PeriodicSyncWorker.cancel(context)
    }

    /**
     * Поставить в очередь отправку накопленных сообщений при появлении сети.
     */
    fun enqueueOutboxDrain() {
        OutboxDrainWorker.enqueue(context)
    }

    /**
     * Остановить все стратегии синхронизации.
     * Вызывать при логауте пользователя.
     */
    fun stopAll() {
        stopImapIdle()
        cancelPeriodicSync()
        Log.i(TAG, "Вся синхронизация остановлена")
    }

    companion object {
        private const val TAG = "SyncManager"
    }
}
