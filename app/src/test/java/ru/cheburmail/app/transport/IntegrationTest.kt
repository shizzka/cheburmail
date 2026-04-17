package ru.cheburmail.app.transport

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.cheburmail.app.crypto.CryptoConstants
import ru.cheburmail.app.crypto.MessageDecryptor
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
 * Integration tests: full pipeline without real SMTP/IMAP servers.
 *
 * Uses FakeBoxNative for deterministic crypto operations and
 * mock SmtpClient/ImapClient for network simulation.
 */
class IntegrationTest {

    // --- Fake crypto ---

    private class FakeBoxNative : com.goterl.lazysodium.interfaces.Box.Native {
        override fun cryptoBoxEasy(c: ByteArray, m: ByteArray, mLen: Long, n: ByteArray, pk: ByteArray, sk: ByteArray): Boolean {
            // Fake encryption: XOR each byte with 0xAA for reversibility
            for (i in m.indices) {
                c[i] = (m[i].toInt() xor 0xAA).toByte()
            }
            // Fill MAC area with zeros
            for (i in m.size until c.size) {
                c[i] = 0
            }
            return true
        }
        override fun cryptoBoxOpenEasy(m: ByteArray, c: ByteArray, cLen: Long, n: ByteArray, pk: ByteArray, sk: ByteArray): Boolean {
            // Fake decryption: XOR with 0xAA (reverses the encryption)
            for (i in m.indices) {
                m[i] = (c[i].toInt() xor 0xAA).toByte()
            }
            return true
        }
        override fun cryptoBoxKeypair(pk: ByteArray, sk: ByteArray) = true
        override fun cryptoBoxSeedKeypair(pk: ByteArray, sk: ByteArray, seed: ByteArray) = true
        override fun cryptoBoxDetached(c: ByteArray, mac: ByteArray, m: ByteArray, mLen: Long, n: ByteArray, pk: ByteArray, sk: ByteArray) = true
        override fun cryptoBoxOpenDetached(m: ByteArray, c: ByteArray, mac: ByteArray, cLen: Long, n: ByteArray, pk: ByteArray, sk: ByteArray) = true
        override fun cryptoBoxBeforeNm(k: ByteArray, pk: ByteArray, sk: ByteArray) = true
        override fun cryptoBoxEasyAfterNm(c: ByteArray, m: ByteArray, mLen: Long, n: ByteArray, k: ByteArray) = true
        override fun cryptoBoxOpenEasyAfterNm(m: ByteArray, c: ByteArray, cLen: Long, n: ByteArray, k: ByteArray) = true
        override fun cryptoBoxDetachedAfterNm(c: ByteArray, mac: ByteArray, m: ByteArray, mLen: Long, n: ByteArray, k: ByteArray) = true
        override fun cryptoBoxOpenDetachedAfterNm(m: ByteArray, c: ByteArray, mac: ByteArray, cLen: Long, n: ByteArray, k: ByteArray) = true
        override fun cryptoBoxSeal(c: ByteArray, m: ByteArray, mLen: Long, pk: ByteArray) = true
        override fun cryptoBoxSealOpen(m: ByteArray, c: ByteArray, cLen: Long, pk: ByteArray, sk: ByteArray) = true
    }

    private class FakeRandomNative : com.goterl.lazysodium.interfaces.Random {
        override fun randomBytesRandom(): Long = 42L
        override fun randomBytesBuf(size: Int): ByteArray = ByteArray(size) { 0x42 }
        override fun randomBytesUniform(upperBound: Int): Long = 0L
        override fun randomBytesDeterministic(size: Int, seed: ByteArray): ByteArray = ByteArray(size)
        override fun nonce(size: Int): ByteArray = ByteArray(size) { 0x11 }
    }

    // --- Fake transport ---

    /**
     * Mock SMTP that captures sent emails into a shared mailbox.
     */
    private class MockSmtpClient : SmtpClient() {
        val sentEmails = mutableListOf<EmailMessage>()
        var errorCount = 0

        override fun send(config: EmailConfig, message: EmailMessage) {
            if (errorCount > 0) {
                errorCount--
                throw TransportException.SmtpException("Mock SMTP error")
            }
            sentEmails.add(message)
        }
    }

    /**
     * Mock IMAP that serves emails from a shared inbox.
     */
    private class MockImapClient : ImapClient() {
        val inbox = mutableListOf<EmailMessage>()

        override fun fetchMessages(config: EmailConfig): List<EmailMessage> = inbox.toList()
    }

