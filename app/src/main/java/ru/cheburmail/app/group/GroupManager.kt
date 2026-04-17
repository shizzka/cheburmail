package ru.cheburmail.app.group

import android.util.Log
import ru.cheburmail.app.db.ChatType
import ru.cheburmail.app.db.dao.ChatDao
import ru.cheburmail.app.db.dao.ContactDao
import ru.cheburmail.app.db.entity.ChatEntity
import ru.cheburmail.app.db.entity.ChatMemberEntity
import java.util.UUID

/**
 * Управление групповыми чатами.
 *
 * Ограничения:
 * - Максимум 10 участников в группе (MAX_GROUP_SIZE)
 * - Создатель группы автоматически добавляется как участник
 * - При удалении последнего участника группа не удаляется (остаётся пустой)
 */
class GroupManager(
    private val chatDao: ChatDao,
    private val contactDao: ContactDao,
    private val groupMessageSender: GroupMessageSender
) {

    /**
     * Создать новый групповой чат.
     *
     * @param name название группы
     * @param memberContactIds ID контактов-участников (без создателя)
     * @return ID созданного чата
     * @throws IllegalArgumentException если участников больше MAX_GROUP_SIZE
     */
    suspend fun createGroup(name: String, memberContactIds: List<Long>): String {
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
            updatedAt = now
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

    companion object {
        private const val TAG = "GroupManager"
        const val MAX_GROUP_SIZE = 10
    }
}
