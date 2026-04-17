package ru.cheburmail.app.messaging

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.cheburmail.app.db.MessageStatus
import ru.cheburmail.app.db.entity.MessageEntity
import ru.cheburmail.app.transport.EmailMessage

/**
 * Тесты подтверждения доставки (delivery receipts).
 * Проверяет формат ACK, парсинг subject, обработку ACK.
 */
class DeliveryReceiptTest {

    private lateinit var handler: DeliveryReceiptHandler
    private lateinit var fakeMessageDao: FakeMessageDao

    @Before
    fun setup() {
        fakeMessageDao = FakeMessageDao()
        handler = DeliveryReceiptHandler(fakeMessageDao)
    }

    // --- DeliveryReceiptSender: формат ACK ---

    @Test
    fun isAckSubject_validAck_returnsTrue() {
        val subject = "CM/1/chat-123/ack-msg-uuid-456"
        assertTrue(DeliveryReceiptSender.isAckSubject(subject))
    }

    @Test
    fun isAckSubject_regularMessage_returnsFalse() {
        val subject = "CM/1/chat-123/msg-uuid-456"
        assertFalse(DeliveryReceiptSender.isAckSubject(subject))
    }

    @Test
    fun isAckSubject_nonCheburMail_returnsFalse() {
        val subject = "Hello World"
        assertFalse(DeliveryReceiptSender.isAckSubject(subject))
    }

    @Test
    fun isAckSubject_emptySubject_returnsFalse() {
        assertFalse(DeliveryReceiptSender.isAckSubject(""))
    }

    @Test
    fun extractOriginalMsgUuid_validAck_returnsUuid() {
        val subject = "CM/1/chat-123/ack-msg-uuid-456"
        assertEquals("msg-uuid-456", DeliveryReceiptSender.extractOriginalMsgUuid(subject))
    }

    @Test
    fun extractOriginalMsgUuid_regularMessage_returnsNull() {
        val subject = "CM/1/chat-123/msg-uuid-456"
        assertNull(DeliveryReceiptSender.extractOriginalMsgUuid(subject))
    }

    @Test
    fun extractChatId_validAck_returnsChatId() {
        val subject = "CM/1/chat-123/ack-msg-uuid-456"
        assertEquals("chat-123", DeliveryReceiptSender.extractChatId(subject))
    }

    @Test
    fun extractChatId_regularMessage_returnsNull() {
        val subject = "CM/1/chat-123/msg-uuid-456"
        assertNull(DeliveryReceiptSender.extractChatId(subject))
    }

    // --- DeliveryReceiptHandler: обработка ACK ---

    @Test
    fun handleAck_sentMessage_updatesToDelivered() = runBlocking {
        val msgId = "msg-001"
        fakeMessageDao.messages[msgId] = MessageEntity(
            id = msgId, chatId = "chat-1", isOutgoing = true,
            plaintext = "hello", status = MessageStatus.SENT,
            timestamp = System.currentTimeMillis()
        )

        val subject = "CM/1/chat-1/ack-$msgId"
        val result = handler.handleAck(subject)

        assertTrue(result)
        assertEquals(MessageStatus.DELIVERED, fakeMessageDao.statusUpdates[msgId])
    }

    @Test
    fun handleAck_alreadyDelivered_returnsFalse() = runBlocking {
        val msgId = "msg-002"
        fakeMessageDao.messages[msgId] = MessageEntity(
            id = msgId, chatId = "chat-1", isOutgoing = true,
            plaintext = "hello", status = MessageStatus.DELIVERED,
            timestamp = System.currentTimeMillis()
        )

        val subject = "CM/1/chat-1/ack-$msgId"
        val result = handler.handleAck(subject)

        assertFalse(result)
    }

    @Test
    fun handleAck_unknownMessage_returnsFalse() = runBlocking {
        val subject = "CM/1/chat-1/ack-nonexistent-msg"
        val result = handler.handleAck(subject)
        assertFalse(result)
    }

    @Test
    fun handleAck_invalidSubject_returnsFalse() = runBlocking {
        val result = handler.handleAck("invalid-subject")
        assertFalse(result)
    }

    @Test
    fun handleAck_failedMessage_notUpdated() = runBlocking {
        val msgId = "msg-003"
        fakeMessageDao.messages[msgId] = MessageEntity(
            id = msgId, chatId = "chat-1", isOutgoing = true,
            plaintext = "hello", status = MessageStatus.FAILED,
            timestamp = System.currentTimeMillis()
        )

        val subject = "CM/1/chat-1/ack-$msgId"
        val result = handler.handleAck(subject)

        assertFalse(result)
        // Статус не должен измениться
        assertFalse(fakeMessageDao.statusUpdates.containsKey(msgId))
    }

    // --- Fake DAO ---

    private class FakeMessageDao : ru.cheburmail.app.db.dao.MessageDao {
        val messages = mutableMapOf<String, MessageEntity>()
        val statusUpdates = mutableMapOf<String, MessageStatus>()

        override suspend fun insert(message: MessageEntity) {
            messages[message.id] = message
        }

        override suspend fun getById(id: String): MessageEntity? = messages[id]

        override fun getForChat(chatId: String) =
            kotlinx.coroutines.flow.flowOf(messages.values.filter { it.chatId == chatId })

        override suspend fun updateStatus(messageId: String, status: MessageStatus) {
            statusUpdates[messageId] = status
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
}
