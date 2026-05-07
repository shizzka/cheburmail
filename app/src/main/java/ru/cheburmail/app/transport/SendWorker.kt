package ru.cheburmail.app.transport

import android.util.Log
import ru.cheburmail.app.account.MultiAccountManager
import ru.cheburmail.app.crypto.CryptoException
import ru.cheburmail.app.crypto.model.EncryptedEnvelope
import ru.cheburmail.app.db.MediaType
import ru.cheburmail.app.db.MessageStatus
import ru.cheburmail.app.db.QueueStatus
import ru.cheburmail.app.db.dao.ContactDao
import ru.cheburmail.app.db.dao.MessageDao
import ru.cheburmail.app.db.dao.SendQueueDao
import ru.cheburmail.app.db.entity.SendQueueEntity

/**
 * Processes the send queue (send_queue from Room).
 *
 * Algorithm:
 * 1. Get all entries with status QUEUED and nextRetryAt <= now
 * 2. For each entry:
 *    a. Update status -> SENDING
 *    b. Get message from messages by messageId
 *    c. Get recipient public key from contacts
 *    d. Format encrypted payload as email and send via SMTP
 *    e. On success: update status -> SENT
 *    f. On error:
 *       - Increment retryCount
 *       - Calculate nextDelay via RetryStrategy
 *       - If canRetry: update nextRetryAt, status -> QUEUED
 *       - If !canRetry: update status -> FAILED
 */
