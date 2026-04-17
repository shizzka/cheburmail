package ru.cheburmail.app.transport

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import ru.cheburmail.app.crypto.CryptoConstants
import ru.cheburmail.app.crypto.CryptoException
import ru.cheburmail.app.crypto.MessageDecryptor
import ru.cheburmail.app.crypto.model.EncryptedEnvelope
import ru.cheburmail.app.db.MessageStatus
import ru.cheburmail.app.db.TrustStatus
import ru.cheburmail.app.db.entity.ContactEntity
import ru.cheburmail.app.db.entity.MessageEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

/**
 * Unit tests for ReceiveWorker using fake DAOs and controllable TransportService.
 */
class ReceiveWorkerTest {

    // --- Fakes ---

    private class FakeTransportService(
        private var parsedMessages: List<EmailParser.ParsedMessage> = emptyList(),
        private var shouldThrowOnReceive: Exception? = null
    ) {
        fun setMessages(messages: List<EmailParser.ParsedMessage>) {
            parsedMessages = messages
        }

        fun setError(e: Exception) {
            shouldThrowOnReceive = e
        }

        fun receiveMessages(config: EmailConfig): List<EmailParser.ParsedMessage> {
            shouldThrowOnReceive?.let { throw it }
            return parsedMessages
        }
    }

    private class FakeDecryptor : MessageDecryptor(FakeBoxNative()) {
        var shouldThrowFor: Set<String> = emptySet() // msgUuids that should fail

        override fun decrypt(
            envelope: EncryptedEnvelope,
            senderPublicKey: ByteArray,
            recipientPrivateKey: ByteArray
        ): ByteArray {
            // We check via a hack: the ciphertext contains a marker
            // But actually we need to match by something. Let's just always succeed unless flagged.
            return "decrypted-text".toByteArray()
        }
    }

    private lateinit var fakeTransport: FakeTransportService
    private lateinit var fakeDecryptor: FakeDecryptor
    private lateinit var messageDao: FakeMessageDao
    private lateinit var contactDao: FakeContactDao
    private lateinit var receiveWorker: ReceiveWorker
    private lateinit var config: EmailConfig

    private val recipientPrivateKey = ByteArray(CryptoConstants.PRIVATE_KEY_BYTES) { 0x42 }

    private fun makeParsedMessage(
        chatId: String = "chat-1",
        msgUuid: String = "uuid-1",
        fromEmail: String = "alice@yandex.ru"
    ): EmailParser.ParsedMessage {
        val envelope = EncryptedEnvelope(
            nonce = ByteArray(CryptoConstants.NONCE_BYTES) { 1 },
            ciphertext = ByteArray(32) { 2 }
        )
        return EmailParser.ParsedMessage(
            chatId = chatId,
            msgUuid = msgUuid,
            envelope = envelope,
            fromEmail = fromEmail
        )
    }

    @Before
    fun setup() {
        fakeTransport = FakeTransportService()
        fakeDecryptor = FakeDecryptor()
        messageDao = FakeMessageDao()
        contactDao = FakeContactDao()
        config = EmailConfig("me@yandex.ru", "pass", EmailProvider.YANDEX)

        // ReceiveWorker takes a real TransportService, but we need to inject a fake.
        // We'll create a wrapper ReceiveWorker that uses our FakeTransportService.
        // Actually, ReceiveWorker calls transportService.receiveMessages() which calls
        // imapClient.fetchMessages() and emailParser methods. Let's create a real
        // TransportService with a fake ImapClient.
    }

    private fun createWorkerWithFakeTransport(): ReceiveWorker {
        // Create a custom ImapClient that returns controlled data
        val fakeImapClient = object : ImapClient() {
            var messages: List<EmailMessage> = emptyList()
            var shouldThrow: Exception? = null

            override fun fetchMessages(config: EmailConfig): List<EmailMessage> {
                shouldThrow?.let { throw it }
                return messages
            }
        }

        val transportService = TransportService(
            smtpClient = SmtpClient(),
            imapClient = fakeImapClient,
            emailFormatter = EmailFormatter(),
            emailParser = EmailParser(),
            encryptor = FakeEncryptor(),
            decryptor = fakeDecryptor
        )

        return ReceiveWorker(
            transportService = transportService,
            decryptor = fakeDecryptor,
            retryStrategy = RetryStrategy(),
            messageDao = messageDao,
            contactDao = contactDao,
            recipientPrivateKey = recipientPrivateKey
        )
    }

