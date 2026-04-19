package ru.cheburmail.app.group

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.cheburmail.app.db.ChatType
import ru.cheburmail.app.db.ChatWithLastMessage
import ru.cheburmail.app.db.MediaDownloadStatus
import ru.cheburmail.app.db.MessageStatus
import ru.cheburmail.app.db.QueueStatus
import ru.cheburmail.app.db.TrustStatus
import ru.cheburmail.app.db.entity.ChatEntity
import ru.cheburmail.app.db.entity.ChatMemberEntity
import ru.cheburmail.app.db.entity.ContactEntity
import ru.cheburmail.app.db.entity.DeletedMessageEntity
import ru.cheburmail.app.db.entity.MessageEntity
import ru.cheburmail.app.db.entity.PendingAddRequestEntity
import ru.cheburmail.app.db.entity.SendQueueEntity

/**
 * Unit-тесты approval-флоу GroupManager:
 * requestAddMember / approveAddRequest / rejectAddRequest.
 *
 * Сценарий:
 *   me@y.ru (Eugene) — ADMIN группы "Тест"
 *   alice@y.ru — verified участник
 *   bob@y.ru — verified, НЕ в группе → кандидат на добавление
 *   carol@y.ru — UNVERIFIED, не должен проходить как target
 */
class GroupManagerApprovalTest {

    private lateinit var chatDao: FakeChatDao
    private lateinit var contactDao: FakeContactDao
    private lateinit var pendingDao: FakePendingAddRequestDao
    private lateinit var sender: FakeGroupMessageSender

    private val adminEmail = "me@y.ru"
    private val aliceEmail = "alice@y.ru"
    private val bobEmail = "bob@y.ru"
    private val carolEmail = "carol@y.ru"

    private val now = 1_700_000_000_000L

    @Before
    fun setup() {
        chatDao = FakeChatDao()
        contactDao = FakeContactDao()
        pendingDao = FakePendingAddRequestDao()

        runBlocking {
            // Контакты у admin'а
            contactDao.insert(verifiedContact(1L, aliceEmail, "Alice"))
            contactDao.insert(verifiedContact(2L, bobEmail, "Bob"))
            contactDao.insert(unverifiedContact(3L, carolEmail, "Carol"))
        }
    }

    private fun adminGm(): GroupManager {
        sender = FakeGroupMessageSender()
        return GroupManager(
            chatDao, contactDao, sender,
            pendingAddRequestDao = pendingDao,
            selfEmail = adminEmail
        )
    }

    private fun aliceGm(): GroupManager {
        // Alice не админ; у Alice свой набор контактов → пересоздаём contactDao
        sender = FakeGroupMessageSender()
        return GroupManager(
            chatDao, contactDao, sender,
            pendingAddRequestDao = pendingDao,
            selfEmail = aliceEmail
        )
    }

    // ── requestAddMember ─────────────────────────────────────────

    @Test
    fun requestAddMember_verifiedTarget_pendingSavedAndCtrlSent() = runBlocking {
        val gm = adminGm()
        // Сначала admin создаёт группу с alice; для Alice её GroupManager —
        // не админ, она шлёт запрос на bob.
        val chatId = gm.createGroup("Тест", listOf(1L), creatorEmail = adminEmail)

        // Имитируем что у Alice есть контакт admin'а (для sendControlToAdmin)
        runBlocking { contactDao.insert(verifiedContact(99L, adminEmail, "Admin")) }

        val aliceManager = aliceGm()
        val ok = aliceManager.requestAddMember(chatId, 2L) // bob

        assertTrue("requestAddMember должен вернуть true", ok)
        assertEquals(1, sender.adminControl.size)
        val (toEmail, msg) = sender.adminControl.last()
        assertEquals(adminEmail, toEmail)
        assertEquals(ControlMessageType.MEMBER_ADD_REQUEST, msg.type)
        assertEquals(bobEmail, msg.targetEmail)
        assertEquals(aliceEmail, msg.requesterEmail)

        val pending = pendingDao.get(chatId, bobEmail)
        assertNotNull("pending должен быть сохранён", pending)
        assertEquals(aliceEmail, pending!!.requesterEmail)
    }