class SendWorker(
    private val smtpClient: SmtpClient,
    private val emailFormatter: EmailFormatter,
    private val retryStrategy: RetryStrategy,
    private val sendQueueDao: SendQueueDao,
    private val messageDao: MessageDao,
    private val contactDao: ContactDao,
    private val emailConfig: EmailConfig,
    private val multiAccountManager: MultiAccountManager? = null,
    /**
     * Если false — отключаем авто-fallback на другой аккаунт при SMTP-фейле.
     * Юзер может выключить через настройки. Default=true.
     */
    private val autoFallbackEnabled: Boolean = true
) {

    /**
     * Process the send queue.
     * Called periodically (from a coroutine or WorkManager).
     */
    suspend fun processQueue() {
        val now = System.currentTimeMillis()
        val queued = sendQueueDao.getQueued()
        val retryable = sendQueueDao.getRetryable(now)
        val items = (queued + retryable).distinctBy { it.id }

        for (entry in items) {
            processEntry(entry)
        }
    }

    private suspend fun processEntry(entry: SendQueueEntity) {
        val msgId = entry.messageId
        Log.d(TAG, "Sending message $msgId, attempt ${entry.retryCount + 1}")

        // Mark as SENDING
        sendQueueDao.updateStatus(entry.id, QueueStatus.SENDING)

        // sendConfig объявлен ВНЕ try чтобы catch-блок видел реально
        // использованный аккаунт (а не emailConfig) — это критично для health-tracking
        // когда multi-account выбрал не базовый аккаунт.
        var sendConfig: EmailConfig = emailConfig
        try {
            // Get the message
            val message = messageDao.getByIdOnce(msgId)
            if (message == null) {
                Log.e(TAG, "Message $msgId not found in DB, marking FAILED")
                sendQueueDao.updateStatus(entry.id, QueueStatus.FAILED)
                return
            }

            // Get recipient contact
            val contact = contactDao.getByEmail(entry.recipientEmail)
            if (contact == null) {
                Log.e(TAG, "Contact ${entry.recipientEmail} not found, marking FAILED")
                sendQueueDao.updateStatus(entry.id, QueueStatus.FAILED)
                return
            }

            // Выбираем аккаунт: мульти-аккаунт (с учётом health) или одиночный
            sendConfig = multiAccountManager?.getNextSendAccount() ?: emailConfig

            // Load payload: from file (large media) or from DB BLOB (text / small media)
            val payload = if (entry.payloadFilePath != null) {
                val file = java.io.File(entry.payloadFilePath)
                if (!file.exists()) {
                    Log.e(TAG, "Payload file not found: ${entry.payloadFilePath}, marking FAILED")
                    sendQueueDao.updateStatus(entry.id, QueueStatus.FAILED)
                    return
                }
                file.readBytes()
            } else {
                entry.encryptedPayload
            }

            // For media messages the payload is: [4-byte metaLen][metaBytes][payloadBytes].
            // For text messages it is a plain EncryptedEnvelope wire encoding.
            if (message.mediaType != MediaType.NONE && payload.size > 4) {
                val metaLen = ((payload[0].toInt() and 0xff) shl 24) or
                    ((payload[1].toInt() and 0xff) shl 16) or
                    ((payload[2].toInt() and 0xff) shl 8) or
                    (payload[3].toInt() and 0xff)

                if (metaLen > 0 && 4 + metaLen < payload.size) {
                    val metaBytes = payload.copyOfRange(4, 4 + metaLen)
                    val payloadBytes = payload.copyOfRange(4 + metaLen, payload.size)

                    val metaEnvelope = EncryptedEnvelope.fromBytes(metaBytes)
                    val payloadEnvelope = EncryptedEnvelope.fromBytes(payloadBytes)

                    val emailMessage = emailFormatter.formatMedia(
                        metadataEnvelope = metaEnvelope,
                        payloadEnvelope = payloadEnvelope,
                        chatId = message.chatId,
                        msgUuid = message.id,
                        fromEmail = sendConfig.email,
                        toEmail = entry.recipientEmail
                    )
                    smtpClient.sendWithAttachment(sendConfig, emailMessage)
                } else {
                    Log.e(TAG, "Invalid media payload for $msgId, falling back to text send")
                    val envelope = EncryptedEnvelope.fromBytes(payload)
                    val emailMessage = emailFormatter.format(
                        envelope = envelope,
                        chatId = message.chatId,
                        msgUuid = message.id,
                        fromEmail = sendConfig.email,
                        toEmail = entry.recipientEmail
                    )
                    smtpClient.send(sendConfig, emailMessage)
                }
            } else {
                val envelope = EncryptedEnvelope.fromBytes(payload)
                val emailMessage = emailFormatter.format(
                    envelope = envelope,
                    chatId = message.chatId,
                    msgUuid = message.id,
                    fromEmail = sendConfig.email,
                    toEmail = entry.recipientEmail
                )
                smtpClient.send(sendConfig, emailMessage)
            }

            // Clean up payload file after successful send
            if (entry.payloadFilePath != null) {
                try { java.io.File(entry.payloadFilePath).delete() } catch (_: Exception) {}
            }

            // Фиксируем отправку для rate limit tracking + здоровье аккаунта
            multiAccountManager?.recordSend(sendConfig.email)
            multiAccountManager?.health()?.recordOk(sendConfig.email)

            // Success
            sendQueueDao.updateStatus(entry.id, QueueStatus.SENT)
            messageDao.updateStatus(msgId, MessageStatus.SENT)
            // Verify update actually worked
            val updated = messageDao.getByIdOnce(msgId)
            Log.i(TAG, "Message $msgId sent successfully, DB status now: ${updated?.status}")

        } catch (e: CryptoException) {
            // Crypto errors are not retryable
            Log.e(TAG, "Crypto error for $msgId: ${e.message}, marking FAILED")
            sendQueueDao.updateStatus(entry.id, QueueStatus.FAILED)

        } catch (e: TransportException.FormatException) {
            // Format errors are not retryable
            Log.e(TAG, "Format error for $msgId: ${e.message}, marking FAILED")
            sendQueueDao.updateStatus(entry.id, QueueStatus.FAILED)

        } catch (e: TransportException.SmtpException) {
            // Health-aware fallback: маркируем фактически использованный
            // sendConfig (не emailConfig!) как sick, пробуем другой аккаунт
            // ПРЯМО СЕЙЧАС (без retry-delay). Если успех — мы уже отправили
            // в catch-блоке. Если нет — обычный handleRetry.
            val sentByFallback = tryImmediateFallback(entry, sendConfig, e)
            if (!sentByFallback) {
                handleRetry(entry, e)
            }

        } catch (e: Exception) {
            // Unexpected errors — treat as retryable
            handleRetry(entry, e)
        }
    }

    /**
     * При SMTP-фейле — мгновенный fallback на другой здоровый аккаунт.
     * Возвращает true если сообщение всё-таки отправлено (через альтернативу).
     * Использует [MultiAccountManager.getNextSendAccount(exclude)] чтобы
     * исключить текущий упавший аккаунт.
     */
    private suspend fun tryImmediateFallback(
        entry: SendQueueEntity,
        failedConfig: EmailConfig,
        originalError: TransportException.SmtpException
    ): Boolean {
        val mam = multiAccountManager ?: return false
        // Отметим РЕАЛЬНО упавший аккаунт как sick (failedConfig != emailConfig
        // если round-robin выбрал не базовый)
        mam.health().recordFail(failedConfig.email)
        Log.w(TAG, "SMTP fail для ${failedConfig.email}: ${originalError.message} — пробуем fallback")

        // Глобальный rubber-cheque: если auto-fallback отключён — не пробуем
        if (!autoFallbackEnabled) {
            Log.d(TAG, "Auto-fallback отключён в настройках, пропускаем")
            return false
        }

        val fallback = mam.getNextSendAccount(exclude = setOf(failedConfig.email))
            ?: run {
                Log.w(TAG, "Fallback недоступен — нет других здоровых аккаунтов")
                mam.emit(ru.cheburmail.app.account.MultiAccountManager.SendEvent.AllAccountsFailed(
                    originalAccount = failedConfig.email,
                    reason = originalError.message ?: "SMTP fail"
                ))
                return false
            }

        return try {
            val message = messageDao.getByIdOnce(entry.messageId) ?: return false
            val contact = contactDao.getByEmail(entry.recipientEmail) ?: return false
            val payload = if (entry.payloadFilePath != null) {
                java.io.File(entry.payloadFilePath).readBytes()
            } else {
                entry.encryptedPayload
            }
            // Используем тот же путь форматирования, но fromEmail = fallback.email.
            // Текстовое сообщение — простой envelope; медиа — meta+payload.
            if (message.mediaType != ru.cheburmail.app.db.MediaType.NONE && payload.size > 4) {
                val metaLen = ((payload[0].toInt() and 0xff) shl 24) or
                    ((payload[1].toInt() and 0xff) shl 16) or
                    ((payload[2].toInt() and 0xff) shl 8) or
                    (payload[3].toInt() and 0xff)
                if (metaLen > 0 && 4 + metaLen < payload.size) {
                    val metaBytes = payload.copyOfRange(4, 4 + metaLen)
                    val payloadBytes = payload.copyOfRange(4 + metaLen, payload.size)
                    val metaEnv = ru.cheburmail.app.crypto.model.EncryptedEnvelope.fromBytes(metaBytes)
                    val payloadEnv = ru.cheburmail.app.crypto.model.EncryptedEnvelope.fromBytes(payloadBytes)
                    val emailMessage = emailFormatter.formatMedia(
                        metadataEnvelope = metaEnv,
                        payloadEnvelope = payloadEnv,
                        chatId = message.chatId,
                        msgUuid = message.id,
                        fromEmail = fallback.email,
                        toEmail = entry.recipientEmail
                    )
                    smtpClient.sendWithAttachment(fallback, emailMessage)
                } else {
                    val env = ru.cheburmail.app.crypto.model.EncryptedEnvelope.fromBytes(payload)
                    val emailMessage = emailFormatter.format(
                        envelope = env, chatId = message.chatId, msgUuid = message.id,
                        fromEmail = fallback.email, toEmail = entry.recipientEmail
                    )
                    smtpClient.send(fallback, emailMessage)
                }
            } else {
                val env = ru.cheburmail.app.crypto.model.EncryptedEnvelope.fromBytes(payload)
                val emailMessage = emailFormatter.format(
                    envelope = env, chatId = message.chatId, msgUuid = message.id,
                    fromEmail = fallback.email, toEmail = entry.recipientEmail
                )
                smtpClient.send(fallback, emailMessage)
            }
            // Успех! Чистим payload-file, маркируем SENT.
            if (entry.payloadFilePath != null) {
                try { java.io.File(entry.payloadFilePath).delete() } catch (_: Exception) {}
            }
            mam.recordSend(fallback.email)
            mam.health().recordOk(fallback.email)
            sendQueueDao.updateStatus(entry.id, ru.cheburmail.app.db.QueueStatus.SENT)
            messageDao.updateStatus(entry.messageId, ru.cheburmail.app.db.MessageStatus.SENT)
            Log.i(TAG, "Сообщение ${entry.messageId} отправлено через fallback ${fallback.email}")
            mam.emit(ru.cheburmail.app.account.MultiAccountManager.SendEvent.FallbackUsed(
                originalAccount = failedConfig.email,
                fallbackAccount = fallback.email,
                reason = originalError.message ?: "SMTP fail"
            ))
            true
        } catch (e: TransportException.SmtpException) {
            mam.health().recordFail(fallback.email)
            Log.w(TAG, "Fallback ${fallback.email} тоже упал: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Fallback ${fallback.email} упал с unexpected error: ${e.message}")
            false
        }
    }

    private suspend fun handleRetry(entry: SendQueueEntity, error: Exception) {
        val newRetryCount = entry.retryCount + 1
        Log.e(TAG, "Failed to send ${entry.messageId}: ${error.message}")

        if (retryStrategy.canRetry(newRetryCount)) {
            val delay = retryStrategy.nextDelay(newRetryCount) ?: 0L
            val nextRetryAt = System.currentTimeMillis() + delay
            sendQueueDao.updateStatus(
                id = entry.id,
                status = QueueStatus.QUEUED,
                retryCount = newRetryCount,
                nextRetryAt = nextRetryAt
            )
            Log.d(TAG, "Message ${entry.messageId} queued for retry #$newRetryCount in ${delay / 1000}s")
        } else {
            sendQueueDao.updateStatus(entry.id, QueueStatus.FAILED)
            Log.w(TAG, "Message ${entry.messageId} FAILED after ${RetryStrategy.MAX_RETRIES} retries")
        }
    }

    companion object {
        private const val TAG = "SendWorker"
    }
}