    @Test
    fun pollAndProcess_newMessage_savedToRoom() = runBlocking {
        val now = System.currentTimeMillis()
        contactDao.contacts["alice@yandex.ru"] = ContactEntity(
            id = 1, email = "alice@yandex.ru", displayName = "Alice",
            publicKey = ByteArray(32), fingerprint = "fp",
            trustStatus = TrustStatus.VERIFIED, createdAt = now, updatedAt = now
        )

        // Create a ReceiveWorker that uses a TransportService wrapping a controllable ImapClient
        val parsed = makeParsedMessage()
        val formatter = EmailFormatter()
        val envelope = parsed.envelope
        // Create an email that the ImapClient would return
        val email = formatter.format(envelope, "chat-1", "uuid-1", "alice@yandex.ru", "me@yandex.ru")

        val fakeImapClient = FakeImapClient(listOf(email))
        val transportService = TransportService(
            smtpClient = SmtpClient(),
            imapClient = fakeImapClient,
            emailFormatter = EmailFormatter(),
            emailParser = EmailParser(),
            encryptor = FakeEncryptor(),
            decryptor = fakeDecryptor
        )

        val worker = ReceiveWorker(
            transportService = transportService,
            decryptor = fakeDecryptor,
            retryStrategy = RetryStrategy(),
            messageDao = messageDao,
            contactDao = contactDao,
            recipientPrivateKey = recipientPrivateKey
        )

        val count = worker.pollAndProcess(config)
        assertEquals(1, count)
        assertEquals(true, messageDao.messages.containsKey("uuid-1"))
    }

    @Test
    fun pollAndProcess_duplicateUuid_skipped() = runBlocking {
        val now = System.currentTimeMillis()
        contactDao.contacts["alice@yandex.ru"] = ContactEntity(
            id = 1, email = "alice@yandex.ru", displayName = "Alice",
            publicKey = ByteArray(32), fingerprint = "fp",
            trustStatus = TrustStatus.VERIFIED, createdAt = now, updatedAt = now
        )

        // Pre-insert the message (duplicate)
        messageDao.messages["uuid-1"] = MessageEntity(
            id = "uuid-1", chatId = "chat-1", isOutgoing = false,
            plaintext = "old", status = MessageStatus.RECEIVED, timestamp = now
        )

        val parsed = makeParsedMessage()
        val email = EmailFormatter().format(parsed.envelope, "chat-1", "uuid-1", "alice@yandex.ru", "me@yandex.ru")
        val fakeImapClient = FakeImapClient(listOf(email))
        val transportService = TransportService(
            smtpClient = SmtpClient(),
            imapClient = fakeImapClient,
            emailFormatter = EmailFormatter(),
            emailParser = EmailParser(),
            encryptor = FakeEncryptor(),
            decryptor = fakeDecryptor
        )
        val worker = ReceiveWorker(
            transportService = transportService,
            decryptor = fakeDecryptor,
            retryStrategy = RetryStrategy(),
            messageDao = messageDao,
            contactDao = contactDao,
            recipientPrivateKey = recipientPrivateKey
        )

        val count = worker.pollAndProcess(config)
        assertEquals(0, count)
        // Original message unchanged
        assertEquals("old", messageDao.messages["uuid-1"]?.plaintext)
    }

    @Test
    fun pollAndProcess_unknownSender_skipped() = runBlocking {
        // No contact for alice@yandex.ru
        val parsed = makeParsedMessage()
        val email = EmailFormatter().format(parsed.envelope, "chat-1", "uuid-1", "alice@yandex.ru", "me@yandex.ru")
        val fakeImapClient = FakeImapClient(listOf(email))
        val transportService = TransportService(
            smtpClient = SmtpClient(),
            imapClient = fakeImapClient,
            emailFormatter = EmailFormatter(),
            emailParser = EmailParser(),
            encryptor = FakeEncryptor(),
            decryptor = fakeDecryptor
        )
        val worker = ReceiveWorker(
            transportService = transportService,
            decryptor = fakeDecryptor,
            retryStrategy = RetryStrategy(),
            messageDao = messageDao,
            contactDao = contactDao,
            recipientPrivateKey = recipientPrivateKey
        )

        val count = worker.pollAndProcess(config)
        assertEquals(0, count)
    }

