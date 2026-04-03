package ru.cheburmail.app.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.cheburmail.app.crypto.CryptoConstants
import ru.cheburmail.app.crypto.model.EncryptedEnvelope
import ru.cheburmail.app.db.MessageStatus
import ru.cheburmail.app.db.QueueStatus
import ru.cheburmail.app.db.TrustStatus
import ru.cheburmail.app.db.entity.ContactEntity
import ru.cheburmail.app.db.entity.MessageEntity
import ru.cheburmail.app.db.entity.SendQueueEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

/**
 * Unit tests for SendWorker using fake DAOs and a controllable SmtpClient.
 */
class SendWorkerTest {

    // --- Fakes ---

    private class FakeSmtpClient : SmtpClient() {
        var shouldThrow: Exception? = null
        val sentMessages = mutableListOf<EmailMessage>()

        override fun send(config: EmailConfig, message: EmailMessage) {
            shouldThrow?.let { throw it }
            sentMessages.add(message)
        }
    }

    private lateinit var fakeSmtp: FakeSmtpClient
    private lateinit var sendQueueDao: FakeSendQueueDao
    private lateinit var messageDao: FakeMessageDao
    private lateinit var contactDao: FakeContactDao
    private lateinit var sendWorker: SendWorker
    private lateinit var config: EmailConfig

    private val testEnvelope = EncryptedEnvelope(
        nonce = ByteArray(CryptoConstants.NONCE_BYTES) { 1 },
        ciphertext = ByteArray(32) { 2 }
    )

    @Before
    fun setup() {
        fakeSmtp = FakeSmtpClient()
        sendQueueDao = FakeSendQueueDao()
        messageDao = FakeMessageDao()
        contactDao = FakeContactDao()
        config = EmailConfig("test@yandex.ru", "pass", EmailProvider.YANDEX)

        sendWorker = SendWorker(
            smtpClient = fakeSmtp,
            emailFormatter = EmailFormatter(),
            retryStrategy = RetryStrategy(),
            sendQueueDao = sendQueueDao,
            messageDao = messageDao,
            contactDao = contactDao,
            emailConfig = config
        )
    }

    @Test
    fun processQueue_successfulSend_statusSent() = runBlocking {
        val now = System.currentTimeMillis()
        val entry = SendQueueEntity(
            id = 1,
            messageId = "msg-1",
            recipientEmail = "bob@mail.ru",
            encryptedPayload = testEnvelope.toBytes(),
            status = QueueStatus.QUEUED,
            createdAt = now,
            updatedAt = now
        )
        sendQueueDao.entries.add(entry)
        messageDao.messages["msg-1"] = MessageEntity(
            id = "msg-1", chatId = "chat-1", isOutgoing = true,
            plaintext = "hello", status = MessageStatus.SENDING, timestamp = now
        )
        contactDao.contacts["bob@mail.ru"] = ContactEntity(
            id = 1, email = "bob@mail.ru", displayName = "Bob",
            publicKey = ByteArray(32), fingerprint = "fp",
            trustStatus = TrustStatus.VERIFIED, createdAt = now, updatedAt = now
        )

        sendWorker.processQueue()

        val updated = sendQueueDao.statusUpdates.last()
        assertEquals(QueueStatus.SENT, updated.second)
    }

    @Test
    fun processQueue_smtpError_retryQueued() = runBlocking {
        val now = System.currentTimeMillis()
        fakeSmtp.shouldThrow = TransportException.SmtpException("Connection refused")

        val entry = SendQueueEntity(
            id = 1,
            messageId = "msg-1",
            recipientEmail = "bob@mail.ru",
            encryptedPayload = testEnvelope.toBytes(),
            status = QueueStatus.QUEUED,
            retryCount = 0,
            createdAt = now,
            updatedAt = now
        )
        sendQueueDao.entries.add(entry)
        messageDao.messages["msg-1"] = MessageEntity(
            id = "msg-1", chatId = "chat-1", isOutgoing = true,
            plaintext = "hello", status = MessageStatus.SENDING, timestamp = now
        )
        contactDao.contacts["bob@mail.ru"] = ContactEntity(
            id = 1, email = "bob@mail.ru", displayName = "Bob",
            publicKey = ByteArray(32), fingerprint = "fp",
            trustStatus = TrustStatus.VERIFIED, createdAt = now, updatedAt = now
        )

        sendWorker.processQueue()

        // Last status update should be QUEUED (retry)
        val lastUpdate = sendQueueDao.statusUpdates.last()
        assertEquals(QueueStatus.QUEUED, lastUpdate.second)
    }

