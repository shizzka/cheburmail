package ru.cheburmail.app.sync

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.cheburmail.app.crypto.CryptoProvider
import ru.cheburmail.app.crypto.KeyPairGenerator
import ru.cheburmail.app.crypto.MessageDecryptor
import ru.cheburmail.app.crypto.MessageEncryptor
import ru.cheburmail.app.crypto.NonceGenerator
import ru.cheburmail.app.db.CheburMailDatabase
import ru.cheburmail.app.notification.NotificationHelper
import ru.cheburmail.app.repository.AccountRepository
import ru.cheburmail.app.storage.SecureKeyStorage
import ru.cheburmail.app.transport.EmailConfig
import ru.cheburmail.app.transport.EmailFormatter
import ru.cheburmail.app.transport.EmailParser
import ru.cheburmail.app.transport.ImapClient
import ru.cheburmail.app.transport.ReceiveWorker
import ru.cheburmail.app.transport.RetryStrategy
import ru.cheburmail.app.transport.SmtpClient
import ru.cheburmail.app.transport.TransportService
import ru.cheburmail.app.messaging.KeyExchangeManager
import java.util.Properties
import javax.mail.Folder
import javax.mail.Session
import javax.mail.Store
import javax.mail.event.MessageCountAdapter
import javax.mail.event.MessageCountEvent

/**
 * Foreground Service поддерживающий IMAP IDLE соединение для
 * мгновенного получения новых сообщений.
 *
 * Алгоритм:
 * 1. Запуск как foreground service с уведомлением "CheburMail синхронизация"
 * 2. Подключение к IMAP, открытие папки CheburMail
 * 3. Вход в IDLE-режим (ожидание push-событий от сервера)
 * 4. При получении нового сообщения — оповещение через callback
 * 5. При потере соединения — переподключение с экспоненциальным backoff
 *
 * Жизненный цикл:
 * - START_STICKY — перезапуск системой при убийстве
 * - Старт при наличии сконфигурированного аккаунта
 * - Стоп при логауте пользователя
 */
