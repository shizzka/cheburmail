package ru.cheburmail.app.group

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.cheburmail.app.db.ChatType
import ru.cheburmail.app.db.TrustStatus
import ru.cheburmail.app.db.ChatWithLastMessage
import ru.cheburmail.app.db.entity.ChatEntity
import ru.cheburmail.app.db.entity.ChatMemberEntity
import ru.cheburmail.app.db.entity.ContactEntity

/**
 * Unit-тесты GroupManager: создание группы, добавление/удаление участников.
 */
class GroupManagerTest {

    private lateinit var chatDao: FakeChatDao
    private lateinit var contactDao: FakeContactDao
    private lateinit var groupMessageSender: FakeGroupMessageSender
    private lateinit var groupManager: GroupManager

    @Before
    fun setup() {
        chatDao = FakeChatDao()
        contactDao = FakeContactDao()
        groupMessageSender = FakeGroupMessageSender()

        // Добавить тестовые контакты
        val now = System.currentTimeMillis()
        runBlocking {
            contactDao.insert(ContactEntity(
                id = 1, email = "alice@yandex.ru", displayName = "Alice",
                publicKey = ByteArray(32), fingerprint = "fp1",
                trustStatus = TrustStatus.VERIFIED, createdAt = now, updatedAt = now
            ))
            contactDao.insert(ContactEntity(
                id = 2, email = "bob@mail.ru", displayName = "Bob",
                publicKey = ByteArray(32), fingerprint = "fp2",
                trustStatus = TrustStatus.VERIFIED, createdAt = now, updatedAt = now
            ))
            contactDao.insert(ContactEntity(
                id = 3, email = "carol@gmail.com", displayName = "Carol",
                publicKey = ByteArray(32), fingerprint = "fp3",
                trustStatus = TrustStatus.VERIFIED, createdAt = now, updatedAt = now
            ))
        }

        groupManager = GroupManager(chatDao, contactDao, groupMessageSender)
    }

    @Test
    fun createGroup_createsChat() = runBlocking {
        val chatId = groupManager.createGroup("Тестовая группа", listOf(1L, 2L))

        assertNotNull(chatId)
        val chat = chatDao.getById(chatId)
        assertNotNull(chat)
        assertEquals(ChatType.GROUP, chat!!.type)
        assertEquals("Тестовая группа", chat.title)
    }

