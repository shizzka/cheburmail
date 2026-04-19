package ru.cheburmail.app.group

import android.util.Log
import ru.cheburmail.app.db.ChatType
import ru.cheburmail.app.db.TrustStatus
import ru.cheburmail.app.db.dao.ChatDao
import ru.cheburmail.app.db.dao.ContactDao
import ru.cheburmail.app.db.dao.PendingAddRequestDao
import ru.cheburmail.app.db.entity.ChatEntity
import ru.cheburmail.app.db.entity.ChatMemberEntity
import ru.cheburmail.app.db.entity.PendingAddRequestEntity
import java.util.UUID

/**
 * Управление групповыми чатами.
 *
 * Ограничения:
 * - Максимум 10 участников в группе (MAX_GROUP_SIZE)
 * - Создатель группы автоматически добавляется как участник
 * - При удалении последнего участника группа не удаляется (остаётся пустой)
 *
 * Approval-флоу добавления участников (созданный после v8 миграции):
 * - Админ (chat.created_by == self) — может вызывать addMember напрямую.
 * - Verified-не-админ — вызывает requestAddMember, который шлёт
 *   MEMBER_ADD_REQUEST p2p админу. Админ через approveAddRequest()
 *   фактически добавляет участника (и всем broadcast'ит MEMBER_ADDED) +
 *   подтверждает p2p автору запроса MEMBER_ADD_APPROVED. Либо
 *   rejectAddRequest() → MEMBER_ADD_REJECTED.
 *
 * pendingAddRequestDao и selfEmail nullable для бэк-совместимости с
 * существующими тестами GroupManagerTest, которые не используют approval.
 */
