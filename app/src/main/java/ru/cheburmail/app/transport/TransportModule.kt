package ru.cheburmail.app.transport

import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.Random
import ru.cheburmail.app.crypto.MessageDecryptor
import ru.cheburmail.app.crypto.MessageEncryptor
import ru.cheburmail.app.crypto.NonceGenerator
import ru.cheburmail.app.db.dao.ContactDao
import ru.cheburmail.app.db.dao.MessageDao
import ru.cheburmail.app.db.dao.SendQueueDao

/**
 * Manual dependency wiring for the transport module.
 *
 * Creates and connects all transport components without a DI framework.
 * Will be replaced with Hilt/Koin in the future.
 */
object TransportModule {

    fun provideSmtpClient(): SmtpClient = SmtpClient()

    fun provideImapClient(): ImapClient = ImapClient()

    fun provideEmailFormatter(): EmailFormatter = EmailFormatter()

    fun provideEmailParser(): EmailParser = EmailParser()

    fun provideRetryStrategy(): RetryStrategy = RetryStrategy()

    fun provideEncryptor(box: Box.Native, random: Random): MessageEncryptor =
        MessageEncryptor(box, NonceGenerator(random))

    fun provideDecryptor(box: Box.Native): MessageDecryptor =
        MessageDecryptor(box)

    fun provideTransportService(
        box: Box.Native,
        random: Random
    ): TransportService {
        return TransportService(
            smtpClient = provideSmtpClient(),
            imapClient = provideImapClient(),
            emailFormatter = provideEmailFormatter(),
            emailParser = provideEmailParser(),
            encryptor = provideEncryptor(box, random),
            decryptor = provideDecryptor(box)
        )
    }

    fun provideSendWorker(
        emailConfig: EmailConfig,
        box: Box.Native,
        random: Random,
        sendQueueDao: SendQueueDao,
        messageDao: MessageDao,
        contactDao: ContactDao
    ): SendWorker {
        return SendWorker(
            smtpClient = provideSmtpClient(),
            emailFormatter = provideEmailFormatter(),
            retryStrategy = provideRetryStrategy(),
            sendQueueDao = sendQueueDao,
            messageDao = messageDao,
            contactDao = contactDao,
            emailConfig = emailConfig
        )
    }

    fun provideReceiveWorker(
        emailConfig: EmailConfig,
        box: Box.Native,
        random: Random,
        messageDao: MessageDao,
        contactDao: ContactDao,
        recipientPrivateKey: ByteArray
    ): ReceiveWorker {
        val transportService = provideTransportService(box, random)
        return ReceiveWorker(
            transportService = transportService,
            decryptor = provideDecryptor(box),
            retryStrategy = provideRetryStrategy(),
            messageDao = messageDao,
            contactDao = contactDao,
            recipientPrivateKey = recipientPrivateKey
        )
    }

    /**
     * Create the full MessageRepository with all dependencies wired.
     *
     * @param config SMTP/IMAP configuration
     * @param box NaCl Box.Native for crypto operations
     * @param random NaCl Random for nonce generation
     * @param messageDao Room DAO for messages
     * @param sendQueueDao Room DAO for send queue
     * @param contactDao Room DAO for contacts
     * @param senderPrivateKey 32-byte X25519 private key of the local user
     */
    fun provideMessageRepository(
        config: EmailConfig,
        box: Box.Native,
        random: Random,
        messageDao: MessageDao,
        sendQueueDao: SendQueueDao,
        contactDao: ContactDao,
        senderPrivateKey: ByteArray
    ): MessageRepository {
        val sendWorker = provideSendWorker(config, box, random, sendQueueDao, messageDao, contactDao)
        val receiveWorker = provideReceiveWorker(config, box, random, messageDao, contactDao, senderPrivateKey)
        val encryptor = provideEncryptor(box, random)

        return MessageRepository(
            sendWorker = sendWorker,
            receiveWorker = receiveWorker,
            encryptor = encryptor,
            messageDao = messageDao,
            sendQueueDao = sendQueueDao,
            contactDao = contactDao,
            config = config,
            senderPrivateKey = senderPrivateKey
        )
    }
}
