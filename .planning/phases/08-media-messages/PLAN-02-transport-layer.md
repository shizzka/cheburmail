---
phase: 8
plan: 02
title: "Transport layer for media attachments"
wave: 1
depends_on: []
files_modified:
  - app/src/main/java/ru/cheburmail/app/transport/SmtpClient.kt
  - app/src/main/java/ru/cheburmail/app/transport/ImapClient.kt
  - app/src/main/java/ru/cheburmail/app/transport/EmailMessage.kt
  - app/src/main/java/ru/cheburmail/app/transport/EmailFormatter.kt
  - app/src/main/java/ru/cheburmail/app/transport/EmailParser.kt
autonomous: true
---

# Plan 02: Transport Layer for Media Attachments

## Objective
Extend the SMTP/IMAP transport layer to send and receive multipart emails with encrypted binary attachments. The email format for media messages uses `multipart/mixed` with two MIME parts: Part 0 is the encrypted metadata (JSON) and Part 1 is the encrypted file payload. Subject format gains a `/M` suffix to distinguish media from text: `CM/1/<chatId>/<msgUuid>/M`. The existing text-only flow must remain untouched.

## Tasks

<task id="1" title="Add media subject prefix and attachment field to EmailMessage" file="app/src/main/java/ru/cheburmail/app/transport/EmailMessage.kt">
Add a constant for media subject suffix, and add an optional `attachment` field for binary attachment data:

1. Add to the `companion object`:
```kotlin
const val MEDIA_SUBJECT_SUFFIX = "/M"
const val CHEBURMAIL_MEDIA_CONTENT_TYPE = "application/x-cheburmail-media"
```

2. Add an optional `attachment` field to the data class (after `contentType`):
```kotlin
val attachment: ByteArray? = null
```

3. Update `equals()` to compare `attachment`:
```kotlin
&& (attachment?.contentEquals(other.attachment) ?: (other.attachment == null))
```

4. Update `hashCode()` to include `attachment`:
```kotlin
result = 31 * result + (attachment?.contentHashCode() ?: 0)
```

5. Add a helper method:
```kotlin
fun isMediaMessage(): Boolean = subject.endsWith(MEDIA_SUBJECT_SUFFIX)
```
</task>

<task id="2" title="Add sendWithAttachment to SmtpClient" file="app/src/main/java/ru/cheburmail/app/transport/SmtpClient.kt" depends_on="1">
Add a new `sendWithAttachment()` method to `SmtpClient`. Keep the existing `send()` method unchanged. The new method sends a `multipart/mixed` email with two MIME body parts.

1. Increase SMTP write timeout for media messages. Add a constant:
```kotlin
private const val MEDIA_WRITE_TIMEOUT = "120000" // 2 minutes for large attachments
```

2. Add the new method:
```kotlin
/**
 * Send an email with a binary attachment via SMTP.
 * Used for media messages (image/file/voice).
 *
 * @param config connection configuration
 * @param message email message where body = encrypted metadata bytes, attachment = encrypted payload bytes
 * @throws TransportException.SmtpException on errors
 */
open fun sendWithAttachment(config: EmailConfig, message: EmailMessage) {
    try {
        require(message.attachment != null) { "attachment must not be null" }

        Log.d(TAG, "Отправка медиа-письма: ${message.from} -> ${message.to}, " +
            "subject: ${message.subject}, attachment size: ${message.attachment.size}")

        val props = Properties().apply {
            put("mail.smtp.host", config.smtpHost)
            put("mail.smtp.port", config.smtpPort.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.ssl.enable", "true")
            put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "30000")
            put("mail.smtp.writetimeout", MEDIA_WRITE_TIMEOUT)
        }

        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(config.email, config.password)
            }
        })

        val mimeMessage = MimeMessage(session).apply {
            setFrom(InternetAddress(message.from))
            setRecipient(Message.RecipientType.TO, InternetAddress(message.to))
            subject = message.subject

            // Part 0: encrypted metadata
            val metadataPart = MimeBodyPart().apply {
                dataHandler = javax.activation.DataHandler(
                    ByteArrayDataSource(message.body, message.contentType)
                )
                setHeader("Content-Transfer-Encoding", "base64")
            }

            // Part 1: encrypted payload (attachment)
            val payloadPart = MimeBodyPart().apply {
                dataHandler = javax.activation.DataHandler(
                    ByteArrayDataSource(message.attachment, "application/octet-stream")
                )
                fileName = "payload.enc"
                setHeader("Content-Transfer-Encoding", "base64")
            }

            val multipart = MimeMultipart("mixed").apply {
                addBodyPart(metadataPart)
                addBodyPart(payloadPart)
            }

            setContent(multipart)
        }

        Transport.send(mimeMessage)
        Log.i(TAG, "Медиа-письмо успешно отправлено: ${message.subject}")
    } catch (e: TransportException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Ошибка отправки медиа-письма: ${e.message}", e)
        throw TransportException.SmtpException("SMTP media send failed: ${e.message}", e)
    }
}
```
</task>