    @Test
    fun processQueue_maxRetriesExhausted_statusFailed() = runBlocking {
        val now = System.currentTimeMillis()
        fakeSmtp.shouldThrow = TransportException.SmtpException("Connection refused")

        val entry = SendQueueEntity(
            id = 1,
            messageId = "msg-1",
            recipientEmail = "bob@mail.ru",
            encryptedPayload = testEnvelope.toBytes(),
            status = QueueStatus.QUEUED,
            retryCount = 4, // Already at 4, next will be 5 which exceeds max
            createdAt = now,
            updatedAt = now
        )
        sendQueueDao.entries.add(entry)
        messageDao.messages["msg-1"] = MessageEntity(
            id = "msg-1", chatId = "chat-1", isOutgoing = true,
            plaintext = "hello", status = MessageStatus.SENDING, timestamp = now
        )
        contactDao.contacts["bob@mail.ru"] = ContactEntity(
            id = 1, email = "bob@mail.ru", displayName = "Bob",
            publicKey = ByteArray(32), fingerprint = "fp",
            trustStatus = TrustStatus.VERIFIED, createdAt = now, updatedAt = now
        )

        sendWorker.processQueue()

        val lastUpdate = sendQueueDao.statusUpdates.last()
        assertEquals(QueueStatus.FAILED, lastUpdate.second)
    }

    @Test
    fun processQueue_emptyQueue_noAction() = runBlocking {
        sendWorker.processQueue()
        assertTrue(sendQueueDao.statusUpdates.isEmpty())
    }

    // --- Fake DAO implementations (minimal, in-memory) ---

    private class FakeSendQueueDao : ru.cheburmail.app.db.dao.SendQueueDao {
        val entries = mutableListOf<SendQueueEntity>()
        val statusUpdates = mutableListOf<Pair<Long, QueueStatus>>()

        override suspend fun insert(item: SendQueueEntity): Long {
            entries.add(item)
            return item.id
        }

        override suspend fun getQueued(): List<SendQueueEntity> {
            return entries.filter { it.status == QueueStatus.QUEUED }
        }

        override suspend fun getByMessageId(messageId: String): List<SendQueueEntity> {
            return entries.filter { it.messageId == messageId }
        }

        override suspend fun updateStatus(
            id: Long,
            status: QueueStatus,
            retryCount: Int?,
            nextRetryAt: Long?,
            updatedAt: Long
        ) {
            statusUpdates.add(id to status)
        }

        override suspend fun getRetryable(now: Long): List<SendQueueEntity> {
            return entries.filter {
                it.status == QueueStatus.FAILED &&
                    it.nextRetryAt != null && it.nextRetryAt <= now
            }
        }

        override suspend fun deleteSent(): Int = 0
        override suspend fun countPending(): Int = 0
    }

    private class FakeMessageDao : ru.cheburmail.app.db.dao.MessageDao {
        val messages = mutableMapOf<String, MessageEntity>()

        override suspend fun insert(message: MessageEntity) {
            messages[message.id] = message
        }
        override suspend fun getById(id: String): MessageEntity? = messages[id]
        override fun getForChat(chatId: String) =
            flowOf(messages.values.filter { it.chatId == chatId })
        override suspend fun updateStatus(messageId: String, status: MessageStatus) {}
        override suspend fun getByIdOnce(id: String): MessageEntity? = messages[id]
        override suspend fun deleteExpired(now: Long): Int = 0
        override suspend fun existsById(id: String): Boolean = messages.containsKey(id)
    }

    private class FakeContactDao : ru.cheburmail.app.db.dao.ContactDao {
        val contacts = mutableMapOf<String, ContactEntity>()

        override suspend fun insert(contact: ContactEntity): Long {
            contacts[contact.email] = contact
            return contact.id
        }
        override suspend fun getById(id: Long): ContactEntity? =
            contacts.values.find { it.id == id }
        override suspend fun getByEmail(email: String): ContactEntity? = contacts[email]
        override fun getAll() = flowOf(contacts.values.toList())
        override suspend fun update(contact: ContactEntity) {}
        override suspend fun delete(contact: ContactEntity) {}
        override suspend fun deleteById(id: Long) {}
    }
}
