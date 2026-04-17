package ru.cheburmail.app.transport

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Session
import javax.mail.Store
import javax.mail.search.FlagTerm
import javax.mail.search.SubjectTerm

/**
 * Represents extracted media parts from a multipart/mixed CheburMail media email.
 *
 * @property metadataBytes raw bytes of Part 0 (encrypted metadata)
 * @property payloadBytes raw bytes of Part 1 (encrypted file payload, "payload.enc")
 */
data class ExtractedMediaParts(
    val metadataBytes: ByteArray,
    val payloadBytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExtractedMediaParts) return false
        return metadataBytes.contentEquals(other.metadataBytes) &&
            payloadBytes.contentEquals(other.payloadBytes)
    }

    override fun hashCode(): Int {
        var result = metadataBytes.contentHashCode()
        result = 31 * result + payloadBytes.contentHashCode()
        return result
    }
}

/**
 * Fetches email messages via IMAP over SSL.
 * Blocking — call from a background thread or coroutine dispatcher.
 */
open class ImapClient {

    /**
     * Connect to IMAP, ensure the CheburMail folder exists,
     * move any CM/ messages from INBOX, then fetch UNSEEN messages.
     *
     * @param config connection configuration
     * @return list of EmailMessage from the CheburMail folder
     * @throws TransportException.ImapException on errors
     */
    open fun fetchMessages(config: EmailConfig): List<EmailMessage> {
        if (!FETCH_LOCK.tryLock(30, TimeUnit.SECONDS)) {
            Log.d(TAG, "fetchMessages skipped — another fetch is in progress (30s timeout)")
            return emptyList()
        }
        var store: Store? = null
        try {
            store = connectStore(config)

            // Создаем папку CheburMail если она не существует
            ensureCheburMailFolder(store)

            // Перемещаем сообщения из INBOX в CheburMail
            moveCheburMailFromInbox(store)

            val folder = store.getFolder(CHEBURMAIL_FOLDER)
            if (!folder.exists()) {
                Log.w(TAG, "Папка $CHEBURMAIL_FOLDER не существует после создания")
                return emptyList()
            }

            folder.open(Folder.READ_WRITE)
            try {
                val totalCM = folder.messageCount
                Log.d(TAG, "CheburMail: $totalCM всего")

                // Fetch ALL messages — deduplication happens in ReceiveWorker by msgUuid.
                // Previously we fetched only UNSEEN, but messages marked SEEN by
                // web-client or server were permanently skipped.
                val scanCount = minOf(MAX_FETCH_COUNT, totalCM)
                val toProcess = if (scanCount > 0) {
                    folder.getMessages(totalCM - scanCount + 1, totalCM)
                } else {
                    emptyArray()
                }

                val result = mutableListOf<EmailMessage>()

                for (msg in toProcess) {
                    val subject = msg.subject ?: continue
                    if (!subject.startsWith(EmailMessage.SUBJECT_PREFIX)) continue

                    try {
                        // Skip oversized messages to prevent OOM
                        val msgSize = msg.size
                        if (msgSize > MAX_MESSAGE_SIZE) {
                            Log.w(TAG, "Skipping oversized message $subject (${msgSize / 1024}KB)")
                            continue
                        }

                        val bodyBytes = extractBody(msg)
                        val rawFrom = msg.from?.firstOrNull()?.toString() ?: continue
                        val from = normalizeEmail(rawFrom)
                        val rawTo = msg.allRecipients?.firstOrNull()?.toString() ?: ""
                        val to = normalizeEmail(rawTo)
                        val isMedia = subject.endsWith(EmailMessage.MEDIA_SUBJECT_SUFFIX)
                        val contentType = if (isMedia) {
                            EmailMessage.CHEBURMAIL_MEDIA_CONTENT_TYPE
                        } else {
                            EmailMessage.CHEBURMAIL_CONTENT_TYPE
                        }
                        val attachmentBytes = if (isMedia) extractAttachment(msg) else null

                        result.add(
                            EmailMessage(
                                from = from,
                                to = to,
                                subject = subject,
                                body = bodyBytes,
                                contentType = contentType,
                                attachment = attachmentBytes
                            )
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error extracting message ${msg.subject}: ${e.message}")
                        // Don't mark as SEEN — will retry next cycle
                    }
                }

                return result
            } finally {
                if (folder.isOpen) {
                    folder.close(false)
                }
            }
        } catch (e: TransportException) {
            throw e
        } catch (e: Exception) {
            throw TransportException.ImapException("IMAP fetch failed: ${e.message}", e)
        } finally {
            store?.close()
            FETCH_LOCK.unlock()
        }
    }

    /**
     * Ensure the CheburMail folder exists on the IMAP server.
     *
     * @param config connection configuration
     * @throws TransportException.ImapException on errors
     */
    fun ensureCheburMailFolder(config: EmailConfig) {
        var store: Store? = null
        try {
            store = connectStore(config)
            ensureCheburMailFolder(store)
        } catch (e: TransportException) {
            throw e
        } catch (e: Exception) {
            throw TransportException.ImapException(
                "Failed to ensure CheburMail folder: ${e.message}", e
            )
        } finally {
            store?.close()
        }
    }

    /**
     * Move CheburMail messages from INBOX to the CheburMail folder.
     * Called during fetchMessages to collect scattered messages.
     */
    fun moveCheburMailFromInbox(config: EmailConfig) {
        var store: Store? = null
        try {
            store = connectStore(config)
            moveCheburMailFromInbox(store)
        } catch (e: TransportException) {
            throw e
        } catch (e: Exception) {
            throw TransportException.ImapException(
                "Failed to move CheburMail from INBOX: ${e.message}", e
            )
        } finally {
            store?.close()
        }
    }

    // --- Internal helpers ---

    private fun connectStore(config: EmailConfig): Store {
        val props = Properties().apply {
            put("mail.imap.host", config.imapHost)
            put("mail.imap.port", config.imapPort.toString())
            put("mail.imap.ssl.enable", "true")
            put("mail.imap.ssl.checkserveridentity", "true")
            put("mail.imap.connectiontimeout", "15000")
            put("mail.imap.timeout", "15000")
        }

        val session = Session.getInstance(props)
        val store = session.getStore("imaps")
        store.connect(config.imapHost, config.email, config.password)
        return store
    }

    private fun ensureCheburMailFolder(store: Store) {
        val folder = store.getFolder(CHEBURMAIL_FOLDER)
        if (!folder.exists()) {
            val created = folder.create(Folder.HOLDS_MESSAGES)
            if (!created) {
                // Fallback: try INBOX.CheburMail for providers that require hierarchy
                val inboxChild = store.getFolder("INBOX.$CHEBURMAIL_FOLDER")
                if (!inboxChild.exists()) {
                    inboxChild.create(Folder.HOLDS_MESSAGES)
                }
            }
        }
    }

    private fun moveCheburMailFromInbox(store: Store) {
        val inbox = store.getFolder("INBOX")
        if (!inbox.exists()) return

        inbox.open(Folder.READ_WRITE)
        try {
            // Диагностика: сколько всего сообщений в INBOX
            val totalInbox = inbox.messageCount
            val unseenInbox = inbox.unreadMessageCount
            if (totalInbox > 0) {
                Log.d(TAG, "INBOX: $totalInbox всего, $unseenInbox непрочитанных")
            }

            // Сначала пробуем IMAP SEARCH по Subject
            var cmMessages = inbox.search(SubjectTerm("CM/1/"))

            // Fallback: на Mail.ru/bk.ru SubjectTerm может не работать с encoded subjects.
            // Сканируем последние N сообщений вручную.
            if (cmMessages.isEmpty() && totalInbox > 0) {
                val scanCount = minOf(50, totalInbox)
                val startIdx = totalInbox - scanCount + 1
                val recent = inbox.getMessages(startIdx, totalInbox)
                val manual = recent.filter { msg ->
                    try {
                        msg.subject?.startsWith("CM/1/") == true
                    } catch (e: Exception) { false }
                }
                if (manual.isNotEmpty()) {
                    Log.w(TAG, "SubjectTerm не нашёл, но ручной скан нашёл ${manual.size} CM/1/ сообщений")
                    cmMessages = manual.toTypedArray()
                }
            }

            if (cmMessages.isEmpty()) {
                Log.d(TAG, "В INBOX нет сообщений CM/1/")
                return
            }

            android.util.Log.d(TAG, "Найдено ${cmMessages.size} сообщений CM/1/ в INBOX")

            val cheburFolder = store.getFolder(CHEBURMAIL_FOLDER)
            if (!cheburFolder.exists()) return

            inbox.copyMessages(cmMessages, cheburFolder)

            for (msg in cmMessages) {
                msg.setFlag(Flags.Flag.DELETED, true)
            }
            inbox.expunge()

            android.util.Log.d(TAG, "Перемещено ${cmMessages.size} сообщений в $CHEBURMAIL_FOLDER")
        } finally {
            if (inbox.isOpen) {
                inbox.close(true)
            }
        }
    }

    private fun extractBody(message: javax.mail.Message): ByteArray {
        val content = message.content
        return when (content) {
            is ByteArray -> content
            is InputStream -> content.use { it.readBytes() }
            is javax.mail.Multipart -> {
                if (content.count > 0) {
                    val part = content.getBodyPart(0)
                    val partContent = part.content
                    when (partContent) {
                        is ByteArray -> partContent
                        is InputStream -> partContent.use { it.readBytes() }
                        is String -> partContent.toByteArray(Charsets.UTF_8)
                        else -> {
                            val bos = ByteArrayOutputStream()
                            part.writeTo(bos)
                            bos.toByteArray()
                        }
                    }
                } else {
                    ByteArray(0)
                }
            }
            is String -> content.toByteArray(Charsets.UTF_8)
            else -> {
                val bos = ByteArrayOutputStream()
                message.writeTo(bos)
                bos.toByteArray()
            }
        }
    }

    /**
     * Extract the payload attachment (Part 1) from a multipart/mixed media message.
     * Returns null if the message is not multipart or has fewer than 2 parts.
     */
    private fun extractAttachment(message: javax.mail.Message): ByteArray? {
        val content = message.content
        if (content !is javax.mail.Multipart) return null
        if (content.count < 2) return null
        return try {
            val part = content.getBodyPart(1)
            val partContent = part.content
            when (partContent) {
                is ByteArray -> partContent
                is InputStream -> partContent.use { it.readBytes() }
                is String -> partContent.toByteArray(Charsets.UTF_8)
                else -> {
                    val bos = ByteArrayOutputStream()
                    part.writeTo(bos)
                    bos.toByteArray()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting attachment from media message: ${e.message}")
            null
        }
    }

    /**
     * Normalize email from IMAP format.
     * " <user@example.com>" → "user@example.com"
     * "User Name <user@example.com>" → "user@example.com"
     */
    private fun normalizeEmail(raw: String): String {
        val trimmed = raw.trim()
        val match = Regex("<(.+)>").find(trimmed)
        return match?.groupValues?.get(1)?.trim() ?: trimmed
    }

    /**
     * Delete messages from CheburMail IMAP folder whose subject contains [msgUuid]
     * as a whole slash-separated segment (not substring).
     *
     * Example: msgUuid="kex-aaa" matches subject "CM/1/KEYEX/kex-aaa"
     * but NOT "CM/1/KEYEX/kex-aaaZZZ".
     *
     * Subjects are of the form "CM/1/.../<id>" or "CM/1/.../<id>/M" (media),
     * so segment-level matching is exact enough without requiring the full subject.
     *
     * Also checks INBOX. Expunges after marking DELETED.
     */
    fun deleteFromImap(config: EmailConfig, msgUuid: String) {
        var store: Store? = null
        try {
            store = connectStore(config)
            for (folderName in listOf(CHEBURMAIL_FOLDER, "INBOX")) {
                val folder = store.getFolder(folderName)
                if (!folder.exists()) continue
                folder.open(Folder.READ_WRITE)
                try {
                    val total = folder.messageCount
                    val scanCount = minOf(MAX_FETCH_COUNT, total)
                    if (scanCount == 0) continue
                    val msgs = folder.getMessages(total - scanCount + 1, total)
                    for (msg in msgs) {
                        val subj = msg.subject ?: continue
                        if (subjectMatchesId(subj, msgUuid)) {
                            msg.setFlag(Flags.Flag.DELETED, true)
                            Log.d(TAG, "Marked DELETED in $folderName: $subj")
                        }
                    }
                    folder.close(true) // expunge
                } catch (e: Exception) {
                    Log.e(TAG, "Error deleting from $folderName: ${e.message}")
                    if (folder.isOpen) folder.close(false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "IMAP delete failed for $msgUuid: ${e.message}")
        } finally {
            store?.close()
        }
    }

    companion object {
        private const val TAG = "ImapClient"
        const val CHEBURMAIL_FOLDER = "CheburMail"
        private const val MAX_FETCH_COUNT = 200
        private const val MAX_MESSAGE_SIZE = 50 * 1024 * 1024 // 50MB
        /** Prevents parallel IMAP fetches from multiple callers. */
        private val FETCH_LOCK = ReentrantLock()

        /**
         * Match a CheburMail subject against an identifier (msgUuid/kex-uuid).
         * Matches only if the identifier is a complete slash-separated segment —
         * no substring collisions (e.g. "msg-abc" must not match "msg-abcdef").
         */
        fun subjectMatchesId(subject: String, id: String): Boolean {
            if (!subject.startsWith(EmailMessage.SUBJECT_PREFIX)) return false
            return subject.split('/').any { it == id }
        }
    }
}
