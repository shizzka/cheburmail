package ru.cheburmail.app.transport

import android.util.Log
import ru.cheburmail.app.crypto.CryptoException
import ru.cheburmail.app.crypto.MessageDecryptor
import ru.cheburmail.app.db.ChatType
import ru.cheburmail.app.db.MessageStatus
import ru.cheburmail.app.db.dao.ChatDao
import ru.cheburmail.app.db.dao.ContactDao
import ru.cheburmail.app.db.dao.MessageDao
import ru.cheburmail.app.db.entity.ChatEntity
import ru.cheburmail.app.db.entity.MessageEntity
import ru.cheburmail.app.group.ControlMessage
import ru.cheburmail.app.group.ControlMessageHandler
import ru.cheburmail.app.messaging.DeliveryReceiptHandler
import ru.cheburmail.app.messaging.DeliveryReceiptSender
import ru.cheburmail.app.messaging.KeyExchangeManager
import ru.cheburmail.app.notification.NotificationHelper

/**
 * Polls IMAP, parses incoming CheburMail messages,
 * deduplicates by UUID, decrypts and saves new messages to Room.
 *
 * Algorithm:
 * 1. Call TransportService.receiveMessages(config)
 * 2. For each ParsedMessage:
 *    a. Check deduplication: messageDao.existsById(msgUuid)
 *    b. If exists: skip ("Duplicate message $msgUuid, skipping")
 *    c. If new:
 *       - Get sender's public key from contacts by fromEmail
 *       - Decrypt: decryptor.decrypt(envelope, senderPublicKey, recipientPrivateKey)
 *       - Save to Room: messageDao.insert(...)
 * 3. On IMAP error: return 0, retry on the next cycle
 * 4. On decryption error for a single message: log and skip (don't block others)
 */
