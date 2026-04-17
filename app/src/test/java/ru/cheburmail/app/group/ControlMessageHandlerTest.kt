package ru.cheburmail.app.group

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.cheburmail.app.db.ChatType
import ru.cheburmail.app.db.ChatWithLastMessage
import ru.cheburmail.app.db.TrustStatus
import ru.cheburmail.app.db.entity.ChatEntity
import ru.cheburmail.app.db.entity.ChatMemberEntity
import ru.cheburmail.app.db.entity.ContactEntity
import ru.cheburmail.app.db.entity.PendingAddRequestEntity

/**
 * Тесты гейтинга ControlMessageHandler для approval-флоу.
 *
 * selfEmail = me@y.ru (admin группы 'g1')
 * alice@y.ru (verified, член группы) — легитимный requester
 * mallory@y.ru (verified, НЕ член группы) — пытается слать запросы
 * carol@y.ru (UNVERIFIED, член группы) — не должна проходить как requester
 * bob@y.ru — кандидат на добавление
 */
class ControlMessageHandlerTest {

    private lateinit var chatDao: FakeChatDao
    private lateinit var contactDao: FakeContactDao
    private lateinit var pendingDao: FakePendingAddRequestDao
    private lateinit var handler: ControlMessageHandler

    private val me = "me@y.ru"
    private val alice = "alice@y.ru"
    private val mallory = "mallory@y.ru"
    private val carol = "carol@y.ru"
    private val bob = "bob@y.ru"
    private val chatId = "g1"
    private val now = 1_700_000_000_000L

    @Before
    fun setup() {
        chatDao = FakeChatDao()
        contactDao = FakeContactDao()
        pendingDao = FakePendingAddRequestDao()

        runBlocking {
            chatDao.insert(
                ChatEntity(
                    id = chatId, type = ChatType.GROUP, title = "Group",
                    createdAt = now, updatedAt = now, createdBy = me
                )
            )
            // alice — verified + в группе
            val aliceId = contactDao.insert(verified(0L, alice, "Alice"))
            chatDao.insertMember(ChatMemberEntity(chatId, aliceId, now))
            // mallory — verified, НЕ в группе
            contactDao.insert(verified(0L, mallory, "Mallory"))
            // carol — UNVERIFIED, в группе
            val carolId = contactDao.insert(unverified(0L, carol, "Carol"))
            chatDao.insertMember(ChatMemberEntity(chatId, carolId, now))
        }

        handler = ControlMessageHandler(
            chatDao = chatDao,
            contactDao = contactDao,
            selfEmail = me,
            keyStorage = null,
            pendingAddRequestDao = pendingDao
        )
    }

    private fun addRequestMsg(
        target: String = bob,
        requester: String = alice,
        targetPublicKey: ByteArray = ByteArray(32) { 0xAA.toByte() }
    ) = ControlMessage(
        type = ControlMessageType.MEMBER_ADD_REQUEST,
        chatId = chatId,
        groupName = "Group",
        members = listOf(
            GroupMemberInfo(
                email = target,
                publicKey = java.util.Base64.getEncoder().encodeToString(targetPublicKey),
                displayName = "Bob"
            )
        ),
        targetEmail = target,
        requesterEmail = requester
    ).toJson()

    // ── happy path ──────────────────────────────────────────────

    @Test
    fun memberAddRequest_fromVerifiedMember_savesPending() = runBlocking {
        val ok = handler.handle(addRequestMsg(), fromEmail = alice)
        assertTrue(ok)
        val saved = pendingDao.get(chatId, bob)
        assertNotNull(saved)
        assertEquals(alice, saved!!.requesterEmail)
        assertEquals("Bob", saved.targetDisplayName)
    }

    // ── reject paths ────────────────────────────────────────────

    @Test
    fun memberAddRequest_requesterMismatchFromEmail_ignored() = runBlocking {
        // requesterEmail в JSON = alice, но envelope-from = mallory → подделка
        val ok = handler.handle(addRequestMsg(requester = alice), fromEmail = mallory)
        assertTrue("handle сам по себе не падает (true), но pending не пишется", ok)
        assertNull(pendingDao.get(chatId, bob))
    }

    @Test
    fun memberAddRequest_iAmNotAdmin_ignored() = runBlocking {
        // Перемещаем admin'а на alice → me перестаёт быть admin'ом
        val chat = chatDao.getById(chatId)!!
        chatDao.update(chat.copy(createdBy = alice))

        handler.handle(addRequestMsg(), fromEmail = alice)
        assertNull(pendingDao.get(chatId, bob))
    }

    @Test
    fun memberAddRequest_requesterNotInContacts_ignored() = runBlocking {
        // mallory verified, но в группе её нет; мы проверяем "не в контактах" —
        // удалим mallory из контактов и пошлём от mallory
        val mal = contactDao.getByEmail(mallory)!!
        contactDao.delete(mal)
        handler.handle(addRequestMsg(requester = mallory), fromEmail = mallory)
        assertNull(pendingDao.get(chatId, bob))
    }

    @Test
    fun memberAddRequest_requesterUnverified_ignored() = runBlocking {
        // carol — UNVERIFIED участник, шлёт запрос → отклонить
        handler.handle(addRequestMsg(requester = carol), fromEmail = carol)
        assertNull(pendingDao.get(chatId, bob))
    }

    @Test
    fun memberAddRequest_requesterNotInGroup_ignored() = runBlocking {
        // mallory verified, но не член группы → запрос должен быть отвергнут
        handler.handle(addRequestMsg(requester = mallory), fromEmail = mallory)
        assertNull(pendingDao.get(chatId, bob))
    }

