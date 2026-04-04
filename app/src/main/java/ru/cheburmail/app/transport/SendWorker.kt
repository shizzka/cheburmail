package ru.cheburmail.app.transport

import android.util.Log
import ru.cheburmail.app.account.MultiAccountManager
import ru.cheburmail.app.crypto.CryptoException
import ru.cheburmail.app.crypto.model.EncryptedEnvelope
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
    private val multiAccountManager: MultiAccountManager? = null
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

            // Выбираем аккаунт: мульти-аккаунт (round-robin) или одиночный
            val sendConfig = multiAccountManager?.getNextSendAccount() ?: emailConfig

            // The encrypted payload is already stored in send_queue.
            // Parse it as EncryptedEnvelope and format + send via SMTP.
            val envelope = EncryptedEnvelope.fromBytes(entry.encryptedPayload)
            val emailMessage = emailFormatter.format(
                envelope = envelope,
                chatId = message.chatId,
                msgUuid = message.id,
                fromEmail = sendConfig.email,
                toEmail = entry.recipientEmail
            )

            smtpClient.send(sendConfig, emailMessage)

            // Фиксируем отправку для rate limit tracking
            multiAccountManager?.recordSend(sendConfig.email)

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
            // SMTP errors are retryable
            handleRetry(entry, e)

        } catch (e: Exception) {
            // Unexpected errors — treat as retryable
            handleRetry(entry, e)
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
