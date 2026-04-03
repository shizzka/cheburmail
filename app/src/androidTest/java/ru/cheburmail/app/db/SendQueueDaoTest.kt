package ru.cheburmail.app.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.cheburmail.app.db.dao.ChatDao
import ru.cheburmail.app.db.dao.MessageDao
import ru.cheburmail.app.db.dao.SendQueueDao
import ru.cheburmail.app.db.entity.ChatEntity
import ru.cheburmail.app.db.entity.MessageEntity
import ru.cheburmail.app.db.entity.SendQueueEntity

@RunWith(AndroidJUnit4::class)
class SendQueueDaoTest {

    private lateinit var db: CheburMailDatabase
    private lateinit var sendQueueDao: SendQueueDao
    private lateinit var messageDao: MessageDao
    private lateinit var chatDao: ChatDao

    private val chatId = "queue-test-chat"
    private val messageId = "queue-test-msg"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CheburMailDatabase::class.java
        ).allowMainThreadQueries().build()
        sendQueueDao = db.sendQueueDao()
        messageDao = db.messageDao()
        chatDao = db.chatDao()

        // Создаём чат и сообщение для FK
        kotlinx.coroutines.runBlocking {
            chatDao.insert(
                ChatEntity(
                    id = chatId,
                    type = ChatType.DIRECT,
                    title = null,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            messageDao.insert(
                MessageEntity(
                    id = messageId,
                    chatId = chatId,
                    senderContactId = null,
                    isOutgoing = true,
                    plaintext = "Тестовое сообщение",
                    status = MessageStatus.SENDING,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun makeQueueItem(
        status: QueueStatus = QueueStatus.QUEUED,
        retryCount: Int = 0,
        nextRetryAt: Long? = null,
        createdAt: Long = System.currentTimeMillis()
    ) = SendQueueEntity(
        messageId = messageId,
        recipientEmail = "bob@example.com",
        encryptedPayload = ByteArray(56) { it.toByte() },
        status = status,
        retryCount = retryCount,
        nextRetryAt = nextRetryAt,
        createdAt = createdAt,
        updatedAt = createdAt
    )

    @Test
    fun insertAndGetQueued() = runTest {
        val id = sendQueueDao.insert(makeQueueItem())
        assertTrue(id > 0)

        val queued = sendQueueDao.getQueued()
        assertEquals(1, queued.size)
        assertEquals(QueueStatus.QUEUED, queued[0].status)
        assertEquals("bob@example.com", queued[0].recipientEmail)
    }

    @Test
    fun getQueued_excludesNonQueued() = runTest {
        sendQueueDao.insert(makeQueueItem(status = QueueStatus.QUEUED))
        sendQueueDao.insert(makeQueueItem(status = QueueStatus.SENDING))
        sendQueueDao.insert(makeQueueItem(status = QueueStatus.SENT))
        sendQueueDao.insert(makeQueueItem(status = QueueStatus.FAILED))

        val queued = sendQueueDao.getQueued()
        assertEquals(1, queued.size)
        assertEquals(QueueStatus.QUEUED, queued[0].status)
    }

    @Test
    fun updateStatus_transitions() = runTest {
        val id = sendQueueDao.insert(makeQueueItem())
        val now = System.currentTimeMillis()

        // QUEUED → SENDING
        sendQueueDao.updateStatus(id, QueueStatus.SENDING, updatedAt = now)
        var items = sendQueueDao.getByMessageId(messageId)
        assertEquals(QueueStatus.SENDING, items[0].status)

        // SENDING → FAILED с retryCount и nextRetryAt
        val retryAt = now + 30_000
        sendQueueDao.updateStatus(id, QueueStatus.FAILED, retryCount = 1, nextRetryAt = retryAt, updatedAt = now)
        items = sendQueueDao.getByMessageId(messageId)
        assertEquals(QueueStatus.FAILED, items[0].status)
        assertEquals(1, items[0].retryCount)
        assertEquals(retryAt, items[0].nextRetryAt)

        // FAILED → SENT
        sendQueueDao.updateStatus(id, QueueStatus.SENT, updatedAt = now)
        items = sendQueueDao.getByMessageId(messageId)
        assertEquals(QueueStatus.SENT, items[0].status)
    }

    @Test
    fun getRetryable_filtersCorrectly() = runTest {
        val now = System.currentTimeMillis()

        // Элемент готовый к retry (FAILED + nextRetryAt в прошлом)
        sendQueueDao.insert(
            makeQueueItem(status = QueueStatus.FAILED, retryCount = 1, nextRetryAt = now - 1000)
        )
        // Элемент НЕ готовый к retry (nextRetryAt в будущем)
        sendQueueDao.insert(
            makeQueueItem(status = QueueStatus.FAILED, retryCount = 1, nextRetryAt = now + 60_000)
        )
        // QUEUED — не FAILED, не попадает
        sendQueueDao.insert(makeQueueItem(status = QueueStatus.QUEUED))

        val retryable = sendQueueDao.getRetryable(now)
        assertEquals(1, retryable.size)
        assertEquals(QueueStatus.FAILED, retryable[0].status)
        assertTrue(retryable[0].nextRetryAt!! <= now)
    }

    @Test
    fun countPending() = runTest {
        sendQueueDao.insert(makeQueueItem(status = QueueStatus.QUEUED))
        sendQueueDao.insert(makeQueueItem(status = QueueStatus.SENDING))
        sendQueueDao.insert(makeQueueItem(status = QueueStatus.SENT))
        sendQueueDao.insert(makeQueueItem(status = QueueStatus.FAILED))

        // QUEUED + SENDING = 2
        assertEquals(2, sendQueueDao.countPending())
    }

    @Test
    fun deleteSent_cleansUpCompleted() = runTest {
        sendQueueDao.insert(makeQueueItem(status = QueueStatus.SENT))
        sendQueueDao.insert(makeQueueItem(status = QueueStatus.SENT))
        sendQueueDao.insert(makeQueueItem(status = QueueStatus.QUEUED))

        val deleted = sendQueueDao.deleteSent()
        assertEquals(2, deleted)

        // Остался только QUEUED
        assertEquals(1, sendQueueDao.countPending())
    }

    @Test
    fun getByMessageId() = runTest {
        sendQueueDao.insert(makeQueueItem())
        sendQueueDao.insert(makeQueueItem())

        val items = sendQueueDao.getByMessageId(messageId)
        assertEquals(2, items.size)
        assertTrue(items.all { it.messageId == messageId })
    }
}