    @Test
    fun memberAddRequest_targetAlreadyInGroup_ignored() = runBlocking {
        // bob уже в группе как контакт — добавим вручную
        val bobId = contactDao.insert(verified(0L, bob, "Bob"))
        chatDao.insertMember(ChatMemberEntity(chatId, bobId, now))

        handler.handle(addRequestMsg(target = bob), fromEmail = alice)
        assertNull(pendingDao.get(chatId, bob))
    }

    @Test
    fun memberAddRequest_invalidBase64_ignored() = runBlocking {
        // Custom JSON c кривым ключом
        val msg = ControlMessage(
            type = ControlMessageType.MEMBER_ADD_REQUEST,
            chatId = chatId,
            groupName = "Group",
            members = listOf(
                GroupMemberInfo(email = bob, publicKey = "!!! not base64 !!!", displayName = "Bob")
            ),
            targetEmail = bob,
            requesterEmail = alice
        ).toJson()
        handler.handle(msg, fromEmail = alice)
        assertNull(pendingDao.get(chatId, bob))
    }

    // ── approved/rejected ───────────────────────────────────────

    @Test
    fun memberAddApproved_fromAdminForMe_clearsPending() = runBlocking {
        // У me был свой pending как requester (моделируем)
        pendingDao.upsert(
            PendingAddRequestEntity(chatId, bob, me, ByteArray(32), "Bob", now)
        )
        val msg = ControlMessage(
            type = ControlMessageType.MEMBER_ADD_APPROVED,
            chatId = chatId, groupName = "Group", members = emptyList(),
            targetEmail = bob, requesterEmail = me
        ).toJson()
        // chat.createdBy = me, поэтому fromEmail = me пройдёт admin-check
        handler.handle(msg, fromEmail = me)
        assertNull(pendingDao.get(chatId, bob))
    }

    @Test
    fun memberAddApproved_fromNonAdmin_ignored() = runBlocking {
        pendingDao.upsert(
            PendingAddRequestEntity(chatId, bob, me, ByteArray(32), "Bob", now)
        )
        val msg = ControlMessage(
            type = ControlMessageType.MEMBER_ADD_APPROVED,
            chatId = chatId, groupName = "Group", members = emptyList(),
            targetEmail = bob, requesterEmail = me
        ).toJson()
        // mallory не admin
        handler.handle(msg, fromEmail = mallory)
        assertNotNull("pending должен остаться (approved отброшен)",
            pendingDao.get(chatId, bob))
    }

    @Test
    fun memberAddApproved_notForMe_ignored() = runBlocking {
        // pending принадлежит alice; APPROVED для alice должен НЕ трогать его у me
        pendingDao.upsert(
            PendingAddRequestEntity(chatId, bob, alice, ByteArray(32), "Bob", now)
        )
        val msg = ControlMessage(
            type = ControlMessageType.MEMBER_ADD_APPROVED,
            chatId = chatId, groupName = "Group", members = emptyList(),
            targetEmail = bob, requesterEmail = alice
        ).toJson()
        handler.handle(msg, fromEmail = me)
        assertNotNull(pendingDao.get(chatId, bob))
    }

    @Test
    fun memberAddRejected_fromAdminForMe_clearsPending() = runBlocking {
        pendingDao.upsert(
            PendingAddRequestEntity(chatId, bob, me, ByteArray(32), "Bob", now)
        )
        val msg = ControlMessage(
            type = ControlMessageType.MEMBER_ADD_REJECTED,
            chatId = chatId, groupName = "Group", members = emptyList(),
            targetEmail = bob, requesterEmail = me
        ).toJson()
        handler.handle(msg, fromEmail = me)
        assertNull(pendingDao.get(chatId, bob))
    }

    // ── helpers ─────────────────────────────────────────────────

    private fun verified(id: Long, email: String, name: String) = ContactEntity(
        id = id, email = email, displayName = name,
        publicKey = ByteArray(32) { (id and 0xFF).toByte() }, fingerprint = "fp",
        trustStatus = TrustStatus.VERIFIED, createdAt = now, updatedAt = now
    )

    private fun unverified(id: Long, email: String, name: String) = ContactEntity(
        id = id, email = email, displayName = name,
        publicKey = ByteArray(32) { (id and 0xFF).toByte() }, fingerprint = "fp",
        trustStatus = TrustStatus.UNVERIFIED, createdAt = now, updatedAt = now
    )

    // ── Fakes ───────────────────────────────────────────────────

    private class FakeChatDao : ru.cheburmail.app.db.dao.ChatDao {
        val chats = mutableMapOf<String, ChatEntity>()
        val members = mutableListOf<ChatMemberEntity>()
        override suspend fun insert(chat: ChatEntity) { chats[chat.id] = chat }
        override suspend fun getById(chatId: String) = chats[chatId]
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
            chats.remove(chatId); members.removeAll { it.chatId == chatId }
        }
    }

    private class FakeContactDao : ru.cheburmail.app.db.dao.ContactDao {
        val contacts = mutableMapOf<Long, ContactEntity>()
        var nextId = 1L
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
            val rem = store.filterValues { it.createdAt < cutoff }.keys.toList()
            rem.forEach { store.remove(it) }
            return rem.size
        }
        override suspend fun countForChat(chatId: String) =
            store.values.count { it.chatId == chatId }
    }
}
