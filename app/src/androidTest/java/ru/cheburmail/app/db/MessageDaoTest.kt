package ru.cheburmail.app.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.cheburmail.app.db.dao.ChatDao
import ru.cheburmail.app.db.dao.MessageDao
import ru.cheburmail.app.db.entity.ChatEntity
import ru.cheburmail.app.db.entity.MessageEntity
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MessageDaoTest {

    private lateinit var db: CheburMailDatabase
    private lateinit var messageDao: MessageDao
    private lateinit var chatDao: ChatDao

    private val chatId = "test-chat-001"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CheburMailDatabase::class.java
        ).allowMainThreadQueries().build()
        messageDao = db.messageDao()
        chatDao = db.chatDao()

        // Создаём чат-родитель для FK
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
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun makeMessage(
        id: String = UUID.randomUUID().toString(),
        timestamp: Long = System.currentTimeMillis(),
        status: MessageStatus = MessageStatus.SENDING,
        expiresAt: Long? = null
    ) = MessageEntity(
        id = id,
        chatId = chatId,
        senderContactId = null,
        isOutgoing = true,
        plaintext = "Сообщение $id",
        status = status,
        timestamp = timestamp,
        expiresAt = expiresAt
    )

    @Test
    fun insertAndGetById() = runTest {
        val msg = makeMessage(id = "msg-001")
        messageDao.insert(msg)

        val fetched = messageDao.getById("msg-001")
        assertNotNull(fetched)
        assertEquals("msg-001", fetched!!.id)
        assertEquals(chatId, fetched.chatId)
        assertEquals("Сообщение msg-001", fetched.plaintext)
    }

    @Test
    fun getForChat_orderedByTimestampAsc() = runTest {
        val now = System.currentTimeMillis()
        messageDao.insert(makeMessage(id = "msg-3", timestamp = now + 2000))
        messageDao.insert(makeMessage(id = "msg-1", timestamp = now))
        messageDao.insert(makeMessage(id = "msg-2", timestamp = now + 1000))

        val messages = messageDao.getForChat(chatId).first()
        assertEquals(3, messages.size)
        assertEquals("msg-1", messages[0].id)
        assertEquals("msg-2", messages[1].id)
        assertEquals("msg-3", messages[2].id)
    }

    @Test
    fun updateStatus() = runTest {
        messageDao.insert(makeMessage(id = "msg-status"))
        assertEquals(MessageStatus.SENDING, messageDao.getById("msg-status")!!.status)

        messageDao.updateStatus("msg-status", MessageStatus.SENT)
        assertEquals(MessageStatus.SENT, messageDao.getById("msg-status")!!.status)

        messageDao.updateStatus("msg-status", MessageStatus.DELIVERED)
        assertEquals(MessageStatus.DELIVERED, messageDao.getById("msg-status")!!.status)
    }

    @Test
    fun existsById_deduplication() = runTest {
        assertFalse(messageDao.existsById("msg-dedup"))

        messageDao.insert(makeMessage(id = "msg-dedup"))
        assertTrue(messageDao.existsById("msg-dedup"))
    }

    @Test
    fun deleteExpired_removesOnlyExpired() = runTest {
        val now = System.currentTimeMillis()
        // Сообщение с истёкшим таймером
        messageDao.insert(makeMessage(id = "msg-expired", expiresAt = now - 1000))
        // Сообщение без таймера
        messageDao.insert(makeMessage(id = "msg-permanent", expiresAt = null))
        // Сообщение с будущим таймером
        messageDao.insert(makeMessage(id = "msg-future", expiresAt = now + 60_000))

        val deletedCount = messageDao.deleteExpired(now)
        assertEquals(1, deletedCount)

        assertNull(messageDao.getById("msg-expired"))
        assertNotNull(messageDao.getById("msg-permanent"))
        assertNotNull(messageDao.getById("msg-future"))
    }

    @Test
    fun getByIdOnce_returnsCorrectMessage() = runTest {
        messageDao.insert(makeMessage(id = "msg-once"))
        val fetched = messageDao.getByIdOnce("msg-once")
        assertNotNull(fetched)
        assertEquals("msg-once", fetched!!.id)
    }
}