<task id="3" title="Add multipart extraction to ImapClient" file="app/src/main/java/ru/cheburmail/app/transport/ImapClient.kt" depends_on="1">
Modify `ImapClient` to detect media messages (subject ending with `/M`) and extract both MIME parts:

1. Add a new data class inside `ImapClient` or at package level for the extracted parts:
```kotlin
/**
 * Extracted parts from a multipart media email.
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
```

2. In the `fetchMessages()` method, inside the loop where messages are processed, detect media subjects and extract both parts. Modify the existing extraction logic:

After `if (!subject.startsWith(EmailMessage.SUBJECT_PREFIX)) continue`, add media detection:

```kotlin
val isMedia = subject.endsWith(EmailMessage.MEDIA_SUBJECT_SUFFIX)
```

For media messages, extract Part 0 (metadata) and Part 1 (payload) from the multipart MIME body. Use the existing `extractBody()` for Part 0 (metadata), and add a new `extractAttachment()` for Part 1:

```kotlin
private fun extractAttachment(message: javax.mail.Message): ByteArray? {
    val content = message.content
    if (content is javax.mail.Multipart && content.count >= 2) {
        val part = content.getBodyPart(1)
        val partContent = part.content
        return when (partContent) {
            is ByteArray -> partContent
            is InputStream -> partContent.use { it.readBytes() }
            is String -> partContent.toByteArray(Charsets.UTF_8)
            else -> {
                val bos = ByteArrayOutputStream()
                part.writeTo(bos)
                bos.toByteArray()
            }
        }
    }
    return null
}
```

When building the `EmailMessage` result for media messages, populate the `attachment` field:

```kotlin
val attachment = if (isMedia) extractAttachment(msg) else null

result.add(
    EmailMessage(
        from = from,
        to = to,
        subject = subject,
        body = bodyBytes,
        contentType = if (isMedia) EmailMessage.CHEBURMAIL_MEDIA_CONTENT_TYPE
                      else EmailMessage.CHEBURMAIL_CONTENT_TYPE,
        attachment = attachment
    )
)
```
</task>

<task id="4" title="Add formatMedia to EmailFormatter" file="app/src/main/java/ru/cheburmail/app/transport/EmailFormatter.kt" depends_on="1">
Add a `formatMedia()` method to `EmailFormatter` that creates an `EmailMessage` with the media subject suffix and both metadata + payload as body/attachment:

```kotlin
/**
 * Format an encrypted media message for SMTP send.
 *
 * @param metadataEnvelope encrypted metadata (nonce || ciphertext)
 * @param payloadEnvelope encrypted file payload (nonce || ciphertext)
 * @param chatId chat identifier
 * @param msgUuid message UUID
 * @param fromEmail sender email
 * @param toEmail recipient email
 * @return EmailMessage with body = Base64(metadataEnvelope), attachment = Base64(payloadEnvelope)
 */
fun formatMedia(
    metadataEnvelope: EncryptedEnvelope,
    payloadEnvelope: EncryptedEnvelope,
    chatId: String,
    msgUuid: String,
    fromEmail: String,
    toEmail: String
): EmailMessage {
    val subject = "${EmailMessage.SUBJECT_PREFIX}$chatId/$msgUuid${EmailMessage.MEDIA_SUBJECT_SUFFIX}"
    val metadataBase64 = base64Encode(metadataEnvelope.toBytes())
    val payloadBase64 = base64Encode(payloadEnvelope.toBytes())

    return EmailMessage(
        from = fromEmail,
        to = toEmail,
        subject = subject,
        body = metadataBase64,
        contentType = EmailMessage.CHEBURMAIL_MEDIA_CONTENT_TYPE,
        attachment = payloadBase64
    )
}
```
</task>

<task id="5" title="Add parseMedia to EmailParser" file="app/src/main/java/ru/cheburmail/app/transport/EmailParser.kt" depends_on="1">
Add media parsing support to `EmailParser`:

