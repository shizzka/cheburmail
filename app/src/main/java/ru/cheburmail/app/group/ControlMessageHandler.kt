package ru.cheburmail.app.group

import android.util.Log
import ru.cheburmail.app.crypto.FingerprintGenerator
import ru.cheburmail.app.db.ChatType
import ru.cheburmail.app.db.TrustStatus
import ru.cheburmail.app.db.dao.ChatDao
import ru.cheburmail.app.db.dao.ContactDao
import ru.cheburmail.app.db.dao.PendingAddRequestDao
import ru.cheburmail.app.db.entity.ChatEntity
import ru.cheburmail.app.db.entity.ChatMemberEntity
import ru.cheburmail.app.db.entity.ContactEntity
import ru.cheburmail.app.db.entity.PendingAddRequestEntity
import ru.cheburmail.app.storage.SecureKeyStorage

/**
 * Обработчик входящих управляющих сообщений групповых чатов.
 *
 * Вызывается из ReceiveWorker при обнаружении управляющего сообщения
 * (subject содержит "ctrl-").
 *
 * Обработка по типам:
 * - GROUP_INVITE: создание группового чата, фиксация admin = fromEmail
 * - MEMBER_ADDED/MEMBER_REMOVED: только если fromEmail == chat.created_by
 * - MEMBER_ADD_REQUEST: только если fromEmail — verified-член группы
 * - MEMBER_ADD_APPROVED/REJECTED: только если fromEmail == chat.created_by
 */
