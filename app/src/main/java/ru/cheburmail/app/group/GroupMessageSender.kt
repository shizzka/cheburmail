package ru.cheburmail.app.group

import android.util.Log
import ru.cheburmail.app.crypto.MessageEncryptor
import ru.cheburmail.app.db.MessageStatus
import ru.cheburmail.app.db.dao.ChatDao
import ru.cheburmail.app.db.dao.ContactDao
import ru.cheburmail.app.db.dao.MessageDao
import ru.cheburmail.app.db.dao.SendQueueDao
import ru.cheburmail.app.db.entity.MessageEntity
import ru.cheburmail.app.db.entity.SendQueueEntity
import java.util.UUID

/**
 * Fan-out отправка сообщений в групповой чат.
 *
 * Для каждого участника группы создаёт отдельный SendQueueEntity
 * с сообщением, зашифрованным публичным ключом этого участника.
 *
 * Ограничения:
 * - Предупреждение при глубине очереди > QUEUE_DEPTH_WARNING (50)
 * - Не шифрует для самого отправителя
 */
open class GroupMessageSender(
    private val chatDao: ChatDao,
    private val contactDao: ContactDao,
    private val sendQueueDao: SendQueueDao,
    private val encryptor: MessageEncryptor,
    private val senderPrivateKey: ByteArray,
    private val senderEmail: String,
    private val messageDao: MessageDao
) {

    /**
     * Отправить текстовое сообщение всем участникам группы.
     *
     * @param chatId ID группового чата
     * @param plaintext текст сообщения
     * @param messageId UUID сообщения (для привязки к MessageEntity)
     * @return количество созданных элементов очереди
     */
    suspend fun sendToGroup(chatId: String, plaintext: String, messageId: String): Int {
        val members = chatDao.getMembersForChat(chatId)
        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
        var queuedCount = 0

        // Проверка глубины очереди
        val pendingCount = sendQueueDao.countPending()
        if (pendingCount > QUEUE_DEPTH_WARNING) {
            Log.w(TAG, "Глубина очереди отправки: $pendingCount (порог: $QUEUE_DEPTH_WARNING)")
        }

        for (member in members) {
            val contact = contactDao.getById(member.contactId)
            if (contact == null) {
                Log.w(TAG, "Контакт ${member.contactId} не найден, пропускаем")
                continue
            }

            // Не отправляем самому себе
            if (contact.email == senderEmail) {
                continue
            }

            try {
                val envelope = encryptor.encrypt(
                    plaintextBytes,
                    contact.publicKey,
                    senderPrivateKey
                )

                val now = System.currentTimeMillis()
                val queueItem = SendQueueEntity(
                    messageId = messageId,
                    recipientEmail = contact.email,
                    encryptedPayload = envelope.toBytes(),
                    createdAt = now,
                    updatedAt = now
                )

                sendQueueDao.insert(queueItem)
                queuedCount++

                Log.d(TAG, "Сообщение $messageId для ${contact.email} добавлено в очередь")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка шифрования для ${contact.email}: ${e.message}")
            }
        }

        Log.i(TAG, "Fan-out: $queuedCount из ${members.size} элементов добавлено для chatId=$chatId")
        return queuedCount
    }

    /**
     * Отправить управляющее сообщение всем участникам группы.
     *
     * @param chatId ID группового чата
     * @param controlMessage управляющее сообщение
     * @return количество созданных элементов очереди
     */
    open suspend fun sendControlToGroup(chatId: String, controlMessage: ControlMessage): Int {
        val controlUuid = "${ControlMessage.CTRL_PREFIX}${UUID.randomUUID()}"
        // Плейсхолдер MessageEntity обязателен из-за FK send_queue.message_id → messages.id
        // с ON DELETE CASCADE. Скрывается из UI фильтром `id NOT LIKE 'ctrl-%'` в MessageDao.
        messageDao.insert(
            MessageEntity(
                id = controlUuid,
                chatId = chatId,
                isOutgoing = true,
                plaintext = "",
                status = MessageStatus.SENDING,
                timestamp = System.currentTimeMillis()
            )
        )
        return sendToGroup(chatId, controlMessage.toJson(), controlUuid)
    }

    /**
     * Отправить управляющее сообщение **одному** получателю (point-to-point).
     *
     * Используется для approval-флоу: MEMBER_ADD_REQUEST от не-админа к
     * админу и MEMBER_ADD_APPROVED/REJECTED от админа к автору запроса.
     * В отличие от sendControlToGroup ничего не fan-out'ит — только адресату.
     *
     * @param chatId ID группового чата (нужен для FK на messages)
     * @param recipientEmail email получателя (admin или requester)
     * @param controlMessage управляющее сообщение
     * @return true если в очередь добавлен 1 элемент, false если контакт
     *   не найден / не удалось зашифровать / получатель = self
     */
    open suspend fun sendControlToAdmin(
        chatId: String,
        recipientEmail: String,
        controlMessage: ControlMessage
    ): Boolean {
        if (recipientEmail.equals(senderEmail, ignoreCase = true)) {
            Log.w(TAG, "sendControlToAdmin: получатель == self ($senderEmail), пропускаем")
            return false
        }

        val recipient = contactDao.getByEmail(recipientEmail)
        if (recipient == null) {
            Log.e(TAG, "sendControlToAdmin: контакт $recipientEmail не найден, не отправляем")
            return false
        }

        val controlUuid = "${ControlMessage.CTRL_PREFIX}${UUID.randomUUID()}"
        // FK send_queue.message_id → messages.id (CASCADE) — нужен placeholder,
        // как и в sendControlToGroup. Скрывается из UI фильтром `id NOT LIKE 'ctrl-%'`.
        val now = System.currentTimeMillis()
        messageDao.insert(
            MessageEntity(
                id = controlUuid,
                chatId = chatId,
                isOutgoing = true,
                plaintext = "",
                status = MessageStatus.SENDING,
                timestamp = now
            )
        )

        return try {
            val envelope = encryptor.encrypt(
                controlMessage.toBytes(),
                recipient.publicKey,
                senderPrivateKey
            )
            sendQueueDao.insert(
                SendQueueEntity(
                    messageId = controlUuid,
                    recipientEmail = recipient.email,
                    encryptedPayload = envelope.toBytes(),
                    createdAt = now,
                    updatedAt = now
                )
            )
            Log.i(TAG, "P2P-управление отправлено: type=${controlMessage.type} to=${recipient.email} chatId=$chatId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendControlToAdmin: ошибка шифрования для ${recipient.email}: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "GroupMessageSender"
        const val QUEUE_DEPTH_WARNING = 50
    }
}