1. Add a new result data class:
```kotlin
/**
 * Result of parsing an incoming media email.
 */
data class ParsedMediaMessage(
    val chatId: String,
    val msgUuid: String,
    val metadataEnvelope: EncryptedEnvelope,
    val payloadEnvelope: EncryptedEnvelope,
    val fromEmail: String
)
```

2. Add a `parseMedia()` method:
```kotlin
/**
 * Parse a media EmailMessage back into structured data.
 * Subject format: CM/1/<chatId>/<msgUuid>/M
 *
 * @param email incoming email with body (metadata) and attachment (payload)
 * @return ParsedMediaMessage with chatId, msgUuid, two envelopes, and sender
 * @throws TransportException.FormatException if format is invalid
 */
fun parseMedia(email: EmailMessage): ParsedMediaMessage {
    if (!email.subject.startsWith(EmailMessage.SUBJECT_PREFIX)) {
        throw TransportException.FormatException(
            "Invalid subject: expected prefix '${EmailMessage.SUBJECT_PREFIX}'"
        )
    }
    if (!email.subject.endsWith(EmailMessage.MEDIA_SUBJECT_SUFFIX)) {
        throw TransportException.FormatException(
            "Not a media message: subject does not end with '${EmailMessage.MEDIA_SUBJECT_SUFFIX}'"
        )
    }

    // Strip prefix "CM/1/" and suffix "/M", then split by "/"
    val inner = email.subject
        .removePrefix(EmailMessage.SUBJECT_PREFIX)
        .removeSuffix(EmailMessage.MEDIA_SUBJECT_SUFFIX)
    val parts = inner.split("/")
    if (parts.size != 2) {
        throw TransportException.FormatException(
            "Invalid media subject format: expected CM/1/<chatId>/<msgUuid>/M, got '${email.subject}'"
        )
    }
    val chatId = parts[0]
    val msgUuid = parts[1]

    if (chatId.isBlank() || msgUuid.isBlank()) {
        throw TransportException.FormatException(
            "chatId and msgUuid must not be blank in subject '${email.subject}'"
        )
    }

    val metadataBytes = base64Decode(email.body)
    val metadataEnvelope = EncryptedEnvelope.fromBytes(metadataBytes)

    if (email.attachment == null) {
        throw TransportException.FormatException("Media message has no attachment")
    }

    val payloadBytes = base64Decode(email.attachment)
    val payloadEnvelope = EncryptedEnvelope.fromBytes(payloadBytes)

    return ParsedMediaMessage(
        chatId = chatId,
        msgUuid = msgUuid,
        metadataEnvelope = metadataEnvelope,
        payloadEnvelope = payloadEnvelope,
        fromEmail = email.from
    )
}
```

3. Add a helper to check if email is a media message:
```kotlin
/**
 * Check whether an email is a CheburMail media message.
 */
fun isCheburMailMedia(email: EmailMessage): Boolean {
    return email.subject.startsWith(EmailMessage.SUBJECT_PREFIX) &&
        email.subject.endsWith(EmailMessage.MEDIA_SUBJECT_SUFFIX) &&
        email.attachment != null
}
```
</task>

## Verification
- [ ] Project compiles with `./gradlew assembleDebug --no-daemon`
- [ ] Existing text-only `SmtpClient.send()` still works unchanged
- [ ] `SmtpClient.sendWithAttachment()` constructs a valid `multipart/mixed` MIME message with 2 body parts
- [ ] `ImapClient.fetchMessages()` correctly extracts both MIME parts from a media email and populates `EmailMessage.attachment`
- [ ] `ImapClient.fetchMessages()` still works for text-only messages (attachment is null)
- [ ] `EmailFormatter.formatMedia()` produces correct subject with `/M` suffix
- [ ] `EmailParser.parseMedia()` correctly extracts chatId, msgUuid, and both envelopes from a media email
- [ ] `EmailParser.isCheburMailMedia()` returns true only for media messages
- [ ] SMTP write timeout is 120s for media (vs 15s for text)

## must_haves
- EmailMessage has `attachment: ByteArray?` field and `MEDIA_SUBJECT_SUFFIX = "/M"` constant
- SmtpClient.sendWithAttachment() sends multipart/mixed with 2 MIME parts and 120s write timeout
- ImapClient.fetchMessages() extracts Part 1 (attachment) for media messages into EmailMessage.attachment
- EmailFormatter.formatMedia() produces subject ending with /M and Base64-encodes both envelopes
- EmailParser.parseMedia() returns ParsedMediaMessage with two EncryptedEnvelopes
- Existing text-only send/receive flow is completely unaffected
