package ru.cheburmail.app.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.cheburmail.app.crypto.CryptoProvider
import ru.cheburmail.app.crypto.KeyPairGenerator
import ru.cheburmail.app.crypto.MessageDecryptor
import ru.cheburmail.app.crypto.NonceGenerator
import ru.cheburmail.app.db.CheburMailDatabase
import ru.cheburmail.app.repository.AccountRepository
import ru.cheburmail.app.storage.SecureKeyStorage
import ru.cheburmail.app.transport.EmailFormatter
import ru.cheburmail.app.transport.EmailParser
import ru.cheburmail.app.transport.ImapClient
import ru.cheburmail.app.transport.ReceiveWorker
import ru.cheburmail.app.transport.RetryStrategy
import ru.cheburmail.app.transport.SmtpClient
import ru.cheburmail.app.transport.TransportService
import ru.cheburmail.app.messaging.KeyExchangeManager

/**
 * BroadcastReceiver для обработки событий синхронизации.
 *
 * Запускается при:
 * - IMAP IDLE событии (новое письмо на сервере)
 * - Триггере от пользователя (pull-to-refresh)
 */
class SyncReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_SYNC_NOW) {
            Log.d(TAG, "Получен запрос на синхронизацию")

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.launch {
                try {
                    syncNow(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка синхронизации: ${e.message}")
                }
            }
        }
    }

    private suspend fun syncNow(context: Context) {
        val accountRepo = AccountRepository.create(context)
        val config = accountRepo.getActive()
        if (config == null) {
            Log.w(TAG, "Нет активного аккаунта")
            return
        }

        val db = CheburMailDatabase.getInstance(context)
        val ls = CryptoProvider.lazySodium
        val keyPairGenerator = KeyPairGenerator(ls)
        val keyStorage = SecureKeyStorage.create(context, keyPairGenerator)
        val keyPair = keyStorage.getOrCreateKeyPair()

        val imapClient = ImapClient()
        val emailParser = EmailParser()
        val emailFormatter = EmailFormatter()
        val retryStrategy = RetryStrategy()
        val nonceGenerator = NonceGenerator(ls)
        val encryptor = ru.cheburmail.app.crypto.MessageEncryptor(ls, nonceGenerator)
        val decryptor = MessageDecryptor(ls)

        val transportService = TransportService(
            smtpClient = ru.cheburmail.app.transport.SmtpClient(),
            imapClient = imapClient,
            emailFormatter = emailFormatter,
            emailParser = emailParser,
            encryptor = encryptor,
            decryptor = decryptor
        )

        val notifHelper = ru.cheburmail.app.notification.NotificationHelper(context)

        val keyExchangeManager = KeyExchangeManager(
            smtpClient = SmtpClient(),
            contactDao = db.contactDao(),
            keyStorage = keyStorage,
            notificationHelper = notifHelper
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
            emailConfig = config
        )

        val received: Int
        try {
            received = receiveWorker.pollAndProcess(config)
        } finally {
            receiveWorker.wipePrivateKey()
        }
        Log.i(TAG, "Получено $received новых сообщений")
    }

    companion object {
        private const val TAG = "SyncReceiver"
        const val ACTION_SYNC_NOW = "ru.cheburmail.app.SYNC_NOW"

        /**
         * Создать Intent для запуска синхронизации.
         */
        fun createIntent(context: Context): Intent {
            return Intent(ACTION_SYNC_NOW).apply {
                setPackage(context.packageName)
            }
        }
    }
}