class ControlMessageHandler(
    private val chatDao: ChatDao,
    private val contactDao: ContactDao,
    private val selfEmail: String = "",
    private val keyStorage: SecureKeyStorage? = null,
    private val pendingAddRequestDao: PendingAddRequestDao? = null
) {

    /**
     * Обработать управляющее сообщение.
     *
     * @param plaintext расшифрованный JSON управляющего сообщения
     * @param fromEmail email отправителя (из envelope, **доверяемое значение**
     *   после успешного decrypt — sender_authentic). Nullable для
     *   бэк-совместимости со старыми вызовами без проверки sender'а:
     *   admin/verified-проверки тогда пропускаются (с WARN).
     * @return true если сообщение успешно обработано
     */
    suspend fun handle(plaintext: String, fromEmail: String? = null): Boolean {
        return try {
            val controlMsg = ControlMessage.fromJson(plaintext)
            when (controlMsg.type) {
                ControlMessageType.GROUP_INVITE -> handleGroupInvite(controlMsg, fromEmail)
                ControlMessageType.MEMBER_ADDED -> handleMemberAdded(controlMsg, fromEmail)
                ControlMessageType.MEMBER_REMOVED -> handleMemberRemoved(controlMsg, fromEmail)
                ControlMessageType.MEMBER_ADD_REQUEST -> handleMemberAddRequest(controlMsg, fromEmail)
                ControlMessageType.MEMBER_ADD_APPROVED -> handleMemberAddApproved(controlMsg, fromEmail)
                ControlMessageType.MEMBER_ADD_REJECTED -> handleMemberAddRejected(controlMsg, fromEmail)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обработки управляющего сообщения: ${e.message}")
            false
        }
    }

    /**
     * Проверить, что fromEmail — текущий admin чата.
     * Возвращает true (без проверки), если fromEmail null или created_by null —
     * для бэк-совместимости с pre-v8 группами и старыми вызовами без fromEmail.
     */
    private fun isFromAdmin(chat: ChatEntity, fromEmail: String?): Boolean {
        if (fromEmail == null) return true // legacy путь
        val admin = chat.createdBy ?: return true // pre-v8 группа
        return admin.equals(fromEmail, ignoreCase = true)
    }

    /**
     * GROUP_INVITE: создать групповой чат и добавить всех участников.
     * Если чат уже существует — обновить список участников.
     *
     * fromEmail (если передан) фиксируется как admin (created_by) у получателя:
     * это единственный момент, когда мы доверяем "кто админ" — у первого invite.
     * Если чат уже существует с другим created_by — игнорируем чужой re-invite
     * (защита от попытки чужого админа подменить admin'а у нас).
     */
    private suspend fun handleGroupInvite(msg: ControlMessage, fromEmail: String? = null) {
        val now = System.currentTimeMillis()

        val existingChat = chatDao.getById(msg.chatId)
        if (existingChat == null) {
            val chat = ChatEntity(
                id = msg.chatId,
                type = ChatType.GROUP,
                title = msg.groupName,
                createdAt = now,
                updatedAt = now,
                createdBy = fromEmail
            )
            chatDao.insert(chat)
            Log.i(TAG, "Создан групповой чат '${msg.groupName}' (${msg.chatId}) admin=$fromEmail")
        } else {
            // Если fromEmail задан и created_by уже зафиксирован — invite должен
            // быть от него же. Иначе это попытка hijack admin-роли — игнорируем.
            if (fromEmail != null && existingChat.createdBy != null &&
                !existingChat.createdBy.equals(fromEmail, ignoreCase = true)) {
                Log.w(TAG, "GROUP_INVITE для ${msg.chatId} от $fromEmail, " +
                    "но admin = ${existingChat.createdBy}. Игнорируем.")
                return
            }
            // Пишем createdBy если он был NULL (pre-v8 группа получает admin'а)
            val newCreatedBy = existingChat.createdBy ?: fromEmail
            chatDao.update(existingChat.copy(
                title = msg.groupName,
                updatedAt = now,
                createdBy = newCreatedBy
            ))
            Log.i(TAG, "Обновлён групповой чат '${msg.groupName}' (${msg.chatId}) admin=$newCreatedBy")
        }

        for (memberInfo in msg.members) {
            ensureContactAndMember(msg.chatId, memberInfo, now)
        }

        Log.i(TAG, "GROUP_INVITE обработан: chatId=${msg.chatId}, участников=${msg.members.size}")
    }

    /**
     * MEMBER_ADDED: добавить нового участника в существующую группу.
     * Принимаем только от admin'а группы (chat.created_by). Чужие
     * MEMBER_ADDED игнорируются (защита от подмены состава).
     */
    private suspend fun handleMemberAdded(msg: ControlMessage, fromEmail: String? = null) {
        val now = System.currentTimeMillis()

        val chat = chatDao.getById(msg.chatId)
        if (chat == null) {
            Log.w(TAG, "Чат ${msg.chatId} не найден, создаём из MEMBER_ADDED admin=$fromEmail")
            handleGroupInvite(msg, fromEmail)
            return
        }

        if (!isFromAdmin(chat, fromEmail)) {
            Log.w(TAG, "MEMBER_ADDED от $fromEmail, но admin=${chat.createdBy}. Игнорируем.")
            return
        }

        val target = msg.targetEmail
        if (target != null) {
            val targetInfo = msg.members.find { it.email == target }
            if (targetInfo != null) {
                ensureContactAndMember(msg.chatId, targetInfo, now)
                Log.i(TAG, "MEMBER_ADDED: $target добавлен в ${msg.chatId}")
            }
        } else {
            for (memberInfo in msg.members) {
                ensureContactAndMember(msg.chatId, memberInfo, now)
            }
        }
    }

    /**
     * MEMBER_REMOVED: удалить участника из группы.
     * Только от admin'а. Случай "кикнули меня" обрабатываем до проверки
     * admin'а, чтобы кикнутый узнал даже если чат у него сломан.
     */
    private suspend fun handleMemberRemoved(msg: ControlMessage, fromEmail: String? = null) {
        val target = msg.targetEmail
        if (target == null) {
            Log.w(TAG, "MEMBER_REMOVED без targetEmail, игнорируем")
            return
        }

        // Если кикнули меня — удалить чат локально, чтобы не слать в пустоту.
        // FK CASCADE уберёт chat_members и messages.
        // Делаем до admin-проверки: даже если admin поменялся, кикнутый
        // должен узнать о выходе.
        if (selfEmail.isNotEmpty() && target.equals(selfEmail, ignoreCase = true)) {
            chatDao.deleteById(msg.chatId)
            Log.i(TAG, "MEMBER_REMOVED: меня ($selfEmail) удалили из ${msg.chatId} → чат удалён локально")
            return
        }

        val chat = chatDao.getById(msg.chatId)
        if (chat == null) {
            Log.w(TAG, "Чат ${msg.chatId} не найден для MEMBER_REMOVED")
            return
        }
        if (!isFromAdmin(chat, fromEmail)) {
            Log.w(TAG, "MEMBER_REMOVED от $fromEmail, но admin=${chat.createdBy}. Игнорируем.")
            return
        }

        val contact = contactDao.getByEmail(target)
        if (contact == null) {
            Log.w(TAG, "Контакт $target не найден для удаления из группы")
            return
        }

        chatDao.deleteMember(msg.chatId, contact.id)
        Log.i(TAG, "MEMBER_REMOVED: $target удалён из ${msg.chatId}")
    }

    /**
     * MEMBER_ADD_REQUEST: получен запрос на добавление участника.
     * Принимается только админом группы и только от verified-члена группы.
     * Сохраняется в pending_add_requests; UI у админа покажет approve/reject.
     */
    private suspend fun handleMemberAddRequest(msg: ControlMessage, fromEmail: String?) {
        val dao = pendingAddRequestDao
        if (dao == null) {
            Log.w(TAG, "MEMBER_ADD_REQUEST: pendingAddRequestDao не сконфигурирован, игнорируем")
            return
        }
        if (fromEmail == null) {
            Log.w(TAG, "MEMBER_ADD_REQUEST без fromEmail, игнорируем")
            return
        }
        val target = msg.targetEmail ?: run {
            Log.w(TAG, "MEMBER_ADD_REQUEST без targetEmail, игнорируем")
            return
        }
        val requester = msg.requesterEmail ?: run {
            Log.w(TAG, "MEMBER_ADD_REQUEST без requesterEmail, игнорируем")
            return
        }
        // requesterEmail должен совпадать с envelope-from (чтобы нельзя было
        // подделать запрос от чужого имени)
        if (!requester.equals(fromEmail, ignoreCase = true)) {
            Log.w(TAG, "MEMBER_ADD_REQUEST: requesterEmail=$requester != fromEmail=$fromEmail. Игнорируем.")
            return
        }

        val chat = chatDao.getById(msg.chatId)
        if (chat == null) {
            Log.w(TAG, "MEMBER_ADD_REQUEST: чат ${msg.chatId} не найден")
            return
        }
        // Только админ принимает запросы
        if (selfEmail.isEmpty() || !chat.createdBy.equals(selfEmail, ignoreCase = true)) {
            Log.w(TAG, "MEMBER_ADD_REQUEST: я ($selfEmail) не admin (${chat.createdBy}) для ${msg.chatId}")
            return
        }
        // Запросчик должен быть verified-членом группы у меня
        val requesterContact = contactDao.getByEmail(requester)
        if (requesterContact == null) {
            Log.w(TAG, "MEMBER_ADD_REQUEST: requester $requester не в моих контактах")
            return
        }
        if (requesterContact.trustStatus != TrustStatus.VERIFIED) {
            Log.w(TAG, "MEMBER_ADD_REQUEST: requester $requester не verified, игнорируем")
            return
        }
        val members = chatDao.getMembersForChat(msg.chatId)
        if (members.none { it.contactId == requesterContact.id }) {
            Log.w(TAG, "MEMBER_ADD_REQUEST: requester $requester не в составе группы ${msg.chatId}")
            return
        }

        // target не должен уже быть в группе
        val targetContact = contactDao.getByEmail(target)
        if (targetContact != null && members.any { it.contactId == targetContact.id }) {
            Log.w(TAG, "MEMBER_ADD_REQUEST: target $target уже в группе, игнорируем")
            return
        }

        val targetInfo = msg.members.firstOrNull { it.email.equals(target, ignoreCase = true) }
        if (targetInfo == null) {
            Log.w(TAG, "MEMBER_ADD_REQUEST: нет данных target $target в members[], игнорируем")
            return
        }

        val publicKey = try {
            java.util.Base64.getDecoder().decode(targetInfo.publicKey)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "MEMBER_ADD_REQUEST: невалидный publicKey base64, игнорируем")
            return
        }

        dao.upsert(
            PendingAddRequestEntity(
                chatId = msg.chatId,
                targetEmail = target,
                requesterEmail = requester,
                targetPublicKey = publicKey,
                targetDisplayName = targetInfo.displayName,
                createdAt = System.currentTimeMillis()
            )
        )
        Log.i(TAG, "MEMBER_ADD_REQUEST сохранён: chat=${msg.chatId} target=$target requester=$requester")
    }

    /**
     * MEMBER_ADD_APPROVED: админ одобрил мой запрос. Удаляем локальный
     * pending-маркер. Сам новый участник появится через MEMBER_ADDED от
     * админа в общем broadcast'е.
     */
    private suspend fun handleMemberAddApproved(msg: ControlMessage, fromEmail: String?) {
        val dao = pendingAddRequestDao ?: return
        val target = msg.targetEmail ?: return
        val requester = msg.requesterEmail ?: return

        if (!requester.equals(selfEmail, ignoreCase = true)) {
            Log.w(TAG, "MEMBER_ADD_APPROVED: requesterEmail=$requester != selfEmail=$selfEmail. Не для меня.")
            return
        }

        val chat = chatDao.getById(msg.chatId) ?: return
        if (fromEmail != null && !chat.createdBy.equals(fromEmail, ignoreCase = true)) {
            Log.w(TAG, "MEMBER_ADD_APPROVED от $fromEmail, но admin=${chat.createdBy}. Игнорируем.")
            return
        }

        dao.delete(msg.chatId, target)
        Log.i(TAG, "MEMBER_ADD_APPROVED: chat=${msg.chatId} target=$target — pending снят")
    }

    /**
     * MEMBER_ADD_REJECTED: админ отклонил мой запрос. Снимаем pending,
     * UI покажет подсказку через NotificationsCenter (Step 7).
     */
    private suspend fun handleMemberAddRejected(msg: ControlMessage, fromEmail: String?) {
        val dao = pendingAddRequestDao ?: return
        val target = msg.targetEmail ?: return
        val requester = msg.requesterEmail ?: return

        if (!requester.equals(selfEmail, ignoreCase = true)) {
            Log.w(TAG, "MEMBER_ADD_REJECTED: requesterEmail=$requester != selfEmail=$selfEmail. Не для меня.")
            return
        }

        val chat = chatDao.getById(msg.chatId) ?: return
        if (fromEmail != null && !chat.createdBy.equals(fromEmail, ignoreCase = true)) {
            Log.w(TAG, "MEMBER_ADD_REJECTED от $fromEmail, но admin=${chat.createdBy}. Игнорируем.")
            return
        }

        dao.delete(msg.chatId, target)
        Log.i(TAG, "MEMBER_ADD_REJECTED: chat=${msg.chatId} target=$target — pending снят")
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
        // Пропускаем себя — контакт для себя не создаётся, в chat_members нас нет
        if (selfEmail.isNotEmpty() && memberInfo.email.equals(selfEmail, ignoreCase = true)) {
            Log.d(TAG, "Пропускаем self (${memberInfo.email}) в members")
            return
        }
        var contact = contactDao.getByEmail(memberInfo.email)
        if (contact == null) {
            // Создать новый контакт из данных управляющего сообщения.
            // Вычисляем fingerprint сразу, чтобы UI сразу показывал safety number и
            // пользователь мог верифицировать контакт без ожидания keyex.
            val publicKeyBytes = java.util.Base64.getDecoder().decode(memberInfo.publicKey)
            val fingerprint = computeFingerprintOrEmpty(publicKeyBytes)
            contact = ContactEntity(
                email = memberInfo.email,
                displayName = memberInfo.displayName,
                publicKey = publicKeyBytes,
                fingerprint = fingerprint,
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

    private suspend fun computeFingerprintOrEmpty(remotePublicKey: ByteArray): String {
        val storage = keyStorage ?: return ""
        return try {
            val localPub = storage.getPublicKey() ?: return ""
            FingerprintGenerator.generateHex(localPub, remotePublicKey)
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось вычислить fingerprint: ${e.message}")
            ""
        }
    }

    companion object {
        private const val TAG = "ControlMessageHandler"
    }
}