    @Test(expected = IllegalArgumentException::class)
    fun requestAddMember_unverifiedTarget_throws() = runBlocking {
        val gm = adminGm()
        val chatId = gm.createGroup("Тест", listOf(1L), creatorEmail = adminEmail)
        runBlocking { contactDao.insert(verifiedContact(99L, adminEmail, "Admin")) }

        aliceGm().requestAddMember(chatId, 3L) // carol UNVERIFIED
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun requestAddMember_byAdminItself_throws() = runBlocking {
        val gm = adminGm()
        val chatId = gm.createGroup("Тест", listOf(1L), creatorEmail = adminEmail)
        // adminGm() как админ должен использовать addMember напрямую:
        gm.requestAddMember(chatId, 2L)
        Unit
    }

    @Test
    fun requestAddMember_adminContactMissing_returnsFalseAndCleansPending() = runBlocking {
        val gm = adminGm()
        val chatId = gm.createGroup("Тест", listOf(1L), creatorEmail = adminEmail)
        runBlocking { contactDao.insert(verifiedContact(99L, adminEmail, "Admin")) }

        // Имитируем что у транспорта admin недостижим (контакт не найден ИЛИ
        // ключ не валиден ИЛИ что угодно — sender вернул false).
        val aliceManager = aliceGm()
        sender.adminUnreachable.add(adminEmail.lowercase())

        val ok = aliceManager.requestAddMember(chatId, 2L)
        assertFalse(ok)
        assertNull("pending должен быть удалён после провала отправки",
            pendingDao.get(chatId, bobEmail))
    }

    @Test
    fun requestAddMember_alreadyInGroup_returnsFalseNoCtrl() = runBlocking {
        val gm = adminGm()
        // alice (id=1) сразу в группе. Просим её же добавить ещё раз — short-circuit.
        val chatId = gm.createGroup("Тест", listOf(1L), creatorEmail = adminEmail)
        runBlocking { contactDao.insert(verifiedContact(99L, adminEmail, "Admin")) }

        val ok = aliceGm().requestAddMember(chatId, 1L)
        assertFalse(ok)
        assertEquals(0, sender.adminControl.size)
    }

    // ── approveAddRequest ───────────────────────────────────────

    @Test
    fun approveAddRequest_admin_addsMemberAndSendsApproved() = runBlocking {
        val gm = adminGm()
        val chatId = gm.createGroup("Тест", listOf(1L), creatorEmail = adminEmail)

        // Имитируем что pending уже сохранён (как будто handler принял запрос)
        pendingDao.upsert(
            PendingAddRequestEntity(
                chatId = chatId,
                targetEmail = bobEmail,
                requesterEmail = aliceEmail,
                targetPublicKey = ByteArray(32) { 0xBB.toByte() },
                targetDisplayName = "Bob",
                createdAt = now
            )
        )

        val ok = gm.approveAddRequest(chatId, bobEmail)
        assertTrue(ok)

        // Bob должен оказаться в группе (контакт уже был, addMember найдёт по email)
        val members = chatDao.getMembersForChat(chatId)
        assertTrue("bob добавлен", members.any { it.contactId == 2L })

        // pending снят
        assertNull(pendingDao.get(chatId, bobEmail))

        // Был MEMBER_ADDED broadcast (sendControlToGroup) и MEMBER_ADD_APPROVED p2p
        assertTrue("MEMBER_ADDED broadcast", sender.groupControl.any { it.second.type == ControlMessageType.MEMBER_ADDED })
        val approved = sender.adminControl.lastOrNull()
        assertNotNull(approved)
        assertEquals(ControlMessageType.MEMBER_ADD_APPROVED, approved!!.second.type)
        assertEquals(aliceEmail, approved.first)
        assertEquals(bobEmail, approved.second.targetEmail)
        assertEquals(aliceEmail, approved.second.requesterEmail)
    }

    @Test(expected = IllegalArgumentException::class)
    fun approveAddRequest_byNonAdmin_throws() = runBlocking {
        val admin = adminGm()
        val chatId = admin.createGroup("Тест", listOf(1L), creatorEmail = adminEmail)
        pendingDao.upsert(
            PendingAddRequestEntity(
                chatId, bobEmail, aliceEmail,
                ByteArray(32), "Bob", now
            )
        )
        aliceGm().approveAddRequest(chatId, bobEmail)
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun approveAddRequest_noPending_throws() = runBlocking {
        val gm = adminGm()
        val chatId = gm.createGroup("Тест", listOf(1L), creatorEmail = adminEmail)
        gm.approveAddRequest(chatId, bobEmail)
        Unit
    }

    // ── rejectAddRequest ────────────────────────────────────────

    @Test
    fun rejectAddRequest_admin_deletesPendingAndSendsRejected() = runBlocking {
        val gm = adminGm()
        val chatId = gm.createGroup("Тест", listOf(1L), creatorEmail = adminEmail)
        pendingDao.upsert(
            PendingAddRequestEntity(
                chatId, bobEmail, aliceEmail,
                ByteArray(32), "Bob", now
            )
        )

        val ok = gm.rejectAddRequest(chatId, bobEmail)
        assertTrue(ok)
        assertNull(pendingDao.get(chatId, bobEmail))

        val rejected = sender.adminControl.lastOrNull()
        assertNotNull(rejected)
        assertEquals(ControlMessageType.MEMBER_ADD_REJECTED, rejected!!.second.type)
        assertEquals(aliceEmail, rejected.first)
        assertEquals(bobEmail, rejected.second.targetEmail)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectAddRequest_byNonAdmin_throws() = runBlocking {
        val admin = adminGm()
        val chatId = admin.createGroup("Тест", listOf(1L), creatorEmail = adminEmail)
        pendingDao.upsert(
            PendingAddRequestEntity(
                chatId, bobEmail, aliceEmail,
                ByteArray(32), "Bob", now
            )
        )
        aliceGm().rejectAddRequest(chatId, bobEmail)
        Unit
    }

    // ── PendingAddRequestDao behavior ───────────────────────────

    @Test
    fun pendingDao_upsert_replacesByCompositeKey() = runBlocking {
        val chatId = "c1"
        pendingDao.upsert(
            PendingAddRequestEntity(chatId, bobEmail, aliceEmail, ByteArray(32), "Bob old", now)
        )
        pendingDao.upsert(
            PendingAddRequestEntity(chatId, bobEmail, aliceEmail, ByteArray(32), "Bob new", now + 1_000)
        )
        val req = pendingDao.get(chatId, bobEmail)
        assertNotNull(req)
        assertEquals("Bob new", req!!.targetDisplayName)
        assertEquals(now + 1_000, req.createdAt)
        assertEquals(1, pendingDao.countForChat(chatId))
    }

    @Test
    fun pendingDao_deleteOlderThan_removesByTtl() = runBlocking {
        val chatId = "c1"
        pendingDao.upsert(
            PendingAddRequestEntity(chatId, "old@y.ru", aliceEmail, ByteArray(32), "Old", 100L)
        )
        pendingDao.upsert(
            PendingAddRequestEntity(chatId, "new@y.ru", aliceEmail, ByteArray(32), "New", 5_000L)
        )
        val removed = pendingDao.deleteOlderThan(1_000L)
        assertEquals(1, removed)
        assertNull(pendingDao.get(chatId, "old@y.ru"))
        assertNotNull(pendingDao.get(chatId, "new@y.ru"))
    }

    // ── Helpers ─────────────────────────────────────────────────

    private fun verifiedContact(id: Long, email: String, name: String) = ContactEntity(
        id = id, email = email, displayName = name,
        publicKey = ByteArray(32) { id.toByte() }, fingerprint = "fp$id",
        trustStatus = TrustStatus.VERIFIED, createdAt = now, updatedAt = now
    )

    private fun unverifiedContact(id: Long, email: String, name: String) = ContactEntity(
        id = id, email = email, displayName = name,
        publicKey = ByteArray(32) { id.toByte() }, fingerprint = "fp$id",
        trustStatus = TrustStatus.UNVERIFIED, createdAt = now, updatedAt = now
    )

    // ── Fakes ───────────────────────────────────────────────────

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
        override suspend fun getMembersForChat(chatId: String) =
            members.filter { it.chatId == chatId }
        override suspend fun deleteMember(chatId: String, contactId: Long) {
            members.removeAll { it.chatId == chatId && it.contactId == contactId }
        }
        override suspend fun update(chat: ChatEntity) { chats[chat.id] = chat }
        override suspend fun delete(chat: ChatEntity) { chats.remove(chat.id) }
        override suspend fun deleteById(chatId: String) {
            chats.remove(chatId)
            members.removeAll { it.chatId == chatId }
        }
    }

    private class FakeContactDao : ru.cheburmail.app.db.dao.ContactDao {
        val contacts = mutableMapOf<Long, ContactEntity>()
        var nextId = 100L
        override suspend fun insert(contact: ContactEntity): Long {
            val id = if (contact.id == 0L) nextId++ else contact.id
            contacts[id] = contact.copy(id = id)
            return id
        }
        override suspend fun getById(id: Long) = contacts[id]
        override suspend fun getByEmail(email: String) =
            contacts.values.find { it.email.equals(email, ignoreCase = true) }
        override fun getAll() = flowOf(contacts.values.toList())
        override suspend fun getAllOnce() = contacts.values.toList()
        override suspend fun update(contact: ContactEntity) { contacts[contact.id] = contact }
        override suspend fun delete(contact: ContactEntity) { contacts.remove(contact.id) }
        override suspend fun deleteById(id: Long) { contacts.remove(id) }
    }

    private class FakePendingAddRequestDao : ru.cheburmail.app.db.dao.PendingAddRequestDao {
        private val store = mutableMapOf<Pair<String, String>, PendingAddRequestEntity>()
        override suspend fun upsert(request: PendingAddRequestEntity) {
            store[request.chatId to request.targetEmail.lowercase()] = request
        }
        override fun observeForChat(chatId: String) = flowOf(
            store.values.filter { it.chatId == chatId }.sortedBy { it.createdAt }
        )
        override suspend fun getForChatOnce(chatId: String) =
            store.values.filter { it.chatId == chatId }.sortedBy { it.createdAt }
        override suspend fun get(chatId: String, targetEmail: String) =
            store[chatId to targetEmail.lowercase()]
        override suspend fun delete(chatId: String, targetEmail: String) {
            store.remove(chatId to targetEmail.lowercase())
        }
        override suspend fun deleteOlderThan(cutoff: Long): Int {
            val toRemove = store.filterValues { it.createdAt < cutoff }.keys.toList()
            toRemove.forEach { store.remove(it) }
            return toRemove.size
        }
        override suspend fun countForChat(chatId: String): Int =
            store.values.count { it.chatId == chatId }
    }

    private class FakeGroupMessageSender : GroupMessageSender(
        chatDao = StubChatDao(), contactDao = StubContactDao(),
        sendQueueDao = StubSendQueueDao(), encryptor = FakeEncryptor(),
        senderPrivateKey = ByteArray(32), senderEmail = "stub@x",
        messageDao = StubMessageDao()
    ) {
        val groupControl = mutableListOf<Pair<String, ControlMessage>>()
        val adminControl = mutableListOf<Pair<String, ControlMessage>>() // (recipient, msg)
        /** Если задан непустой набор — sendControlToAdmin вернёт false для этих email'ов. */
        val adminUnreachable = mutableSetOf<String>()

        override suspend fun sendControlToGroup(chatId: String, controlMessage: ControlMessage): Int {
            groupControl.add(chatId to controlMessage)
            return 0
        }
        override suspend fun sendControlToAdmin(
            chatId: String,
            recipientEmail: String,
            controlMessage: ControlMessage
        ): Boolean {
            if (recipientEmail.lowercase() in adminUnreachable) return false
            adminControl.add(recipientEmail to controlMessage)
            return true
        }
    }

    // Stubs для конструктора GroupMessageSender — не используются, т.к. мы
    // переопределяем sendControlTo* напрямую.
    private class StubChatDao : ru.cheburmail.app.db.dao.ChatDao {
        override suspend fun insert(chat: ChatEntity) {}
        override suspend fun getById(chatId: String): ChatEntity? = null
        override fun getAllWithLastMessage() = flowOf(emptyList<ChatWithLastMessage>())
        override suspend fun insertMember(member: ChatMemberEntity) {}
        override suspend fun getMembersForChat(chatId: String) = emptyList<ChatMemberEntity>()
        override suspend fun deleteMember(chatId: String, contactId: Long) {}
        override suspend fun update(chat: ChatEntity) {}
        override suspend fun delete(chat: ChatEntity) {}
        override suspend fun deleteById(chatId: String) {}
    }
    private class StubContactDao : ru.cheburmail.app.db.dao.ContactDao {
        override suspend fun insert(contact: ContactEntity): Long = 0
        override suspend fun getById(id: Long): ContactEntity? = null
        override suspend fun getByEmail(email: String): ContactEntity? = null
        override fun getAll() = flowOf(emptyList<ContactEntity>())
        override suspend fun getAllOnce() = emptyList<ContactEntity>()
        override suspend fun update(contact: ContactEntity) {}
        override suspend fun delete(contact: ContactEntity) {}
        override suspend fun deleteById(id: Long) {}
    }
    private class StubSendQueueDao : ru.cheburmail.app.db.dao.SendQueueDao {
        override suspend fun insert(item: SendQueueEntity): Long = 0
        override suspend fun getQueued(now: Long) = emptyList<SendQueueEntity>()
        override suspend fun getAll() = emptyList<SendQueueEntity>()
        override suspend fun getByMessageId(messageId: String) = emptyList<SendQueueEntity>()
        override suspend fun updateStatus(id: Long, status: QueueStatus, retryCount: Int?, nextRetryAt: Long?, updatedAt: Long) {}
        override suspend fun getRetryable(now: Long) = emptyList<SendQueueEntity>()
        override suspend fun deleteSent(): Int = 0
        override suspend fun countPending(): Int = 0
    }
    private class StubMessageDao : ru.cheburmail.app.db.dao.MessageDao {
        override suspend fun insert(message: MessageEntity) {}
        override suspend fun getById(id: String): MessageEntity? = null
        override fun getForChat(chatId: String) = flowOf(emptyList<MessageEntity>())
        override suspend fun updateStatus(messageId: String, status: MessageStatus) {}
        override suspend fun getByIdOnce(id: String): MessageEntity? = null
        override suspend fun deleteExpired(now: Long) = 0
        override suspend fun existsById(id: String) = false
        override suspend fun getAllOnce() = emptyList<MessageEntity>()
        override suspend fun getForChatOnce(chatId: String) = emptyList<MessageEntity>()
        override suspend fun markChatAsRead(chatId: String) {}
        override suspend fun deleteByChatId(chatId: String) {}
        override suspend fun deleteById(messageId: String) {}
        override suspend fun updateMedia(messageId: String, localUri: String?, thumbnailUri: String?, downloadStatus: MediaDownloadStatus) {}
        override suspend fun insertDeleted(deleted: DeletedMessageEntity) {}
        override suspend fun isDeleted(messageId: String) = false
    }

    private class FakeBoxNative : com.goterl.lazysodium.interfaces.Box.Native {
        override fun cryptoBoxEasy(c: ByteArray, m: ByteArray, mLen: Long, n: ByteArray, pk: ByteArray, sk: ByteArray) = true
        override fun cryptoBoxOpenEasy(m: ByteArray, c: ByteArray, cLen: Long, n: ByteArray, pk: ByteArray, sk: ByteArray) = true
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
    private class FakeEncryptor : ru.cheburmail.app.crypto.MessageEncryptor(
        box = FakeBoxNative(),
        nonceGenerator = ru.cheburmail.app.crypto.NonceGenerator(FakeRandomNative())
    )
}