    @Test
    fun pollAndProcess_decryptError_skippedContinues() = runBlocking {
        val now = System.currentTimeMillis()
        contactDao.contacts["alice@yandex.ru"] = ContactEntity(
            id = 1, email = "alice@yandex.ru", displayName = "Alice",
            publicKey = ByteArray(32), fingerprint = "fp",
            trustStatus = TrustStatus.VERIFIED, createdAt = now, updatedAt = now
        )
        contactDao.contacts["bob@mail.ru"] = ContactEntity(
            id = 2, email = "bob@mail.ru", displayName = "Bob",
            publicKey = ByteArray(32), fingerprint = "fp2",
            trustStatus = TrustStatus.VERIFIED, createdAt = now, updatedAt = now
        )

        val msg1 = makeParsedMessage(chatId = "chat-1", msgUuid = "uuid-1", fromEmail = "alice@yandex.ru")
        val msg2 = makeParsedMessage(chatId = "chat-2", msgUuid = "uuid-2", fromEmail = "bob@mail.ru")

        val email1 = EmailFormatter().format(msg1.envelope, "chat-1", "uuid-1", "alice@yandex.ru", "me@yandex.ru")
        val email2 = EmailFormatter().format(msg2.envelope, "chat-2", "uuid-2", "bob@mail.ru", "me@yandex.ru")

        val fakeImapClient = FakeImapClient(listOf(email1, email2))

        // Use a decryptor that fails on the first message but succeeds on the second
        val failingDecryptor = object : MessageDecryptor(FakeBoxNative()) {
            var callCount = 0
            override fun decrypt(
                envelope: EncryptedEnvelope,
                senderPublicKey: ByteArray,
                recipientPrivateKey: ByteArray
            ): ByteArray {
                callCount++
                if (callCount == 1) throw CryptoException("Decrypt failed for first msg")
                return "decrypted-text-2".toByteArray()
            }
        }

        val transportService = TransportService(
            smtpClient = SmtpClient(),
            imapClient = fakeImapClient,
            emailFormatter = EmailFormatter(),
            emailParser = EmailParser(),
            encryptor = FakeEncryptor(),
            decryptor = failingDecryptor
        )
        val worker = ReceiveWorker(
            transportService = transportService,
            decryptor = failingDecryptor,
            retryStrategy = RetryStrategy(),
            messageDao = messageDao,
            contactDao = contactDao,
            recipientPrivateKey = recipientPrivateKey
        )

        val count = worker.pollAndProcess(config)
        // First message failed decrypt, second succeeded
        assertEquals(1, count)
        assertEquals(true, messageDao.messages.containsKey("uuid-2"))
        assertEquals(false, messageDao.messages.containsKey("uuid-1"))
    }

    @Test
    fun pollAndProcess_imapError_returns0() = runBlocking {
        val fakeImapClient = FakeImapClient(emptyList()).apply {
            shouldThrow = TransportException.ImapException("Connection failed")
        }
        val transportService = TransportService(
            smtpClient = SmtpClient(),
            imapClient = fakeImapClient,
            emailFormatter = EmailFormatter(),
            emailParser = EmailParser(),
            encryptor = FakeEncryptor(),
            decryptor = fakeDecryptor
        )
        val worker = ReceiveWorker(
            transportService = transportService,
            decryptor = fakeDecryptor,
            retryStrategy = RetryStrategy(),
            messageDao = messageDao,
            contactDao = contactDao,
            recipientPrivateKey = recipientPrivateKey
        )

        val count = worker.pollAndProcess(config)
        assertEquals(0, count)
    }

    // --- Fake implementations ---

    private open class FakeImapClient(
        private val messages: List<EmailMessage> = emptyList()
    ) : ImapClient() {
        var shouldThrow: Exception? = null

        override fun fetchMessages(config: EmailConfig): List<EmailMessage> {
            shouldThrow?.let { throw it }
            return messages
        }
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

    private class FakeBoxNative : com.goterl.lazysodium.interfaces.Box.Native {
        override fun cryptoBoxEasy(c: ByteArray, m: ByteArray, mLen: Long, n: ByteArray, pk: ByteArray, sk: ByteArray): Boolean {
            System.arraycopy(m, 0, c, 0, m.size)
            return true
        }
        override fun cryptoBoxOpenEasy(m: ByteArray, c: ByteArray, cLen: Long, n: ByteArray, pk: ByteArray, sk: ByteArray): Boolean {
            System.arraycopy(c, 0, m, 0, m.size)
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

    private class FakeEncryptor : ru.cheburmail.app.crypto.MessageEncryptor(
        box = FakeBoxNative(),
        nonceGenerator = ru.cheburmail.app.crypto.NonceGenerator(FakeRandomNative())
    )

    private class FakeRandomNative : com.goterl.lazysodium.interfaces.Random {
        override fun randomBytesRandom(): Long = 42L
        override fun randomBytesBuf(size: Int): ByteArray = ByteArray(size) { 0x42 }
        override fun randomBytesUniform(upperBound: Int): Long = 0L
        override fun randomBytesDeterministic(size: Int, seed: ByteArray): ByteArray = ByteArray(size)
        override fun nonce(size: Int): ByteArray = ByteArray(size) { 0x11 }
    }
}