class ReceiveWorker(
    private val transportService: TransportService,
    private val decryptor: MessageDecryptor,
    private val retryStrategy: RetryStrategy,
    private val messageDao: MessageDao,
    private val contactDao: ContactDao,
    private val chatDao: ChatDao? = null,
    private val notificationHelper: NotificationHelper? = null,
    private val recipientPrivateKey: ByteArray,
    private val deliveryReceiptSender: DeliveryReceiptSender? = null,
    private val deliveryReceiptHandler: DeliveryReceiptHandler? = null,
    private val controlMessageHandler: ControlMessageHandler? = null,
    private val keyExchangeManager: KeyExchangeManager? = null,
    private val emailConfig: EmailConfig? = null
) {

    /**
     * Poll IMAP and process new messages.
     *
     * @param config IMAP configuration
     * @return number of new messages saved to Room
     */
    suspend fun pollAndProcess(config: EmailConfig): Int {
        val received: TransportService.ReceivedMessages
        try {
            received = transportService.receiveAll(config)
        } catch (e: TransportException.ImapException) {
            Log.e(TAG, "IMAP error: ${e.message}")
            return 0
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error fetching messages: ${e.message}")
            return 0
        }

        // Обрабатываем key exchange сообщения
        for (kexEmail in received.keyExchangeEmails) {
            try {
                keyExchangeManager?.handleKeyExchange(
                    body = kexEmail.body,
                    fromEmail = kexEmail.from,
                    config = emailConfig
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error processing key exchange from ${kexEmail.from}: ${e.message}")
            }
        }

        val parsed = received.messages

        var savedCount = 0

        for (msg in parsed) {
            try {
                // Проверяем, является ли сообщение ACK (подтверждением доставки)
                if (DeliveryReceiptSender.isAckSubject(
                    "${EmailMessage.SUBJECT_PREFIX}${msg.chatId}/${msg.msgUuid}"
                )) {
                    val ackSubject = "${EmailMessage.SUBJECT_PREFIX}${msg.chatId}/${msg.msgUuid}"
                    deliveryReceiptHandler?.handleAck(ackSubject)
                    Log.d(TAG, "Processed ACK: ${msg.msgUuid}")
                    continue
                }

                // Проверяем, является ли сообщение управляющим (групповые чаты)
                val fullSubject = "${EmailMessage.SUBJECT_PREFIX}${msg.chatId}/${msg.msgUuid}"
                if (ControlMessage.isControlSubject(fullSubject)) {
                    val contact = contactDao.getByEmail(msg.fromEmail)
                    if (contact != null) {
                        val plaintext = decryptor.decrypt(
                            msg.envelope,
                            contact.publicKey,
                            recipientPrivateKey
                        )
                        controlMessageHandler?.handle(String(plaintext, Charsets.UTF_8))
                        Log.d(TAG, "Processed control message: ${msg.msgUuid}")
                    }
                    continue
                }

                // Deduplication check BEFORE decryption — saves CPU
                if (messageDao.existsById(msg.msgUuid)) {
                    Log.d(TAG, "Duplicate message ${msg.msgUuid}, skipping")
                    continue
                }

                // Get sender's public key
                val contact = contactDao.getByEmail(msg.fromEmail)
                if (contact == null) {
                    Log.w(TAG, "Unknown sender ${msg.fromEmail}, skipping message ${msg.msgUuid}")
                    continue
                }

                // Decrypt
                Log.d(TAG, "Decrypting ${msg.msgUuid} from ${msg.fromEmail}, " +
                    "senderPubKey=${java.util.Base64.getEncoder().encodeToString(contact.publicKey).take(16)}..., " +
                    "myPubKey=${java.util.Base64.getEncoder().encodeToString(recipientPrivateKey).take(16)}...(priv)")
                val plaintext = decryptor.decrypt(
                    msg.envelope,
                    contact.publicKey,
                    recipientPrivateKey
                )

                // Ensure chat exists before saving message (FK constraint)
                val now = System.currentTimeMillis()
                if (chatDao != null) {
                    val existingChat = chatDao.getById(msg.chatId)
                    if (existingChat == null) {
                        chatDao.insert(ChatEntity(
                            id = msg.chatId,
                            type = ChatType.DIRECT,
                            title = contact.displayName,
                            createdAt = now,
                            updatedAt = now
                        ))
                        Log.d(TAG, "Created chat ${msg.chatId} for ${contact.email}")
                    }
                }

                // Save to Room
                val entity = MessageEntity(
                    id = msg.msgUuid,
                    chatId = msg.chatId,
                    senderContactId = contact.id,
                    isOutgoing = false,
                    plaintext = String(plaintext, Charsets.UTF_8),
                    status = MessageStatus.RECEIVED,
                    timestamp = now
                )
                messageDao.insert(entity)
                savedCount++

                Log.i(TAG, "Saved new message ${msg.msgUuid} from ${msg.fromEmail}")

                // Show notification
                notificationHelper?.showMessageNotification(
                    senderName = contact.displayName,
                    preview = String(plaintext, Charsets.UTF_8).take(100),
                    chatId = msg.chatId
                )

                // Отправляем ACK (подтверждение доставки) обратно отправителю
                try {
                    deliveryReceiptSender?.sendAck(
                        config = config,
                        chatId = msg.chatId,
                        originalMsgUuid = msg.msgUuid,
                        senderEmail = msg.fromEmail,
                        timestamp = now
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send delivery receipt for ${msg.msgUuid}: ${e.message}")
                    // Не блокируем обработку остальных сообщений
                }

            } catch (e: CryptoException) {
                Log.e(TAG, "Decrypt error for message ${msg.msgUuid}: ${e.message}")
                // Skip this message but continue processing others
            } catch (e: Exception) {
                Log.e(TAG, "Error processing message ${msg.msgUuid}: ${e.message}")
                // Skip this message but continue processing others
            }
        }

        return savedCount
    }

    companion object {
        private const val TAG = "ReceiveWorker"
    }
}
