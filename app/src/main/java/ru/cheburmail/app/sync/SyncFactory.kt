package ru.cheburmail.app.sync

import android.content.Context
import ru.cheburmail.app.crypto.CryptoProvider
import ru.cheburmail.app.crypto.MessageDecryptor
import ru.cheburmail.app.crypto.MessageEncryptor
import ru.cheburmail.app.crypto.NonceGenerator
import ru.cheburmail.app.db.CheburMailDatabase
import ru.cheburmail.app.media.MediaDecryptor
import ru.cheburmail.app.media.MediaFileManager
import ru.cheburmail.app.messaging.KeyExchangeManager
import ru.cheburmail.app.messaging.KeyexRateLimitStore
import ru.cheburmail.app.notification.NotificationHelper
import ru.cheburmail.app.storage.SecureKeyStorage
import ru.cheburmail.app.transport.EmailConfig
import ru.cheburmail.app.transport.EmailFormatter
import ru.cheburmail.app.transport.EmailParser
import ru.cheburmail.app.transport.ImapClient
import ru.cheburmail.app.transport.ReceiveWorker
import ru.cheburmail.app.transport.RetryStrategy
import ru.cheburmail.app.transport.SendWorker
import ru.cheburmail.app.transport.SmtpClient
import ru.cheburmail.app.transport.TransportService

/**
 * Единая точка сборки ReceiveWorker / SendWorker со всеми зависимостями.
 *
 * До появления этого файла сборка ReceiveWorker была продублирована в 4 местах
 * (PeriodicSyncWorker, ImapIdleService, SyncReceiver, ChatViewModel.doSync),
 * что приводило к дрейфу: изменение одной зависимости требовало синхронного
 * апдейта во всех копиях.
 *
 * Все зависимости должны конструироваться через эту фабрику.
 */
class SyncFactory(private val context: Context) {

    private val db: CheburMailDatabase by lazy { CheburMailDatabase.getInstance(context) }
    private val ls by lazy { CryptoProvider.lazySodium }

    /**
     * Собрать ReceiveWorker для текущего аккаунта.
     *
     * @param config конфигурация email-аккаунта
     * @param privateKey приватный ключ для расшифровки (будет обнулён `wipePrivateKey()`)
     * @param mediaDecryptor опциональный расшифровщик медиа (только UI)
     * @param mediaFileManager опциональный файл-менеджер медиа (только UI)
     */
    fun buildReceiveWorker(
        config: EmailConfig,
        privateKey: ByteArray,
        keyStorage: SecureKeyStorage,
        mediaDecryptor: MediaDecryptor? = null,
        mediaFileManager: MediaFileManager? = null
    ): ReceiveWorker {
        val nonceGen = NonceGenerator(ls)
        val encryptor = MessageEncryptor(ls, nonceGen)
        val decryptor = MessageDecryptor(ls)
        val imapClient = ImapClient()
        val smtpClient = SmtpClient()

        val transportService = TransportService(
            smtpClient = smtpClient,
            imapClient = imapClient,
            emailFormatter = EmailFormatter(),
            emailParser = EmailParser(),
            encryptor = encryptor,
            decryptor = decryptor
        )

        val notifHelper = NotificationHelper(context)

        val keyExchangeManager = KeyExchangeManager(
            smtpClient = smtpClient,
            contactDao = db.contactDao(),
            keyStorage = keyStorage,
            notificationHelper = notifHelper,
            processedDao = db.processedKeyExchangeDao(),
            imapClient = imapClient,
            rateLimitStore = KeyexRateLimitStore.sharedPrefs(context)
        )

        val controlMessageHandler = ru.cheburmail.app.group.ControlMessageHandler(
            chatDao = db.chatDao(),
            contactDao = db.contactDao(),
            selfEmail = config.email,
            keyStorage = keyStorage
        )

        return ReceiveWorker(
            transportService = transportService,
            decryptor = decryptor,
            retryStrategy = RetryStrategy(),
            messageDao = db.messageDao(),
            contactDao = db.contactDao(),
            chatDao = db.chatDao(),
            notificationHelper = notifHelper,
            recipientPrivateKey = privateKey,
            keyExchangeManager = keyExchangeManager,
            emailConfig = config,
            mediaDecryptor = mediaDecryptor,
            mediaFileManager = mediaFileManager,
            controlMessageHandler = controlMessageHandler
        )
    }

    /**
     * Собрать SendWorker для текущего аккаунта.
     */
    fun buildSendWorker(config: EmailConfig): SendWorker {
        return SendWorker(
            smtpClient = SmtpClient(),
            emailFormatter = EmailFormatter(),
            retryStrategy = RetryStrategy(),
            sendQueueDao = db.sendQueueDao(),
            messageDao = db.messageDao(),
            contactDao = db.contactDao(),
            emailConfig = config
        )
    }
}