    // --- Fake DAOs ---

    private class FakeMessageDao : ru.cheburmail.app.db.dao.MessageDao {
        val messages = mutableMapOf<String, MessageEntity>()

        override suspend fun insert(message: MessageEntity) {
            messages[message.id] = message
        }
        override suspend fun getById(id: String): MessageEntity? = messages[id]
        override fun getForChat(chatId: String) =
            flowOf(messages.values.filter { it.chatId == chatId })
        override suspend fun updateStatus(messageId: String, status: MessageStatus) {
            messages[messageId]?.let { messages[messageId] = it.copy(status = status) }
        }
        override suspend fun getByIdOnce(id: String): MessageEntity? = messages[id]
        override suspend fun deleteExpired(now: Long): Int = 0
        override suspend fun existsById(id: String): Boolean = messages.containsKey(id)
        override suspend fun getAllOnce(): List<MessageEntity> = messages.values.toList()
        override suspend fun getForChatOnce(chatId: String): List<MessageEntity> =
            messages.values.filter { it.chatId == chatId }
        override suspend fun markChatAsRead(chatId: String) {}
        override suspend fun deleteByChatId(chatId: String) {
            messages.entries.removeAll { it.value.chatId == chatId }
        }
        override suspend fun deleteById(messageId: String) { messages.remove(messageId) }
        override suspend fun updateMedia(
            messageId: String,
            localUri: String?,
            thumbnailUri: String?,
            downloadStatus: ru.cheburmail.app.db.MediaDownloadStatus
        ) {}
        override suspend fun insertDeleted(
            deleted: ru.cheburmail.app.db.entity.DeletedMessageEntity
        ) {}
        override suspend fun isDeleted(messageId: String): Boolean = false
    }

    private class FakeSendQueueDao : ru.cheburmail.app.db.dao.SendQueueDao {
        val entries = mutableListOf<SendQueueEntity>()
        private var nextId = 1L

        override suspend fun insert(item: SendQueueEntity): Long {
            val id = nextId++
            entries.add(item.copy(id = id))
            return id
        }

        override suspend fun getQueued(now: Long): List<SendQueueEntity> =
            entries.filter {
                it.status == QueueStatus.QUEUED &&
                    (it.nextRetryAt == null || it.nextRetryAt <= now)
            }

        override suspend fun getAll(): List<SendQueueEntity> = entries.toList()

        override suspend fun getByMessageId(messageId: String): List<SendQueueEntity> =
            entries.filter { it.messageId == messageId }

        override suspend fun updateStatus(
            id: Long,
            status: QueueStatus,
            retryCount: Int?,
            nextRetryAt: Long?,
            updatedAt: Long
        ) {
            val idx = entries.indexOfFirst { it.id == id }
            if (idx >= 0) {
                entries[idx] = entries[idx].copy(
                    status = status,
                    retryCount = retryCount ?: entries[idx].retryCount,
                    nextRetryAt = nextRetryAt
                )
            }
        }

        override suspend fun getRetryable(now: Long): List<SendQueueEntity> =
            entries.filter {
                it.status == QueueStatus.FAILED &&
                    it.nextRetryAt != null && it.nextRetryAt <= now
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
        override suspend fun getAllOnce(): List<ContactEntity> = contacts.values.toList()
    }

    // --- Test fields ---

    private val box = FakeBoxNative()
    private val random = FakeRandomNative()

    private val alicePublicKey = ByteArray(CryptoConstants.PUBLIC_KEY_BYTES) { 0x11 }
    private val alicePrivateKey = ByteArray(CryptoConstants.PRIVATE_KEY_BYTES) { 0x12 }
    private val bobPublicKey = ByteArray(CryptoConstants.PUBLIC_KEY_BYTES) { 0x21 }
    private val bobPrivateKey = ByteArray(CryptoConstants.PRIVATE_KEY_BYTES) { 0x22 }

    private lateinit var aliceConfig: EmailConfig
    private lateinit var bobConfig: EmailConfig

    @Before
    fun setup() {
        aliceConfig = EmailConfig("alice@yandex.ru", "pass", EmailProvider.YANDEX)
        bobConfig = EmailConfig("bob@mail.ru", "pass", EmailProvider.MAILRU)
    }

