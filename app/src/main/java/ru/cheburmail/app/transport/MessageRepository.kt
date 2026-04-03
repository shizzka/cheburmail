package ru.cheburmail.app.transport

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.cheburmail.app.crypto.MessageEncryptor
import ru.cheburmail.app.db.MessageStatus
import ru.cheburmail.app.db.QueueStatus
import ru.cheburmail.app.db.dao.ContactDao
import ru.cheburmail.app.db.dao.MessageDao
import ru.cheburmail.app.db.dao.SendQueueDao
import ru.cheburmail.app.db.entity.MessageEntity
import ru.cheburmail.app.db.entity.SendQueueEntity
import java.util.UUID

/**
 * High-level API for sending and receiving messages.
 * Hides encryption details, email formatting, and SMTP/IMAP transport.
 *
 * Calling code (UI/ViewModel) works only with plaintext + chatId + contactEmail.
 */
class MessageRepository(
    private val sendWorker: SendWorker,
    private val receiveWorker: ReceiveWorker,
    private val encryptor: MessageEncryptor,
    private val messageDao: MessageDao,
    private val sendQueueDao: SendQueueDao,
    private val contactDao: ContactDao,
    private val config: EmailConfig,
    private val senderPrivateKey: ByteArray
) {

    private val sendMutex = Mutex()
    private val receiveMutex = Mutex()

    /**
     * Queue a message for sending.
     *
     * Algorithm:
     * 1. Generate UUID for the message
     * 2. Save message to Room (messages) with status SENDING
     * 3. Get recipient public key from contacts
     * 4. Encrypt plaintext with recipient's public key
     * 5. Create send_queue entry (status=QUEUED, retryCount=0, nextRetryAt=now)
     * 6. Trigger SendWorker (immediate send attempt)
     *
     * @param plaintext message text
     * @param chatId chat identifier
     * @param recipientEmail recipient email address
     * @return UUID of the queued message
     */
    suspend fun sendMessage(
        plaintext: String,
        chatId: String,
        recipientEmail: String
    ): String = sendMutex.withLock {
        val msgUuid = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        Log.d(TAG, "Sending message $msgUuid to $recipientEmail in chat $chatId")

        // 1. Save message to Room with SENDING status
        val messageEntity = MessageEntity(
            id = msgUuid,
            chatId = chatId,
            senderContactId = null, // outgoing — sender is us
            isOutgoing = true,
            plaintext = plaintext,
            status = MessageStatus.SENDING,
            timestamp = now
        )
        messageDao.insert(messageEntity)

        // 2. Get recipient public key
        val contact = contactDao.getByEmail(recipientEmail)
            ?: throw IllegalStateException("Contact not found for $recipientEmail")

        // 3. Encrypt
        val envelope = encryptor.encrypt(
            plaintext.toByteArray(Charsets.UTF_8),
            contact.publicKey,
            senderPrivateKey
        )

        // 4. Create send_queue entry
        val queueEntry = SendQueueEntity(
            messageId = msgUuid,
            recipientEmail = recipientEmail,
            encryptedPayload = envelope.toBytes(),
            status = QueueStatus.QUEUED,
            retryCount = 0,
            nextRetryAt = now,
            createdAt = now,
            updatedAt = now
        )
        sendQueueDao.insert(queueEntry)

        // 5. Trigger immediate send attempt
        try {
            sendWorker.processQueue()
        } catch (e: Exception) {
            Log.w(TAG, "Immediate send failed for $msgUuid, will retry: ${e.message}")
        }

        Log.i(TAG, "Message $msgUuid queued for sending")
        msgUuid
    }

    /**
     * Check for new incoming messages.
     *
     * @return number of new messages saved to Room
     */
    suspend fun checkIncoming(): Int = receiveMutex.withLock {
        Log.d(TAG, "Checking incoming messages")
        val count = receiveWorker.pollAndProcess(config)
        if (count > 0) {
            Log.i(TAG, "Received $count new messages")
        }
        count
    }

    /**
     * Retry sending all FAILED messages.
     * Called on network recovery.
     */
    suspend fun retrySending() = sendMutex.withLock {
        Log.d(TAG, "Retrying failed messages")

        // Re-queue FAILED items as QUEUED
        val now = System.currentTimeMillis()
        val failed = sendQueueDao.getRetryable(now)

        for (entry in failed) {
            sendQueueDao.updateStatus(
                id = entry.id,
                status = QueueStatus.QUEUED,
                retryCount = 0,
                nextRetryAt = now
            )
        }

        // Also process any existing QUEUED items
        sendWorker.processQueue()
    }

    companion object {
        private const val TAG = "MessageRepository"
    }
}