class ImapIdleService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var idleJob: Job? = null
    private var store: Store? = null
    private var folder: Folder? = null

    /**
     * Слушатель новых сообщений. Устанавливается SyncManager.
     * Вызывается на IO-потоке.
     */
    var onNewMessage: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        val helper = NotificationHelper(this)
        helper.createNotificationChannels()
        startForeground(
            NotificationHelper.SYNC_NOTIFICATION_ID,
            helper.createSyncNotification()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val email = intent?.getStringExtra(EXTRA_EMAIL)
        val password = intent?.getStringExtra(EXTRA_PASSWORD)
        val imapHost = intent?.getStringExtra(EXTRA_IMAP_HOST)
        val imapPort = intent?.getIntExtra(EXTRA_IMAP_PORT, 993) ?: 993

        if (email == null || password == null || imapHost == null) {
            Log.e(TAG, "Не переданы данные аккаунта, останавливаем сервис")
            stopSelf()
            return START_NOT_STICKY
        }

        startIdleLoop(email, password, imapHost, imapPort)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        idleJob?.cancel()
        closeConnection()
        Log.i(TAG, "ImapIdleService остановлен")
    }

    private fun startIdleLoop(
        email: String,
        password: String,
        imapHost: String,
        imapPort: Int
    ) {
        idleJob?.cancel()
        idleJob = serviceScope.launch {
            var backoffMs = INITIAL_BACKOFF_MS

            while (isActive) {
                try {
                    connectAndIdle(email, password, imapHost, imapPort)
                    // Если IDLE завершился нормально (таймаут сервера) — переподключаемся без backoff
                    backoffMs = INITIAL_BACKOFF_MS
                } catch (e: Exception) {
                    Log.e(TAG, "IMAP IDLE ошибка: ${e.message}")
                    closeConnection()

                    if (!isActive) break

                    Log.d(TAG, "Переподключение через ${backoffMs / 1000}с")
                    delay(backoffMs)
                    backoffMs = (backoffMs * BACKOFF_MULTIPLIER).toLong()
                        .coerceAtMost(MAX_BACKOFF_MS)
                }
            }
        }
    }

    /**
     * Подключение к IMAP и вход в IDLE-режим.
     * Блокирует поток до разрыва IDLE (новое сообщение или таймаут).
     */
    private fun connectAndIdle(
        email: String,
        password: String,
        imapHost: String,
        imapPort: Int
    ) {
        val props = Properties().apply {
            put("mail.imap.host", imapHost)
            put("mail.imap.port", imapPort.toString())
            put("mail.imap.ssl.enable", "true")
            put("mail.imap.connectiontimeout", "30000")
            put("mail.imap.timeout", "30000")
        }

        val session = Session.getInstance(props)
        val newStore = session.getStore("imaps")
        newStore.connect(imapHost, email, password)
        store = newStore

        // Запускаем синхронизацию при подключении
        triggerSync()

        // IDLE на INBOX — новые письма приходят именно сюда
        // (ImapClient.fetchMessages() сам перемещает CM/1/ в CheburMail)
        val inbox = newStore.getFolder("INBOX")
        inbox.open(Folder.READ_ONLY)
        folder = inbox

        inbox.addMessageCountListener(object : MessageCountAdapter() {
            override fun messagesAdded(e: MessageCountEvent) {
                Log.i(TAG, "IDLE: ${e.messages.size} новых сообщений в INBOX")
                triggerSync()
                onNewMessage?.invoke()
            }
        })

        Log.i(TAG, "IMAP IDLE активен на $imapHost (мониторинг INBOX)")

        // Вход в IDLE на INBOX. Блокирует до:
        // - нового сообщения (вызовет listener)
        // - таймаута сервера (~29 мин)
        // - разрыва соединения (бросит исключение)
        val imapFolder = inbox as? com.sun.mail.imap.IMAPFolder
        if (imapFolder != null) {
            imapFolder.idle()
        } else {
            Thread.sleep(POLL_FALLBACK_MS)
        }

        closeConnection()
    }

    /**
     * Прямой запуск синхронизации (без BroadcastReceiver — MIUI блокирует broadcasts).
     */
    private fun triggerSync() {
        serviceScope.launch {
            try {
                Log.d(TAG, "Запуск прямой синхронизации")
                val accountRepo = AccountRepository.create(applicationContext)
                val config = accountRepo.getActive() ?: return@launch

                val db = CheburMailDatabase.getInstance(applicationContext)
                val ls = CryptoProvider.lazySodium
                val kpg = KeyPairGenerator(ls)
                val keyStorage = SecureKeyStorage.create(applicationContext, kpg)
                val keyPair = keyStorage.getOrCreateKeyPair()

                val nonceGen = NonceGenerator(ls)
                val transportService = TransportService(
                    smtpClient = SmtpClient(),
                    imapClient = ImapClient(),
                    emailFormatter = EmailFormatter(),
                    emailParser = EmailParser(),
                    encryptor = MessageEncryptor(ls, nonceGen),
                    decryptor = MessageDecryptor(ls)
                )

                val keyExchangeManager = KeyExchangeManager(
                    smtpClient = SmtpClient(),
                    contactDao = db.contactDao(),
                    keyStorage = keyStorage
                )

                val receiveWorker = ReceiveWorker(
                    transportService = transportService,
                    decryptor = MessageDecryptor(ls),
                    retryStrategy = RetryStrategy(),
                    messageDao = db.messageDao(),
                    contactDao = db.contactDao(),
                    chatDao = db.chatDao(),
                    notificationHelper = NotificationHelper(applicationContext),
                    recipientPrivateKey = keyPair.getPrivateKey(),
                    keyExchangeManager = keyExchangeManager,
                    emailConfig = config
                )

                val received = receiveWorker.pollAndProcess(config)
                Log.i(TAG, "Синхронизация завершена: $received новых сообщений")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка синхронизации: ${e.message}")
            }
        }
    }

    private fun closeConnection() {
        try {
            folder?.let { if (it.isOpen) it.close(false) }
        } catch (_: Exception) {}
        try {
            store?.close()
        } catch (_: Exception) {}
        folder = null
        store = null
    }

    companion object {
        private const val TAG = "ImapIdleService"
        const val EXTRA_EMAIL = "extra_email"
        const val EXTRA_PASSWORD = "extra_password"
        const val EXTRA_IMAP_HOST = "extra_imap_host"
        const val EXTRA_IMAP_PORT = "extra_imap_port"

        private const val INITIAL_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 300_000L // 5 мин
        private const val BACKOFF_MULTIPLIER = 2.0
        private const val POLL_FALLBACK_MS = 120_000L // 2 мин

        /**
         * Создать Intent для запуска сервиса.
         */
        fun createIntent(
            context: Context,
            config: EmailConfig
        ): Intent {
            return Intent(context, ImapIdleService::class.java).apply {
                putExtra(EXTRA_EMAIL, config.email)
                putExtra(EXTRA_PASSWORD, config.password)
                putExtra(EXTRA_IMAP_HOST, config.imapHost)
                putExtra(EXTRA_IMAP_PORT, config.imapPort)
            }
        }
    }
}