class GroupManager(
    private val chatDao: ChatDao,
    private val contactDao: ContactDao,
    private val groupMessageSender: GroupMessageSender,
    private val pendingAddRequestDao: PendingAddRequestDao? = null,
    private val selfEmail: String? = null
) {

    /**
     * Создать новый групповой чат.
     *
     * @param name название группы
     * @param memberContactIds ID контактов-участников (без создателя)
     * @param creatorEmail email создателя (admin). Если null — старое поведение
     *   без admin-роли (для бэк-совместимости тестов). В UI всегда передаём myEmail.
     * @return ID созданного чата
     * @throws IllegalArgumentException если участников больше MAX_GROUP_SIZE
     */
    suspend fun createGroup(
        name: String,
        memberContactIds: List<Long>,
        creatorEmail: String? = null
    ): String {
        require(memberContactIds.size + 1 <= MAX_GROUP_SIZE) {
            "Максимум $MAX_GROUP_SIZE участников в группе"
        }
        require(name.isNotBlank()) {
            "Название группы не может быть пустым"
        }

        val chatId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val chat = ChatEntity(
            id = chatId,
            type = ChatType.GROUP,
            title = name,
            createdAt = now,
            updatedAt = now,
            createdBy = creatorEmail
        )
        chatDao.insert(chat)

        // Добавить участников
        for (contactId in memberContactIds) {
            val member = ChatMemberEntity(
                chatId = chatId,
                contactId = contactId,
                joinedAt = now
            )
            chatDao.insertMember(member)
        }

        Log.i(TAG, "Группа '$name' создана: chatId=$chatId, участников=${memberContactIds.size}")
        return chatId
    }

    /**
     * Отправить приглашение GROUP_INVITE всем участникам группы.
     * Каждый участник получает зашифрованное управляющее сообщение
     * со списком всех участников и их публичных ключей.
     *
     * Создатель (self) включается в список members, чтобы получатели
     * могли добавить его в chat_members и отправлять ему сообщения.
     *
     * @param chatId ID группового чата
     * @param selfEmail email создателя группы
     * @param selfPublicKey 32-байтный публичный ключ создателя
     * @param selfDisplayName отображаемое имя создателя (для UI получателей)
     */
    suspend fun sendGroupInvite(
        chatId: String,
        selfEmail: String,
        selfPublicKey: ByteArray,
        selfDisplayName: String
    ) {
        val chat = chatDao.getById(chatId)
            ?: throw IllegalArgumentException("Чат $chatId не найден")

        val members = chatDao.getMembersForChat(chatId)
        val memberInfos = members.mapNotNull { member ->
            val contact = contactDao.getById(member.contactId) ?: return@mapNotNull null
            GroupMemberInfo(
                email = contact.email,
                publicKey = java.util.Base64.getEncoder().encodeToString(contact.publicKey),
                displayName = contact.displayName
            )
        } + GroupMemberInfo(
            email = selfEmail,
            publicKey = java.util.Base64.getEncoder().encodeToString(selfPublicKey),
            displayName = selfDisplayName
        )

        val controlMsg = ControlMessage(
            type = ControlMessageType.GROUP_INVITE,
            chatId = chatId,
            groupName = chat.title ?: "Группа",
            members = memberInfos
        )

        groupMessageSender.sendControlToGroup(chatId, controlMsg)
        Log.i(TAG, "GROUP_INVITE отправлен: chatId=$chatId, участников (с self)=${memberInfos.size}")
    }

    /**
     * Добавить участника в группу.
     *
     * @param chatId ID группового чата
     * @param contactId ID контакта для добавления
     * @throws IllegalArgumentException если группа полна или контакт не найден
     */
    suspend fun addMember(chatId: String, contactId: Long) {
        val chat = chatDao.getById(chatId)
            ?: throw IllegalArgumentException("Чат $chatId не найден")
        require(chat.type == ChatType.GROUP) { "Чат $chatId не является групповым" }

        val existingMembers = chatDao.getMembersForChat(chatId)
        require(existingMembers.size < MAX_GROUP_SIZE) {
            "Группа уже содержит максимум $MAX_GROUP_SIZE участников"
        }

        val contact = contactDao.getById(contactId)
            ?: throw IllegalArgumentException("Контакт $contactId не найден")

        // Проверка дубликата
        if (existingMembers.any { it.contactId == contactId }) {
            Log.w(TAG, "Контакт $contactId уже в группе $chatId")
            return
        }

        val now = System.currentTimeMillis()
        chatDao.insertMember(ChatMemberEntity(chatId, contactId, now))

        // Отправить уведомление MEMBER_ADDED
        val allMembers = chatDao.getMembersForChat(chatId)
        val memberInfos = allMembers.mapNotNull { member ->
            val c = contactDao.getById(member.contactId) ?: return@mapNotNull null
            GroupMemberInfo(
                email = c.email,
                publicKey = java.util.Base64.getEncoder().encodeToString(c.publicKey),
                displayName = c.displayName
            )
        }

        val controlMsg = ControlMessage(
            type = ControlMessageType.MEMBER_ADDED,
            chatId = chatId,
            groupName = chat.title ?: "Группа",
            members = memberInfos,
            targetEmail = contact.email
        )

        groupMessageSender.sendControlToGroup(chatId, controlMsg)
        Log.i(TAG, "Участник ${contact.email} добавлен в группу $chatId")
    }

    /**
     * Удалить участника из группы.
     *
     * @param chatId ID группового чата
     * @param contactId ID контакта для удаления
     */
    suspend fun removeMember(chatId: String, contactId: Long) {
        val chat = chatDao.getById(chatId)
            ?: throw IllegalArgumentException("Чат $chatId не найден")
        require(chat.type == ChatType.GROUP) { "Чат $chatId не является групповым" }

        val contact = contactDao.getById(contactId)
            ?: throw IllegalArgumentException("Контакт $contactId не найден")

        // Удаляем участника (через DAO нет прямого метода удаления member,
        // используем insert с IGNORE — здесь нужно добавить deleteMember в ChatDao)
        // Пока: отправляем уведомление, а удаление произойдёт при обработке
        val remainingMembers = chatDao.getMembersForChat(chatId)
            .filter { it.contactId != contactId }

        val memberInfos = remainingMembers.mapNotNull { member ->
            val c = contactDao.getById(member.contactId) ?: return@mapNotNull null
            GroupMemberInfo(
                email = c.email,
                publicKey = java.util.Base64.getEncoder().encodeToString(c.publicKey),
                displayName = c.displayName
            )
        }

        val controlMsg = ControlMessage(
            type = ControlMessageType.MEMBER_REMOVED,
            chatId = chatId,
            groupName = chat.title ?: "Группа",
            members = memberInfos,
            targetEmail = contact.email
        )

        // Отправить уведомление ВСЕМ текущим участникам (включая удаляемого),
        // чтобы удалённый тоже узнал о выходе из группы.
        groupMessageSender.sendControlToGroup(chatId, controlMsg)

        // Удалить локально
        chatDao.deleteMember(chatId, contactId)
        Log.i(TAG, "Участник ${contact.email} удалён из группы $chatId")
    }

    /**
     * Запросить добавление участника в группу (для verified-не-админа).
     *
     * Сохраняет PendingAddRequest **локально у requester'а** (для UI индикации
     * "запрос отправлен") и шлёт MEMBER_ADD_REQUEST p2p админу группы.
     * Админ при получении сам сохранит копию запроса и покажет его в UI.
     *
     * @throws IllegalStateException если approval не сконфигурирован
     *   (нет pendingAddRequestDao/selfEmail), нет admin'а в чате, чат не group
     * @throws IllegalArgumentException если контакт не verified, не найден,
     *   уже в группе, группа полна
     */
    suspend fun requestAddMember(chatId: String, contactId: Long): Boolean {
        val dao = pendingAddRequestDao
            ?: error("requestAddMember: pendingAddRequestDao не сконфигурирован")
        val me = selfEmail
            ?: error("requestAddMember: selfEmail не сконфигурирован")

        val chat = chatDao.getById(chatId)
            ?: throw IllegalArgumentException("Чат $chatId не найден")
        require(chat.type == ChatType.GROUP) { "Чат $chatId не является групповым" }
        val admin = chat.createdBy
            ?: throw IllegalStateException("В группе $chatId нет admin'а (created_by NULL)")
        require(!admin.equals(me, ignoreCase = true)) {
            "Сам admin должен использовать addMember напрямую, а не requestAddMember"
        }

        val target = contactDao.getById(contactId)
            ?: throw IllegalArgumentException("Контакт $contactId не найден")
        require(target.trustStatus == TrustStatus.VERIFIED) {
            "Только verified-контакт может быть предложен к добавлению"
        }

        val existingMembers = chatDao.getMembersForChat(chatId)
        require(existingMembers.size < MAX_GROUP_SIZE) {
            "Группа уже содержит максимум $MAX_GROUP_SIZE участников"
        }
        if (existingMembers.any { it.contactId == contactId }) {
            Log.w(TAG, "Контакт $contactId уже в группе $chatId — запрос не нужен")
            return false
        }

        val now = System.currentTimeMillis()

        // Локальная копия запроса (REPLACE дедупит повторные клики)
        dao.upsert(
            PendingAddRequestEntity(
                chatId = chatId,
                targetEmail = target.email,
                requesterEmail = me,
                targetPublicKey = target.publicKey,
                targetDisplayName = target.displayName,
                createdAt = now
            )
        )

        // Сообщение админу — несём publicKey + displayName, чтобы админу
        // не пришлось доустанавливать контакт через keyex.
        val msg = ControlMessage(
            type = ControlMessageType.MEMBER_ADD_REQUEST,
            chatId = chatId,
            groupName = chat.title ?: "Группа",
            members = listOf(
                GroupMemberInfo(
                    email = target.email,
                    publicKey = java.util.Base64.getEncoder().encodeToString(target.publicKey),
                    displayName = target.displayName
                )
            ),
            targetEmail = target.email,
            requesterEmail = me
        )

        val sent = groupMessageSender.sendControlToAdmin(chatId, admin, msg)
        if (!sent) {
            Log.e(TAG, "MEMBER_ADD_REQUEST не отправлен (admin=$admin контакт?), убираем pending")
            dao.delete(chatId, target.email)
            return false
        }
        Log.i(TAG, "MEMBER_ADD_REQUEST: chat=$chatId target=${target.email} → admin=$admin")
        return true
    }

    /**
     * Одобрить запрос на добавление участника (только для admin'а группы).
     *
     * Берёт сохранённый PendingAddRequest, материализует контакт у себя
     * (UNVERIFIED — admin сам не верифицировал, лишь поверил автору запроса),
     * добавляет в чат через addMember (broadcast MEMBER_ADDED), удаляет
     * pending, шлёт p2p MEMBER_ADD_APPROVED автору запроса.
     */
    suspend fun approveAddRequest(chatId: String, targetEmail: String): Boolean {
        val dao = pendingAddRequestDao
            ?: error("approveAddRequest: pendingAddRequestDao не сконфигурирован")
        val me = selfEmail
            ?: error("approveAddRequest: selfEmail не сконфигурирован")

        val chat = chatDao.getById(chatId)
            ?: throw IllegalArgumentException("Чат $chatId не найден")
        require(chat.createdBy?.equals(me, ignoreCase = true) == true) {
            "Только admin (${chat.createdBy}) может одобрять запросы"
        }

        val req = dao.get(chatId, targetEmail)
            ?: throw IllegalArgumentException("Запрос на $targetEmail в $chatId не найден")

        val now = System.currentTimeMillis()

        // Материализуем контакт если его ещё нет — используем pubkey/displayName
        // из самого запроса (admin доверяет автору запроса, который verified).
        var contact = contactDao.getByEmail(targetEmail)
        if (contact == null) {
            contact = ru.cheburmail.app.db.entity.ContactEntity(
                email = req.targetEmail,
                displayName = req.targetDisplayName,
                publicKey = req.targetPublicKey,
                fingerprint = "",
                trustStatus = TrustStatus.UNVERIFIED,
                createdAt = now,
                updatedAt = now
            )
            val id = contactDao.insert(contact)
            contact = contact.copy(id = id)
            Log.i(TAG, "approveAddRequest: создан UNVERIFIED контакт $targetEmail из запроса")
        }

        // addMember сам разошлёт MEMBER_ADDED всем (включая requester'а)
        addMember(chatId, contact.id)

        dao.delete(chatId, targetEmail)

        // P2P подтверждение автору запроса (UI у него снимет "ждём админа")
        val approved = ControlMessage(
            type = ControlMessageType.MEMBER_ADD_APPROVED,
            chatId = chatId,
            groupName = chat.title ?: "Группа",
            members = emptyList(),
            targetEmail = targetEmail,
            requesterEmail = req.requesterEmail
        )
        groupMessageSender.sendControlToAdmin(chatId, req.requesterEmail, approved)

        Log.i(TAG, "approveAddRequest: chat=$chatId target=$targetEmail requester=${req.requesterEmail}")
        return true
    }

    /**
     * Отклонить запрос на добавление участника (только для admin'а).
     */
    suspend fun rejectAddRequest(chatId: String, targetEmail: String): Boolean {
        val dao = pendingAddRequestDao
            ?: error("rejectAddRequest: pendingAddRequestDao не сконфигурирован")
        val me = selfEmail
            ?: error("rejectAddRequest: selfEmail не сконфигурирован")

        val chat = chatDao.getById(chatId)
            ?: throw IllegalArgumentException("Чат $chatId не найден")
        require(chat.createdBy?.equals(me, ignoreCase = true) == true) {
            "Только admin (${chat.createdBy}) может отклонять запросы"
        }

        val req = dao.get(chatId, targetEmail)
            ?: throw IllegalArgumentException("Запрос на $targetEmail в $chatId не найден")

        dao.delete(chatId, targetEmail)

        val rejected = ControlMessage(
            type = ControlMessageType.MEMBER_ADD_REJECTED,
            chatId = chatId,
            groupName = chat.title ?: "Группа",
            members = emptyList(),
            targetEmail = targetEmail,
            requesterEmail = req.requesterEmail
        )
        groupMessageSender.sendControlToAdmin(chatId, req.requesterEmail, rejected)

        Log.i(TAG, "rejectAddRequest: chat=$chatId target=$targetEmail requester=${req.requesterEmail}")
        return true
    }

    companion object {
        private const val TAG = "GroupManager"
        const val MAX_GROUP_SIZE = 10
    }
}
