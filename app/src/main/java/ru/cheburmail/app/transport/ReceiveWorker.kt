package ru.cheburmail.app.transport

import android.util.Log
import ru.cheburmail.app.crypto.CryptoException
import ru.cheburmail.app.crypto.MessageDecryptor
import ru.cheburmail.app.db.ChatType
import ru.cheburmail.app.db.MediaDownloadStatus
import ru.cheburmail.app.db.MediaType
import ru.cheburmail.app.db.MessageStatus
import ru.cheburmail.app.db.dao.ChatDao
import ru.cheburmail.app.db.dao.ContactDao
import ru.cheburmail.app.db.dao.MessageDao
import ru.cheburmail.app.db.entity.ChatEntity
import ru.cheburmail.app.db.entity.MessageEntity
import ru.cheburmail.app.group.ControlMessage
import ru.cheburmail.app.group.ControlMessageHandler
import ru.cheburmail.app.media.MediaDecryptor
import ru.cheburmail.app.media.MediaFileManager
import ru.cheburmail.app.media.MediaMetadata
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
    private val emailConfig: EmailConfig? = null,
    private val mediaDecryptor: MediaDecryptor? = null,
    private val mediaFileManager: MediaFileManager? = null
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
                Log.d(TAG, "Decrypting ${msg.msgUuid} from ${msg.fromEmail}")
                val plaintext = decryptor.decrypt(
                    msg.envelope,
                    contact.publicKey,
                    recipientPrivateKey
                )

                val textStr = String(plaintext, Charsets.UTF_8)

                // Handle DELETE control messages
                if (textStr.startsWith("DELETE:")) {
                    val targetMsgId = textStr.removePrefix("DELETE:")
                    messageDao.deleteById(targetMsgId)
                    Log.i(TAG, "Deleted message $targetMsgId by remote request from ${msg.fromEmail}")
                    continue
                }

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
                    plaintext = textStr,
                    status = MessageStatus.RECEIVED,
                    timestamp = now
                )
                messageDao.insert(entity)
                savedCount++

                Log.i(TAG, "Saved new message ${msg.msgUuid} from ${msg.fromEmail}")

                // Show notification
                notificationHelper?.showMessageNotification(
                    senderName = contact.displayName,
                    preview = textStr.take(100),
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

        // Обрабатываем медиа-сообщения
        if (mediaDecryptor != null && mediaFileManager != null) {
            for (mediaMsg in received.mediaMessages) {
                try {
                    if (messageDao.existsById(mediaMsg.msgUuid)) {
                        Log.d(TAG, "Duplicate media message ${mediaMsg.msgUuid}, skipping")
                        continue
                    }

                    val contact = contactDao.getByEmail(mediaMsg.fromEmail)
                    if (contact == null) {
                        Log.w(TAG, "Unknown sender ${mediaMsg.fromEmail}, skipping media ${mediaMsg.msgUuid}")
                        continue
                    }

                    val decryptedMedia = mediaDecryptor.decrypt(
                        metadataEnvelope = mediaMsg.metadataEnvelope,
                        payloadEnvelope = mediaMsg.payloadEnvelope,
                        senderPublicKey = contact.publicKey,
                        recipientPrivateKey = recipientPrivateKey
                    )

                    val now = System.currentTimeMillis()

                    // Убеждаемся что чат существует
                    if (chatDao != null) {
                        val existingChat = chatDao.getById(mediaMsg.chatId)
                        if (existingChat == null) {
                            chatDao.insert(ChatEntity(
                                id = mediaMsg.chatId,
                                type = ChatType.DIRECT,
                                title = contact.displayName,
                                createdAt = now,
                                updatedAt = now
                            ))
                        }
                    }

                    val metadata = decryptedMedia.metadata
                    val mediaType = when (metadata.type) {
                        MediaMetadata.TYPE_IMAGE -> MediaType.IMAGE
                        MediaMetadata.TYPE_VOICE -> MediaType.VOICE
                        MediaMetadata.TYPE_FILE -> MediaType.FILE
                        else -> MediaType.FILE
                    }

                    // Сохраняем файл локально
                    val localPath: String?
                    val thumbPath: String?

                    when (mediaType) {
                        MediaType.IMAGE -> {
                            localPath = mediaFileManager.saveImage(mediaMsg.msgUuid, decryptedMedia.fileBytes)
                            // Используем полное изображение как thumbnail (или ресайзим при необходимости)
                            thumbPath = localPath
                        }
                        MediaType.VOICE -> {
                            val ext = if (metadata.mimeType.contains("mp4")) "m4a" else "ogg"
                            localPath = mediaFileManager.saveVoice(mediaMsg.msgUuid, decryptedMedia.fileBytes, ext)
                            thumbPath = null
                        }
                        else -> {
                            localPath = mediaFileManager.saveFile(
                                mediaMsg.msgUuid, decryptedMedia.fileBytes,
                                metadata.fileName.ifBlank { "file" }
                            )
                            thumbPath = null
                        }
                    }

                    val entity = MessageEntity(
                        id = mediaMsg.msgUuid,
                        chatId = mediaMsg.chatId,
                        senderContactId = contact.id,
                        isOutgoing = false,
                        plaintext = metadata.caption,
                        status = MessageStatus.RECEIVED,
                        timestamp = now,
                        mediaType = mediaType,
                        localMediaUri = localPath,
                        thumbnailUri = thumbPath,
                        fileName = metadata.fileName.ifBlank { null },
                        fileSize = metadata.fileSize.takeIf { it > 0 },
                        mimeType = metadata.mimeType.ifBlank { null },
                        voiceDurationMs = metadata.durationMs.takeIf { it > 0 },
                        waveformData = metadata.waveform.ifBlank { null },
                        mediaDownloadStatus = MediaDownloadStatus.COMPLETED
                    )
                    messageDao.insert(entity)
                    savedCount++

                    Log.i(TAG, "Saved media message ${mediaMsg.msgUuid} (${metadata.type}) from ${mediaMsg.fromEmail}")

                    val notifPreview = when (mediaType) {
                        MediaType.IMAGE -> "📷 Фото"
                        MediaType.VOICE -> "🎤 Голосовое"
                        MediaType.FILE -> "📎 ${metadata.fileName}"
                        else -> "Медиа"
                    }
                    notificationHelper?.showMessageNotification(
                        senderName = contact.displayName,
                        preview = notifPreview,
                        chatId = mediaMsg.chatId
                    )

                } catch (e: CryptoException) {
                    Log.e(TAG, "Decrypt error for media ${mediaMsg.msgUuid}: ${e.message}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing media ${mediaMsg.msgUuid}: ${e.message}")
                }
            }
        }

        return savedCount
    }

    companion object {
        private const val TAG = "ReceiveWorker"
    }
}