    /**
     * Full pipeline: Alice sends "Привет, Боб!" to Bob.
     *
     * 1. Alice encrypts and formats the message
     * 2. MockSmtpClient captures the email
     * 3. Email is "delivered" to Bob's MockImapClient
     * 4. Bob receives, parses, decrypts -> "Привет, Боб!"
     */
    @Test
    fun fullPipeline_aliceToBob_messageDecrypted() = runBlocking {
        val now = System.currentTimeMillis()
        val plaintext = "Привет, Боб!"

        // --- Alice's side ---
        val aliceSmtp = MockSmtpClient()
        val aliceMessageDao = FakeMessageDao()
        val aliceSendQueueDao = FakeSendQueueDao()
        val aliceContactDao = FakeContactDao()

        aliceContactDao.insert(ContactEntity(
            id = 1, email = "bob@mail.ru", displayName = "Bob",
            publicKey = bobPublicKey, fingerprint = "fp-bob",
            trustStatus = TrustStatus.VERIFIED, createdAt = now, updatedAt = now
        ))

        val aliceEncryptor = MessageEncryptor(box, NonceGenerator(random))
        val aliceDecryptor = MessageDecryptor(box)

        val aliceTransport = TransportService(
            smtpClient = aliceSmtp,
            imapClient = MockImapClient(),
            emailFormatter = EmailFormatter(),
            emailParser = EmailParser(),
            encryptor = aliceEncryptor,
            decryptor = aliceDecryptor
        )

        val aliceSendWorker = SendWorker(
            smtpClient = aliceSmtp,
            emailFormatter = EmailFormatter(),
            retryStrategy = RetryStrategy(),
            sendQueueDao = aliceSendQueueDao,
            messageDao = aliceMessageDao,
            contactDao = aliceContactDao,
            emailConfig = aliceConfig
        )

        val aliceRepo = MessageRepository(
            sendWorker = aliceSendWorker,
            receiveWorker = ReceiveWorker(
                transportService = aliceTransport,
                decryptor = aliceDecryptor,
                retryStrategy = RetryStrategy(),
                messageDao = aliceMessageDao,
                contactDao = aliceContactDao,
                recipientPrivateKey = alicePrivateKey
            ),
            encryptor = aliceEncryptor,
            messageDao = aliceMessageDao,
            sendQueueDao = aliceSendQueueDao,
            contactDao = aliceContactDao,
            config = aliceConfig,
            senderPrivateKey = alicePrivateKey
        )

        // Alice sends
        val msgUuid = aliceRepo.sendMessage(plaintext, "chat-alice-bob", "bob@mail.ru")

        // Verify Alice's side
        assertEquals(1, aliceSmtp.sentEmails.size)
        val sentEmail = aliceSmtp.sentEmails[0]
        assertTrue(sentEmail.subject.startsWith("CM/1/chat-alice-bob/"))
        assertTrue(sentEmail.subject.endsWith(msgUuid))

        // --- Bob's side ---
        val bobImap = MockImapClient()
        bobImap.inbox.add(sentEmail) // "deliver" Alice's email to Bob's inbox

        val bobMessageDao = FakeMessageDao()
        val bobContactDao = FakeContactDao()

        bobContactDao.insert(ContactEntity(
            id = 2, email = "alice@yandex.ru", displayName = "Alice",
            publicKey = alicePublicKey, fingerprint = "fp-alice",
            trustStatus = TrustStatus.VERIFIED, createdAt = now, updatedAt = now
        ))

        val bobDecryptor = MessageDecryptor(box)
        val bobTransport = TransportService(
            smtpClient = MockSmtpClient(),
            imapClient = bobImap,
            emailFormatter = EmailFormatter(),
            emailParser = EmailParser(),
            encryptor = MessageEncryptor(box, NonceGenerator(random)),
            decryptor = bobDecryptor
        )

        val bobReceiveWorker = ReceiveWorker(
            transportService = bobTransport,
            decryptor = bobDecryptor,
            retryStrategy = RetryStrategy(),
            messageDao = bobMessageDao,
            contactDao = bobContactDao,
            recipientPrivateKey = bobPrivateKey
        )

        val bobRepo = MessageRepository(
            sendWorker = SendWorker(
                MockSmtpClient(), EmailFormatter(), RetryStrategy(),
                FakeSendQueueDao(), bobMessageDao, bobContactDao, bobConfig
            ),
            receiveWorker = bobReceiveWorker,
            encryptor = MessageEncryptor(box, NonceGenerator(random)),
            messageDao = bobMessageDao,
            sendQueueDao = FakeSendQueueDao(),
            contactDao = bobContactDao,
            config = bobConfig,
            senderPrivateKey = bobPrivateKey
        )

        // Bob receives
        val count = bobRepo.checkIncoming()

        assertEquals(1, count)
        val received = bobMessageDao.messages[msgUuid]
        assertNotNull("Bob should have the message", received)
        assertEquals(plaintext, received!!.plaintext)
        assertEquals("chat-alice-bob", received.chatId)
        assertEquals(false, received.isOutgoing)
        assertEquals(MessageStatus.RECEIVED, received.status)
    }

