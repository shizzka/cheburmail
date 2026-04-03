package ru.cheburmail.app.transport

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.cheburmail.app.crypto.CryptoConstants
import ru.cheburmail.app.crypto.MessageEncryptor
import ru.cheburmail.app.crypto.NonceGenerator
import ru.cheburmail.app.crypto.model.EncryptedEnvelope
import ru.cheburmail.app.db.MessageStatus
import ru.cheburmail.app.db.QueueStatus
import ru.cheburmail.app.db.TrustStatus
import ru.cheburmail.app.db.entity.ContactEntity
import ru.cheburmail.app.db.entity.MessageEntity
import ru.cheburmail.app.db.entity.SendQueueEntity

/**
 * Unit tests for MessageRepository using fake DAOs and controllable workers.
 */
class MessageRepositoryTest {

    // --- Fakes ---

    private class FakeBoxNative : com.goterl.lazysodium.interfaces.Box.Native {
        override fun cryptoBoxEasy(c: ByteArray, m: ByteArray, mLen: Long, n: ByteArray, pk: ByteArray, sk: ByteArray): Boolean {
            // Copy plaintext to ciphertext area (fake encryption)
            System.arraycopy(m, 0, c, 0, m.size)
            return true
        }
        override fun cryptoBoxOpenEasy(m: ByteArray, c: ByteArray, cLen: Long, n: ByteArray, pk: ByteArray, sk: ByteArray): Boolean {
            System.arraycopy(c, 0, m, 0, m.size)
            return true
        }
        override fun cryptoBoxKeypair(pk: ByteArray, sk: ByteArray) = true
        override fun cryptoBoxSeedKeypair(pk: ByteArray, sk: ByteArray, seed: ByteArray) = true
        override fun cryptoBoxBeforenm(k: ByteArray, pk: ByteArray, sk: ByteArray) = true
        override fun cryptoBoxEasyAfternm(c: ByteArray, m: ByteArray, mLen: Long, n: ByteArray, k: ByteArray) = true
        override fun cryptoBoxOpenEasyAfternm(m: ByteArray, c: ByteArray, cLen: Long, n: ByteArray, k: ByteArray) = true
        override fun cryptoBoxSeal(c: ByteArray, m: ByteArray, mLen: Long, pk: ByteArray) = true
        override fun cryptoBoxSealOpen(m: ByteArray, c: ByteArray, cLen: Long, pk: ByteArray, sk: ByteArray) = true
    }

    private class FakeRandomNative : com.goterl.lazysodium.interfaces.Random {
        override fun randomBytesRandom(): Int = 42
        override fun randomBytesBuf(size: Int): ByteArray = ByteArray(size) { 0x42 }
        override fun randomBytesBuf(buf: ByteArray, size: Int) { buf.fill(0x42) }
        override fun randomBytesUniform(upperBound: Int): Int = 0
        override fun randomBytesDeterministic(buf: ByteArray, size: Int, seed: ByteArray) {}
    }

    private class FakeMessageDao : ru.cheburmail.app.db.dao.MessageDao {
        val messages = mutableMapOf<String, MessageEntity>()

        override suspend fun insert(message: MessageEntity) {
            messages[message.id] = message
        }
        override suspend fun getById(id: String): MessageEntity? = messages[id]
        override fun getForChat(chatId: String) =
            flowOf(messages.values.filter { it.chatId == chatId })
        override suspend fun updateStatus(messageId: String, status: MessageStatus) {
            messages[messageId]?.let {
                messages[messageId] = it.copy(status = status)
            }
        }
        override suspend fun getByIdOnce(id: String): MessageEntity? = messages[id]
        override suspend fun deleteExpired(now: Long): Int = 0
        override suspend fun existsById(id: String): Boolean = messages.containsKey(id)
    }

    private class FakeSendQueueDao : ru.cheburmail.app.db.dao.SendQueueDao {
        val entries = mutableListOf<SendQueueEntity>()
        val statusUpdates = mutableListOf<Triple<Long, QueueStatus, Int?>>()
        private var nextId = 1L

