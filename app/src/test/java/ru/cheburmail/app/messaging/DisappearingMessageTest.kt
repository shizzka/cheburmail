package ru.cheburmail.app.messaging

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import ru.cheburmail.app.db.ChatType
import ru.cheburmail.app.db.ChatWithLastMessage
import ru.cheburmail.app.db.MessageStatus
import ru.cheburmail.app.db.entity.ChatEntity
import ru.cheburmail.app.db.entity.ChatMemberEntity
import ru.cheburmail.app.db.entity.MessageEntity

/**
 * Тесты исчезающих сообщений.
 * Проверяет установку таймера, расчёт expiresAt, очистку просроченных.
 */
class DisappearingMessageTest {

    private lateinit var manager: DisappearingMessageManager
    private lateinit var fakeChatDao: FakeChatDao
    private lateinit var fakeMessageDao: FakeMessageDao

    @Before
    fun setup() {
        fakeChatDao = FakeChatDao()
        fakeMessageDao = FakeMessageDao()
        manager = DisappearingMessageManager(fakeChatDao, fakeMessageDao)
    }

    @Test
    fun setTimer_existingChat_updatesTimer() = runBlocking {
        val chatId = "chat-1"
        val now = System.currentTimeMillis()
        fakeChatDao.chats[chatId] = ChatEntity(
            id = chatId, type = ChatType.DIRECT,
            createdAt = now, updatedAt = now
        )

        val fiveMinutes = 5 * 60 * 1000L
        manager.setTimer(chatId, fiveMinutes)

        val updated = fakeChatDao.chats[chatId]
        assertNotNull(updated)
        assertEquals(fiveMinutes, updated!!.disappearTimerMs)
    }

    @Test
    fun setTimer_null_disablesTimer() = runBlocking {
        val chatId = "chat-1"
        val now = System.currentTimeMillis()
        fakeChatDao.chats[chatId] = ChatEntity(
            id = chatId, type = ChatType.DIRECT,
            createdAt = now, updatedAt = now,
            disappearTimerMs = 60_000L
        )

        manager.setTimer(chatId, null)

        val updated = fakeChatDao.chats[chatId]
        assertNull(updated!!.disappearTimerMs)
    }

    @Test
    fun setTimer_nonexistentChat_noOp() = runBlocking {
        // Не должно бросать исключение
        manager.setTimer("nonexistent", 60_000L)
    }

    @Test
    fun getTimer_withTimer_returnsValue() = runBlocking {
        val chatId = "chat-1"
        val now = System.currentTimeMillis()
        val timer = 3600_000L
        fakeChatDao.chats[chatId] = ChatEntity(
            id = chatId, type = ChatType.DIRECT,
            createdAt = now, updatedAt = now,
            disappearTimerMs = timer
        )

        assertEquals(timer, manager.getTimer(chatId))
    }

    @Test
    fun getTimer_noTimer_returnsNull() = runBlocking {
        val chatId = "chat-1"
        val now = System.currentTimeMillis()
        fakeChatDao.chats[chatId] = ChatEntity(
            id = chatId, type = ChatType.DIRECT,
            createdAt = now, updatedAt = now
        )

        assertNull(manager.getTimer(chatId))
    }

    @Test
    fun calculateExpiresAt_withTimer_returnsTimestampPlusTimer() = runBlocking {
        val chatId = "chat-1"
        val now = System.currentTimeMillis()
        val timer = 5 * 60 * 1000L
        fakeChatDao.chats[chatId] = ChatEntity(
            id = chatId, type = ChatType.DIRECT,
            createdAt = now, updatedAt = now,
            disappearTimerMs = timer
        )

        val msgTimestamp = System.currentTimeMillis()
        val expiresAt = manager.calculateExpiresAt(chatId, msgTimestamp)

        assertEquals(msgTimestamp + timer, expiresAt)
    }

    @Test
    fun calculateExpiresAt_noTimer_returnsNull() = runBlocking {
        val chatId = "chat-1"
        val now = System.currentTimeMillis()
        fakeChatDao.chats[chatId] = ChatEntity(
            id = chatId, type = ChatType.DIRECT,
            createdAt = now, updatedAt = now
        )

        assertNull(manager.calculateExpiresAt(chatId, now))
    }

    @Test
    fun cleanup_deletesExpiredMessages() = runBlocking {
        val now = System.currentTimeMillis()

        // Просроченное сообщение
        fakeMessageDao.messages["msg-1"] = MessageEntity(
            id = "msg-1", chatId = "chat-1", isOutgoing = false,
            plaintext = "expired", status = MessageStatus.RECEIVED,
            timestamp = now - 3600_000, expiresAt = now - 1000
        )

        // Актуальное сообщение
        fakeMessageDao.messages["msg-2"] = MessageEntity(
            id = "msg-2", chatId = "chat-1", isOutgoing = false,
            plaintext = "still valid", status = MessageStatus.RECEIVED,
            timestamp = now, expiresAt = now + 3600_000
        )

        // Сообщение без таймера
        fakeMessageDao.messages["msg-3"] = MessageEntity(
            id = "msg-3", chatId = "chat-1", isOutgoing = false,
            plaintext = "no timer", status = MessageStatus.RECEIVED,
            timestamp = now
        )

        val deleted = manager.cleanup()

        assertEquals(1, deleted)
        // msg-1 удалено
        assertNull(fakeMessageDao.messages["msg-1"])
        // msg-2 и msg-3 на месте
        assertNotNull(fakeMessageDao.messages["msg-2"])
        assertNotNull(fakeMessageDao.messages["msg-3"])
    }

    @Test
    fun cleanup_noExpiredMessages_returnsZero() = runBlocking {
        val now = System.currentTimeMillis()

        fakeMessageDao.messages["msg-1"] = MessageEntity(
            id = "msg-1", chatId = "chat-1", isOutgoing = false,
            plaintext = "valid", status = MessageStatus.RECEIVED,
            timestamp = now, expiresAt = now + 3600_000
        )

        assertEquals(0, manager.cleanup())
    }

    // --- Fake DAOs ---

    private class FakeChatDao : ru.cheburmail.app.db.dao.ChatDao {
        val chats = mutableMapOf<String, ChatEntity>()

        override suspend fun insert(chat: ChatEntity) {
            chats[chat.id] = chat
        }

        override suspend fun getById(chatId: String): ChatEntity? = chats[chatId]

        override fun getAllWithLastMessage(): Flow<List<ChatWithLastMessage>> =
            flowOf(emptyList())

        override suspend fun insertMember(member: ChatMemberEntity) {}

        override suspend fun getMembersForChat(chatId: String): List<ChatMemberEntity> =
            emptyList()

        override suspend fun update(chat: ChatEntity) {
            chats[chat.id] = chat
        }

        override suspend fun delete(chat: ChatEntity) {
            chats.remove(chat.id)
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

        override suspend fun updateStatus(messageId: String, status: MessageStatus) {
            messages[messageId]?.let {
                messages[messageId] = it.copy(status = status)
            }
        }

        override suspend fun getByIdOnce(id: String): MessageEntity? = messages[id]

        override suspend fun deleteExpired(now: Long): Int {
            val expired = messages.values.filter {
                it.expiresAt != null && it.expiresAt <= now
            }
            expired.forEach { messages.remove(it.id) }
            return expired.size
        }

        override suspend fun existsById(id: String): Boolean = messages.containsKey(id)
    }
}