    /**
     * Duplicate UUID: second message with same UUID is ignored.
     */
    @Test
    fun duplicateUuid_secondMessageIgnored() = runBlocking {
        val now = System.currentTimeMillis()
        val bobMessageDao = FakeMessageDao()
        val bobContactDao = FakeContactDao()

        bobContactDao.insert(ContactEntity(
            id = 1, email = "alice@yandex.ru", displayName = "Alice",
            publicKey = alicePublicKey, fingerprint = "fp-alice",
            trustStatus = TrustStatus.VERIFIED, createdAt = now, updatedAt = now
        ))

        val envelope = EncryptedEnvelope(
            nonce = ByteArray(CryptoConstants.NONCE_BYTES) { 0x42 },
            ciphertext = ByteArray(32) { 2 }
        )
        val email = EmailFormatter().format(
            envelope, "chat-1", "abc-123", "alice@yandex.ru", "bob@mail.ru"
        )

        val bobImap = MockImapClient()
        bobImap.inbox.add(email)
        bobImap.inbox.add(email) // duplicate

        val bobDecryptor = MessageDecryptor(box)
        val bobTransport = TransportService(
            smtpClient = MockSmtpClient(),
            imapClient = bobImap,
            emailFormatter = EmailFormatter(),
            emailParser = EmailParser(),
            encryptor = MessageEncryptor(box, NonceGenerator(random)),
            decryptor = bobDecryptor
        )

        val receiveWorker = ReceiveWorker(
            transportService = bobTransport,
            decryptor = bobDecryptor,
            retryStrategy = RetryStrategy(),
            messageDao = bobMessageDao,
            contactDao = bobContactDao,
            recipientPrivateKey = bobPrivateKey
        )

        val count = receiveWorker.pollAndProcess(bobConfig)

        // Only 1 message saved despite 2 emails with same UUID
        assertEquals(1, count)
        assertEquals(1, bobMessageDao.messages.size)
        assertTrue(bobMessageDao.messages.containsKey("abc-123"))
    }

    /**
     * SMTP error -> retry -> success.
     */
    @Test
    fun smtpError_retrySucceeds_messageSent() = runBlocking {
        val now = System.currentTimeMillis()
        val messageDao = FakeMessageDao()
        val sendQueueDao = FakeSendQueueDao()
        val contactDao = FakeContactDao()

        contactDao.insert(ContactEntity(
            id = 1, email = "bob@mail.ru", displayName = "Bob",
            publicKey = bobPublicKey, fingerprint = "fp-bob",
            trustStatus = TrustStatus.VERIFIED, createdAt = now, updatedAt = now
        ))

        val smtp = MockSmtpClient()
        smtp.errorCount = 1 // Fail first attempt, succeed second

        val encryptor = MessageEncryptor(box, NonceGenerator(random))

        // baseDelayMs = 0 so processQueue() picks up the requeued entry immediately
        // (FakeSendQueueDao now respects nextRetryAt, mirroring the real Room query).
        val zeroDelayRetry = RetryStrategy(baseDelayMs = 0)
        val sendWorker = SendWorker(
            smtpClient = smtp,
            emailFormatter = EmailFormatter(),
            retryStrategy = zeroDelayRetry,
            sendQueueDao = sendQueueDao,
            messageDao = messageDao,
            contactDao = contactDao,
            emailConfig = aliceConfig
        )

        val repo = MessageRepository(
            sendWorker = sendWorker,
            receiveWorker = ReceiveWorker(
                transportService = TransportService(smtp, MockImapClient(), EmailFormatter(), EmailParser(),
                    encryptor, MessageDecryptor(box)),
                decryptor = MessageDecryptor(box),
                retryStrategy = zeroDelayRetry,
                messageDao = messageDao,
                contactDao = contactDao,
                recipientPrivateKey = alicePrivateKey
            ),
            encryptor = encryptor,
            messageDao = messageDao,
            sendQueueDao = sendQueueDao,
            contactDao = contactDao,
            config = aliceConfig,
            senderPrivateKey = alicePrivateKey
        )

        // First attempt will fail (errorCount=1), but sendMessage catches the exception
        val uuid = repo.sendMessage("Hello!", "chat-1", "bob@mail.ru")

        // The entry should be re-queued after the first SMTP failure
        val entry = sendQueueDao.entries.first()
        // After failure, retryCount should be incremented and status QUEUED
        assertTrue(
            "Entry should be QUEUED for retry or already SENT",
            entry.status == QueueStatus.QUEUED || entry.status == QueueStatus.SENT
        )

        // Now process the queue again (errorCount is now 0 -> success)
        sendWorker.processQueue()

        // Verify: at least one email was eventually sent
        assertTrue("Email should have been sent on retry", smtp.sentEmails.isNotEmpty())
    }