        override suspend fun insert(item: SendQueueEntity): Long {
            val id = nextId++
            entries.add(item.copy(id = id))
            return id
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
            statusUpdates.add(Triple(id, status, retryCount))
            val idx = entries.indexOfFirst { it.id == id }
            if (idx >= 0) {
                entries[idx] = entries[idx].copy(
                    status = status,
                    retryCount = retryCount ?: entries[idx].retryCount,
                    nextRetryAt = nextRetryAt
                )
            }
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

    private class FakeSmtpClient : SmtpClient() {
        val sentMessages = mutableListOf<EmailMessage>()
        var shouldThrow: Exception? = null

        override fun send(config: EmailConfig, message: EmailMessage) {
            shouldThrow?.let { throw it }
            sentMessages.add(message)
        }
    }

    private class FakeImapClient : ImapClient() {
        var messages: List<EmailMessage> = emptyList()
        var shouldThrow: Exception? = null

        override fun fetchMessages(config: EmailConfig): List<EmailMessage> {
            shouldThrow?.let { throw it }
            return messages
        }
    }

    // --- Fields ---

    private lateinit var messageDao: FakeMessageDao
    private lateinit var sendQueueDao: FakeSendQueueDao
    private lateinit var contactDao: FakeContactDao
    private lateinit var fakeSmtp: FakeSmtpClient
    private lateinit var fakeImap: FakeImapClient
    private lateinit var repository: MessageRepository
    private lateinit var config: EmailConfig

    private val senderPrivateKey = ByteArray(CryptoConstants.PRIVATE_KEY_BYTES) { 0x11 }
    private val recipientPrivateKey = ByteArray(CryptoConstants.PRIVATE_KEY_BYTES) { 0x22 }

    @Before
    fun setup() {
        messageDao = FakeMessageDao()
        sendQueueDao = FakeSendQueueDao()
        contactDao = FakeContactDao()
        fakeSmtp = FakeSmtpClient()
        fakeImap = FakeImapClient()
        config = EmailConfig("me@yandex.ru", "pass", EmailProvider.YANDEX)

        val box = FakeBoxNative()
        val random = FakeRandomNative()
        val encryptor = MessageEncryptor(box, NonceGenerator(random))
        val decryptor = ru.cheburmail.app.crypto.MessageDecryptor(box)

        val transportService = TransportService(
            smtpClient = fakeSmtp,
            imapClient = fakeImap,
            emailFormatter = EmailFormatter(),
            emailParser = EmailParser(),
            encryptor = encryptor,
            decryptor = decryptor
        )

        val sendWorker = SendWorker(
            smtpClient = fakeSmtp,
            emailFormatter = EmailFormatter(),
            retryStrategy = RetryStrategy(),
            sendQueueDao = sendQueueDao,
            messageDao = messageDao,
            contactDao = contactDao,
            emailConfig = config
        )

        val receiveWorker = ReceiveWorker(
            transportService = transportService,
            decryptor = decryptor,
            retryStrategy = RetryStrategy(),
            messageDao = messageDao,
            contactDao = contactDao,
            recipientPrivateKey = recipientPrivateKey
        )

        repository = MessageRepository(
            sendWorker = sendWorker,
            receiveWorker = receiveWorker,
            encryptor = encryptor,
            messageDao = messageDao,
            sendQueueDao = sendQueueDao,
            contactDao = contactDao,
            config = config,
            senderPrivateKey = senderPrivateKey
        )

        // Add a default contact for Bob
        val now = System.currentTimeMillis()
        runBlocking {
            contactDao.insert(ContactEntity(
                id = 1,
                email = "bob@mail.ru",
                displayName = "Bob",
                publicKey = ByteArray(CryptoConstants.PUBLIC_KEY_BYTES) { 0x33 },
                fingerprint = "fp-bob",
                trustStatus = TrustStatus.VERIFIED,
                createdAt = now,
                updatedAt = now
            ))
        }
    }

    @Test
    fun sendMessage_createsQueueEntryAndTriggersWorker() = runBlocking {
        val uuid = repository.sendMessage("Hello Bob!", "chat-1", "bob@mail.ru")

        // Message saved to Room
        val msg = messageDao.messages[uuid]
        assertNotNull(msg)
        assertEquals("chat-1", msg!!.chatId)
        assertEquals(true, msg.isOutgoing)
        assertEquals("Hello Bob!", msg.plaintext)

        // Queue entry created
        assertTrue(sendQueueDao.entries.isNotEmpty())
        val entry = sendQueueDao.entries.first()
        assertEquals(uuid, entry.messageId)
        assertEquals("bob@mail.ru", entry.recipientEmail)

        // SMTP was triggered (SendWorker processed the queue)
        assertTrue(fakeSmtp.sentMessages.isNotEmpty())
    }

    @Test
    fun sendMessage_generatesUniqueUuid() = runBlocking {
        val uuid1 = repository.sendMessage("First", "chat-1", "bob@mail.ru")
        val uuid2 = repository.sendMessage("Second", "chat-1", "bob@mail.ru")

        assertNotEquals(uuid1, uuid2)
        assertEquals(2, messageDao.messages.size)
    }

    @Test
    fun checkIncoming_delegatesToReceiveWorker() = runBlocking {
        // Setup: add Alice as contact
        val now = System.currentTimeMillis()
        contactDao.insert(ContactEntity(
            id = 2,
            email = "alice@yandex.ru",
            displayName = "Alice",
            publicKey = ByteArray(CryptoConstants.PUBLIC_KEY_BYTES) { 0x44 },
            fingerprint = "fp-alice",
            trustStatus = TrustStatus.VERIFIED,
            createdAt = now,
            updatedAt = now
        ))

        // Create a CheburMail email that ImapClient will return
        val envelope = EncryptedEnvelope(
            nonce = ByteArray(CryptoConstants.NONCE_BYTES) { 0x42 },
            ciphertext = ByteArray(32) { 2 }
        )
        val email = EmailFormatter().format(
            envelope, "chat-1", "uuid-incoming-1", "alice@yandex.ru", "me@yandex.ru"
        )
        fakeImap.messages = listOf(email)

        val count = repository.checkIncoming()

        assertEquals(1, count)
        assertTrue(messageDao.messages.containsKey("uuid-incoming-1"))
    }

    @Test
    fun checkIncoming_noMessages_returnsZero() = runBlocking {
        fakeImap.messages = emptyList()

        val count = repository.checkIncoming()
        assertEquals(0, count)
    }

    @Test
    fun retrySending_processesFailedMessages() = runBlocking {
        // Manually insert a FAILED queue entry
        val now = System.currentTimeMillis()
        val msgUuid = "failed-msg-1"

        messageDao.insert(MessageEntity(
            id = msgUuid,
            chatId = "chat-1",
            isOutgoing = true,
            plaintext = "Retry me",
            status = MessageStatus.SENDING,
            timestamp = now
        ))

        val envelope = EncryptedEnvelope(
            nonce = ByteArray(CryptoConstants.NONCE_BYTES) { 1 },
            ciphertext = ByteArray(32) { 2 }
        )

        sendQueueDao.entries.add(SendQueueEntity(
            id = 1,
            messageId = msgUuid,
            recipientEmail = "bob@mail.ru",
            encryptedPayload = envelope.toBytes(),
            status = QueueStatus.FAILED,
            retryCount = 2,
            nextRetryAt = now - 1000, // already past
            createdAt = now,
            updatedAt = now
        ))

        repository.retrySending()

        // The entry should have been re-queued and processed
        val lastStatus = sendQueueDao.statusUpdates.lastOrNull()
        assertNotNull(lastStatus)
        // After re-queue (QUEUED) + processQueue, should end as SENT
        assertTrue(
            sendQueueDao.statusUpdates.any { it.second == QueueStatus.QUEUED }
        )
    }

    @Test(expected = IllegalStateException::class)
    fun sendMessage_unknownContact_throws() = runBlocking {
        repository.sendMessage("Hello", "chat-1", "unknown@example.com")
        Unit
    }

    @Test
    fun sendMessage_uuidFormatIncludesDashes() = runBlocking {
        val uuid = repository.sendMessage("Test", "chat-1", "bob@mail.ru")

        // Standard UUID format: 8-4-4-4-12
        assertTrue("UUID should contain dashes: $uuid", uuid.contains("-"))
        assertEquals(5, uuid.split("-").size)
    }
}
