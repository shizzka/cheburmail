package ru.cheburmail.app.transport

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Properties
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
                // Fetch ALL CM messages — deduplication happens in ReceiveWorker
                val messages = folder.messages

                val result = mutableListOf<EmailMessage>()
                for (msg in messages) {
                    val subject = msg.subject ?: continue
                    if (!subject.startsWith(EmailMessage.SUBJECT_PREFIX)) continue

                    try {
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

                        // Mark as SEEN after successful extraction
                        msg.setFlag(Flags.Flag.SEEN, true)
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
            val cmMessages = inbox.search(SubjectTerm("CM/1/"))
            if (cmMessages.isEmpty()) {
                android.util.Log.d(TAG, "В INBOX нет сообщений CM/1/")
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

    companion object {
        private const val TAG = "ImapClient"
        const val CHEBURMAIL_FOLDER = "CheburMail"
    }
}
