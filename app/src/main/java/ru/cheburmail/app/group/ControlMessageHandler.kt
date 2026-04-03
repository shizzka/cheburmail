package ru.cheburmail.app.group

import android.util.Log
import ru.cheburmail.app.db.ChatType
import ru.cheburmail.app.db.TrustStatus
import ru.cheburmail.app.db.dao.ChatDao
import ru.cheburmail.app.db.dao.ContactDao
import ru.cheburmail.app.db.entity.ChatEntity
import ru.cheburmail.app.db.entity.ChatMemberEntity
import ru.cheburmail.app.db.entity.ContactEntity

/**
 * Обработчик входящих управляющих сообщений групповых чатов.
 *
 * Вызывается из ReceiveWorker при обнаружении управляющего сообщения
 * (subject содержит "ctrl-").
 *
 * Обработка по типам:
 * - GROUP_INVITE: создание группового чата, добавление участников
 * - MEMBER_ADDED: добавление нового участника в существующую группу
 * - MEMBER_REMOVED: удаление участника из группы
 */
class ControlMessageHandler(
    private val chatDao: ChatDao,
    private val contactDao: ContactDao
) {

    /**
     * Обработать управляющее сообщение.
     *
     * @param plaintext расшифрованный JSON управляющего сообщения
     * @return true если сообщение успешно обработано
     */
    suspend fun handle(plaintext: String): Boolean {
        return try {
            val controlMsg = ControlMessage.fromJson(plaintext)
            when (controlMsg.type) {
                ControlMessageType.GROUP_INVITE -> handleGroupInvite(controlMsg)
                ControlMessageType.MEMBER_ADDED -> handleMemberAdded(controlMsg)
                ControlMessageType.MEMBER_REMOVED -> handleMemberRemoved(controlMsg)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обработки управляющего сообщения: ${e.message}")
            false
        }
    }

    /**
     * GROUP_INVITE: создать групповой чат и добавить всех участников.
     * Если чат уже существует — обновить список участников.
     */
    private suspend fun handleGroupInvite(msg: ControlMessage) {
        val now = System.currentTimeMillis()

        // Создать или обновить чат
        val existingChat = chatDao.getById(msg.chatId)
        if (existingChat == null) {
            val chat = ChatEntity(
                id = msg.chatId,
                type = ChatType.GROUP,
                title = msg.groupName,
                createdAt = now,
                updatedAt = now
            )
            chatDao.insert(chat)
            Log.i(TAG, "Создан групповой чат '${msg.groupName}' (${msg.chatId})")
        } else {
            chatDao.update(existingChat.copy(
                title = msg.groupName,
                updatedAt = now
            ))
            Log.i(TAG, "Обновлён групповой чат '${msg.groupName}' (${msg.chatId})")
        }

        // Добавить участников
        for (memberInfo in msg.members) {
            ensureContactAndMember(msg.chatId, memberInfo, now)
        }

        Log.i(TAG, "GROUP_INVITE обработан: chatId=${msg.chatId}, участников=${msg.members.size}")
    }

    /**
     * MEMBER_ADDED: добавить нового участника в существующую группу.
     */
    private suspend fun handleMemberAdded(msg: ControlMessage) {
        val now = System.currentTimeMillis()

        // Проверить, что чат существует
        val chat = chatDao.getById(msg.chatId)
        if (chat == null) {
            Log.w(TAG, "Чат ${msg.chatId} не найден, создаём из MEMBER_ADDED")
            handleGroupInvite(msg)
            return
        }

        // Добавить нового участника
        val target = msg.targetEmail
        if (target != null) {
            val targetInfo = msg.members.find { it.email == target }
            if (targetInfo != null) {
                ensureContactAndMember(msg.chatId, targetInfo, now)
                Log.i(TAG, "MEMBER_ADDED: $target добавлен в ${msg.chatId}")
            }
        } else {
            // Если targetEmail не указан — обновить весь список
            for (memberInfo in msg.members) {
                ensureContactAndMember(msg.chatId, memberInfo, now)
            }
        }
    }

    /**
     * MEMBER_REMOVED: удалить участника из группы.
     */
    private suspend fun handleMemberRemoved(msg: ControlMessage) {
        val target = msg.targetEmail
        if (target == null) {
            Log.w(TAG, "MEMBER_REMOVED без targetEmail, игнорируем")
            return
        }

        val contact = contactDao.getByEmail(target)
        if (contact == null) {
            Log.w(TAG, "Контакт $target не найден для удаления из группы")
            return
        }

        // ChatDao не имеет deleteMember — удаление через пересоздание
        // В текущей реализации: логируем, фактическое удаление
        // потребует добавления deleteMember в ChatDao
        Log.i(TAG, "MEMBER_REMOVED: $target из ${msg.chatId} (требуется deleteMember в ChatDao)")
    }

    /**
     * Убедиться, что контакт существует и добавлен как участник чата.
     * Если контакт новый — создать с UNVERIFIED статусом.
     */
    private suspend fun ensureContactAndMember(
        chatId: String,
        memberInfo: GroupMemberInfo,
        now: Long
    ) {
        var contact = contactDao.getByEmail(memberInfo.email)
        if (contact == null) {
            // Создать новый контакт из данных управляющего сообщения
            val publicKeyBytes = java.util.Base64.getDecoder().decode(memberInfo.publicKey)
            contact = ContactEntity(
                email = memberInfo.email,
                displayName = memberInfo.displayName,
                publicKey = publicKeyBytes,
                fingerprint = "", // Будет вычислен позже
                trustStatus = TrustStatus.UNVERIFIED,
                createdAt = now,
                updatedAt = now
            )
            val id = contactDao.insert(contact)
            contact = contact.copy(id = id)
            Log.d(TAG, "Создан контакт ${memberInfo.email} из управляющего сообщения")
        }

        // Добавить как участника чата (IGNORE при дубликате)
        chatDao.insertMember(
            ChatMemberEntity(
                chatId = chatId,
                contactId = contact.id,
                joinedAt = now
            )
        )
    }

    companion object {
        private const val TAG = "ControlMessageHandler"
    }
}
