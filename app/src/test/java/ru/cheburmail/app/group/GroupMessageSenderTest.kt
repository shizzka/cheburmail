package ru.cheburmail.app.group

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import ru.cheburmail.app.db.ChatType
import ru.cheburmail.app.db.QueueStatus
import ru.cheburmail.app.db.TrustStatus
import ru.cheburmail.app.db.ChatWithLastMessage
import ru.cheburmail.app.db.entity.ChatEntity
import ru.cheburmail.app.db.entity.ChatMemberEntity
import ru.cheburmail.app.db.entity.ContactEntity
import ru.cheburmail.app.db.entity.SendQueueEntity

/**
 * Unit-тесты GroupMessageSender: fan-out создаёт N-1 элементов очереди.
 */
class GroupMessageSenderTest {

    private lateinit var chatDao: FakeChatDao
    private lateinit var contactDao: FakeContactDao
    private lateinit var sendQueueDao: FakeSendQueueDao
    private lateinit var sender: GroupMessageSender

    @Before
    fun setup() {
        chatDao = FakeChatDao()
        contactDao = FakeContactDao()
        sendQueueDao = FakeSendQueueDao()

        val now = System.currentTimeMillis()

        // Создать группу с 3 участниками + отправитель
        val chatId = "group-chat-1"
        runBlocking {
            chatDao.insert(ChatEntity(chatId, ChatType.GROUP, "Группа", now, now))

            val contacts = listOf(
                ContactEntity(1, "alice@yandex.ru", "Alice", ByteArray(32), "fp1", TrustStatus.VERIFIED, now, now),
                ContactEntity(2, "bob@mail.ru", "Bob", ByteArray(32), "fp2", TrustStatus.VERIFIED, now, now),
                ContactEntity(3, "carol@gmail.com", "Carol", ByteArray(32), "fp3", TrustStatus.VERIFIED, now, now),
                ContactEntity(4, "me@yandex.ru", "Me", ByteArray(32), "fp4", TrustStatus.VERIFIED, now, now)
            )
            for (c in contacts) {
                contactDao.insert(c)
                chatDao.insertMember(ChatMemberEntity(chatId, c.id, now))
            }
        }

        sender = GroupMessageSender(
            chatDao = chatDao,
            contactDao = contactDao,
            sendQueueDao = sendQueueDao,
            encryptor = FakeEncryptor(),
            senderPrivateKey = ByteArray(32),
            senderEmail = "me@yandex.ru"
        )
    }

    @Test
    fun sendToGroup_createsQueueForEachMemberExceptSender() = runBlocking {
        val count = sender.sendToGroup("group-chat-1", "Привет группа!", "msg-1")

        // 4 участника - 1 отправитель = 3 элемента очереди
        assertEquals(3, count)
        assertEquals(3, sendQueueDao.items.size)
    }

    @Test
    fun sendToGroup_recipientsCorrect() = runBlocking {
        sender.sendToGroup("group-chat-1", "Привет!", "msg-2")

        val emails = sendQueueDao.items.map { it.recipientEmail }.toSet()
        assertEquals(setOf("alice@yandex.ru", "bob@mail.ru", "carol@gmail.com"), emails)
    }

    @Test
    fun sendToGroup_emptyGroupReturnsZero() = runBlocking {
        val emptyDao = FakeChatDao()
        val now = System.currentTimeMillis()
        runBlocking {
            emptyDao.insert(ChatEntity("empty", ChatType.GROUP, "Пустая", now, now))
        }

        val emptySender = GroupMessageSender(
            chatDao = emptyDao,
            contactDao = contactDao,
            sendQueueDao = sendQueueDao,
            encryptor = FakeEncryptor(),
            senderPrivateKey = ByteArray(32),
            senderEmail = "me@yandex.ru"
        )

        val count = emptySender.sendToGroup("empty", "Test", "msg-3")
        assertEquals(0, count)
    }

    // --- Fakes ---

    private class FakeChatDao : ru.cheburmail.app.db.dao.ChatDao {
        val chats = mutableMapOf<String, ChatEntity>()
        val members = mutableListOf<ChatMemberEntity>()

        override suspend fun insert(chat: ChatEntity) { chats[chat.id] = chat }
        override suspend fun getById(chatId: String): ChatEntity? = chats[chatId]
        override fun getAllWithLastMessage(): Flow<List<ChatWithLastMessage>> = flowOf(emptyList())
        override suspend fun insertMember(member: ChatMemberEntity) { members.add(member) }
        override suspend fun getMembersForChat(chatId: String) = members.filter { it.chatId == chatId }
        override suspend fun update(chat: ChatEntity) { chats[chat.id] = chat }
        override suspend fun delete(chat: ChatEntity) { chats.remove(chat.id) }
    }

    private class FakeContactDao : ru.cheburmail.app.db.dao.ContactDao {
        val contacts = mutableMapOf<Long, ContactEntity>()

        override suspend fun insert(contact: ContactEntity): Long {
            contacts[contact.id] = contact
            return contact.id
        }
        override suspend fun getById(id: Long) = contacts[id]
        override suspend fun getByEmail(email: String) = contacts.values.find { it.email == email }
        override fun getAll() = flowOf(contacts.values.toList())
        override suspend fun update(contact: ContactEntity) { contacts[contact.id] = contact }
        override suspend fun delete(contact: ContactEntity) { contacts.remove(contact.id) }
        override suspend fun deleteById(id: Long) { contacts.remove(id) }
    }

    private class FakeSendQueueDao : ru.cheburmail.app.db.dao.SendQueueDao {
        val items = mutableListOf<SendQueueEntity>()

        override suspend fun insert(item: SendQueueEntity): Long {
            items.add(item)
            return items.size.toLong()
        }
        override suspend fun getQueued() = items.filter { it.status == QueueStatus.QUEUED }
        override suspend fun getByMessageId(messageId: String) = items.filter { it.messageId == messageId }
        override suspend fun updateStatus(id: Long, status: QueueStatus, retryCount: Int?, nextRetryAt: Long?, updatedAt: Long) {}
        override suspend fun getRetryable(now: Long) = emptyList<SendQueueEntity>()
        override suspend fun deleteSent(): Int = 0
        override suspend fun countPending(): Int = items.size
    }

    private class FakeBoxNative : com.goterl.lazysodium.interfaces.Box.Native {
        override fun cryptoBoxEasy(c: ByteArray, m: ByteArray, mLen: Long, n: ByteArray, pk: ByteArray, sk: ByteArray): Boolean {
            System.arraycopy(m, 0, c, 0, m.size)
            return true
        }
        override fun cryptoBoxOpenEasy(m: ByteArray, c: ByteArray, cLen: Long, n: ByteArray, pk: ByteArray, sk: ByteArray) = true
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

    private class FakeEncryptor : ru.cheburmail.app.crypto.MessageEncryptor(
        box = FakeBoxNative(),
        nonceGenerator = ru.cheburmail.app.crypto.NonceGenerator(FakeRandomNative())
    )
}