    /**
     * 5 SMTP errors -> message marked FAILED.
     */
    @Test
    fun smtpError_maxRetries_messageFailed() = runBlocking {
        val now = System.currentTimeMillis()
        val messageDao = FakeMessageDao()
        val sendQueueDao = FakeSendQueueDao()
        val contactDao = FakeContactDao()

        contactDao.insert(ContactEntity(
            id = 1, email = "bob@mail.ru", displayName = "Bob",
            publicKey = bobPublicKey, fingerprint = "fp-bob",
            trustStatus = TrustStatus.VERIFIED, createdAt = now, updatedAt = now
        ))

        val smtp = MockSmtpClient()
        smtp.errorCount = 100 // Always fail

        val encryptor = MessageEncryptor(box, NonceGenerator(random))

        // Create a message + queue entry already at retryCount=4
        val msgUuid = "fail-msg-1"
        messageDao.insert(MessageEntity(
            id = msgUuid, chatId = "chat-1", isOutgoing = true,
            plaintext = "Doomed", status = MessageStatus.SENDING, timestamp = now
        ))

        val envelope = encryptor.encrypt(
            "Doomed".toByteArray(), bobPublicKey, alicePrivateKey
        )
        sendQueueDao.insert(SendQueueEntity(
            messageId = msgUuid,
            recipientEmail = "bob@mail.ru",
            encryptedPayload = envelope.toBytes(),
            status = QueueStatus.QUEUED,
            retryCount = 4, // Already at 4, next will be 5 = MAX_RETRIES
            nextRetryAt = now,
            createdAt = now,
            updatedAt = now
        ))

        val sendWorker = SendWorker(
            smtpClient = smtp,
            emailFormatter = EmailFormatter(),
            retryStrategy = RetryStrategy(),
            sendQueueDao = sendQueueDao,
            messageDao = messageDao,
            contactDao = contactDao,
            emailConfig = aliceConfig
        )

        sendWorker.processQueue()

        // Entry should be FAILED after exhausting retries
        val entry = sendQueueDao.entries.first()
        assertEquals(QueueStatus.FAILED, entry.status)
    }

    /**
     * Format -> parse round-trip with crypto.
     * Encrypt "Тестовое сообщение" -> format -> parse -> decrypt -> original text.
     */
    @Test
    fun cryptoFormatParse_roundTrip_plaintextPreserved() {
        val encryptor = MessageEncryptor(box, NonceGenerator(random))
        val decryptor = MessageDecryptor(box)
        val formatter = EmailFormatter()
        val parser = EmailParser()

        val originalText = "Тестовое сообщение"
        val originalBytes = originalText.toByteArray(Charsets.UTF_8)

        // Encrypt
        val envelope = encryptor.encrypt(originalBytes, bobPublicKey, alicePrivateKey)

        // Format as email
        val email = formatter.format(
            envelope, "chat-roundtrip", "uuid-rt-1", "alice@yandex.ru", "bob@mail.ru"
        )

        assertEquals("CM/1/chat-roundtrip/uuid-rt-1", email.subject)
        assertEquals(EmailMessage.CHEBURMAIL_CONTENT_TYPE, email.contentType)

        // Parse back
        val parsed = parser.parse(email)
        assertEquals("chat-roundtrip", parsed.chatId)
        assertEquals("uuid-rt-1", parsed.msgUuid)
        assertEquals("alice@yandex.ru", parsed.fromEmail)

        // Decrypt
        val decrypted = decryptor.decrypt(parsed.envelope, alicePublicKey, bobPrivateKey)
        val decryptedText = String(decrypted, Charsets.UTF_8)

        assertEquals(originalText, decryptedText)
    }
}