    @Test
    fun createGroup_addsMembersToChat() = runBlocking {
        val chatId = groupManager.createGroup("Группа", listOf(1L, 2L, 3L))

        val members = chatDao.getMembersForChat(chatId)
        assertEquals(3, members.size)

        val contactIds = members.map { it.contactId }.toSet()
        assertTrue(contactIds.contains(1L))
        assertTrue(contactIds.contains(2L))
        assertTrue(contactIds.contains(3L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun createGroup_tooManyMembers_throws() = runBlocking {
        val ids = (1L..11L).toList()
        groupManager.createGroup("Большая группа", ids)
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun createGroup_emptyName_throws() = runBlocking {
        groupManager.createGroup("", listOf(1L))
        Unit
    }

    @Test
    fun addMember_addsToExistingGroup() = runBlocking {
        val chatId = groupManager.createGroup("Группа", listOf(1L))
        groupManager.addMember(chatId, 2L)

        val members = chatDao.getMembersForChat(chatId)
        assertEquals(2, members.size)
    }

    @Test
    fun addMember_duplicateIgnored() = runBlocking {
        val chatId = groupManager.createGroup("Группа", listOf(1L, 2L))
        groupManager.addMember(chatId, 1L) // уже в группе

        val members = chatDao.getMembersForChat(chatId)
        assertEquals(2, members.size) // не увеличилось
    }

    @Test
    fun addMember_sendsControlMessage() = runBlocking {
        val chatId = groupManager.createGroup("Группа", listOf(1L))
        groupManager.addMember(chatId, 2L)

        assertTrue(groupMessageSender.sentControlMessages.isNotEmpty())
        val lastMsg = groupMessageSender.sentControlMessages.last()
        assertEquals(ControlMessageType.MEMBER_ADDED, lastMsg.second.type)
    }

    @Test
    fun removeMember_sendsControlMessage() = runBlocking {
        val chatId = groupManager.createGroup("Группа", listOf(1L, 2L))
        groupManager.removeMember(chatId, 2L)

        assertTrue(groupMessageSender.sentControlMessages.isNotEmpty())
        val lastMsg = groupMessageSender.sentControlMessages.last()
        assertEquals(ControlMessageType.MEMBER_REMOVED, lastMsg.second.type)
        assertEquals("bob@mail.ru", lastMsg.second.targetEmail)
    }

    // --- Fakes ---

    private class FakeChatDao : ru.cheburmail.app.db.dao.ChatDao {
        val chats = mutableMapOf<String, ChatEntity>()
        val members = mutableListOf<ChatMemberEntity>()

        override suspend fun insert(chat: ChatEntity) { chats[chat.id] = chat }
        override suspend fun getById(chatId: String): ChatEntity? = chats[chatId]
        override fun getAllWithLastMessage(): Flow<List<ChatWithLastMessage>> = flowOf(emptyList())
        override suspend fun insertMember(member: ChatMemberEntity) {
            if (members.none { it.chatId == member.chatId && it.contactId == member.contactId }) {
                members.add(member)
            }
        }
        override suspend fun getMembersForChat(chatId: String): List<ChatMemberEntity> =
            members.filter { it.chatId == chatId }
        override suspend fun update(chat: ChatEntity) { chats[chat.id] = chat }
        override suspend fun delete(chat: ChatEntity) { chats.remove(chat.id) }
    }

    private class FakeContactDao : ru.cheburmail.app.db.dao.ContactDao {
        val contacts = mutableMapOf<Long, ContactEntity>()

        override suspend fun insert(contact: ContactEntity): Long {
            contacts[contact.id] = contact
            return contact.id
        }
        override suspend fun getById(id: Long): ContactEntity? = contacts[id]
        override suspend fun getByEmail(email: String): ContactEntity? =
            contacts.values.find { it.email == email }
        override fun getAll() = flowOf(contacts.values.toList())
        override suspend fun update(contact: ContactEntity) { contacts[contact.id] = contact }
        override suspend fun delete(contact: ContactEntity) { contacts.remove(contact.id) }
        override suspend fun deleteById(id: Long) { contacts.remove(id) }
    }

    private class FakeGroupMessageSender : GroupMessageSender(
        chatDao = FakeChatDao(),
        contactDao = FakeContactDao(),
        sendQueueDao = FakeSendQueueDao(),
        encryptor = FakeEncryptor(),
        senderPrivateKey = ByteArray(32),
        senderEmail = "me@yandex.ru"
    ) {
        val sentControlMessages = mutableListOf<Pair<String, ControlMessage>>()

        override suspend fun sendControlToGroup(chatId: String, controlMessage: ControlMessage): Int {
            sentControlMessages.add(chatId to controlMessage)
            return 0
        }
    }

    private class FakeSendQueueDao : ru.cheburmail.app.db.dao.SendQueueDao {
        override suspend fun insert(item: ru.cheburmail.app.db.entity.SendQueueEntity): Long = 0
        override suspend fun getQueued(): List<ru.cheburmail.app.db.entity.SendQueueEntity> = emptyList()
        override suspend fun getByMessageId(messageId: String) = emptyList<ru.cheburmail.app.db.entity.SendQueueEntity>()
        override suspend fun updateStatus(id: Long, status: ru.cheburmail.app.db.QueueStatus, retryCount: Int?, nextRetryAt: Long?, updatedAt: Long) {}
        override suspend fun getRetryable(now: Long) = emptyList<ru.cheburmail.app.db.entity.SendQueueEntity>()
        override suspend fun deleteSent(): Int = 0
        override suspend fun countPending(): Int = 0
    }

    private class FakeBoxNative : com.goterl.lazysodium.interfaces.Box.Native {
        override fun cryptoBoxEasy(c: ByteArray, m: ByteArray, mLen: Long, n: ByteArray, pk: ByteArray, sk: ByteArray) = true
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
