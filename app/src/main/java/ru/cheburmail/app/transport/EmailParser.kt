package ru.cheburmail.app.transport

import ru.cheburmail.app.crypto.model.EncryptedEnvelope

/**
 * Parses an incoming EmailMessage back into structured CheburMail data.
 */
class EmailParser(
    private val base64Decode: (ByteArray) -> ByteArray = Companion::defaultBase64Decode
) {

    /**
     * Result of parsing an incoming email.
     */
    data class ParsedMessage(
        val chatId: String,
        val msgUuid: String,
        val envelope: EncryptedEnvelope,
        val fromEmail: String
    )

    /**
     * Parse an EmailMessage back into structured data.
     *
     * @param email incoming email
     * @return ParsedMessage with chatId, msgUuid, EncryptedEnvelope and sender email
     * @throws TransportException.FormatException if the email format is invalid
     */
    fun parse(email: EmailMessage): ParsedMessage {
        if (!email.subject.startsWith(EmailMessage.SUBJECT_PREFIX)) {
            throw TransportException.FormatException(
                "Invalid subject: expected prefix '${EmailMessage.SUBJECT_PREFIX}', got '${email.subject}'"
            )
        }

        val parts = email.subject.removePrefix(EmailMessage.SUBJECT_PREFIX).split("/")
        if (parts.size != 2) {
            throw TransportException.FormatException(
                "Invalid subject format: expected CM/1/<chatId>/<msgUuid>, got '${email.subject}'"
            )
        }
        val chatId = parts[0]
        val msgUuid = parts[1]

        if (chatId.isBlank() || msgUuid.isBlank()) {
            throw TransportException.FormatException(
                "chatId and msgUuid must not be blank in subject '${email.subject}'"
            )
        }

        val wireBytes = base64Decode(email.body)
        val envelope = EncryptedEnvelope.fromBytes(wireBytes)

        return ParsedMessage(
            chatId = chatId,
            msgUuid = msgUuid,
            envelope = envelope,
            fromEmail = email.from
        )
    }

    /**
     * Check whether an email is a CheburMail message (by subject and content-type).
     */
    fun isCheburMail(email: EmailMessage): Boolean {
        return email.subject.startsWith(EmailMessage.SUBJECT_PREFIX) &&
            email.contentType == EmailMessage.CHEBURMAIL_CONTENT_TYPE
    }

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

    /**
     * Check whether an email is a CheburMail media message (by subject suffix and content-type).
     */
    fun isCheburMailMedia(email: EmailMessage): Boolean {
        return email.subject.startsWith(EmailMessage.SUBJECT_PREFIX) &&
            email.subject.endsWith(EmailMessage.MEDIA_SUBJECT_SUFFIX) &&
            email.contentType == EmailMessage.CHEBURMAIL_MEDIA_CONTENT_TYPE
    }

    /**
     * Parse a media EmailMessage back into structured data.
     *
     * Subject: CM/1/<chatId>/<msgUuid>/M
     * Body: Base64(metadataEnvelope wire bytes)
     * Attachment: Base64(payloadEnvelope wire bytes)
     *
     * @param email incoming media email
     * @return ParsedMediaMessage with chatId, msgUuid, both EncryptedEnvelopes and sender email
     * @throws TransportException.FormatException if the email format is invalid
     */
    fun parseMedia(email: EmailMessage): ParsedMediaMessage {
        if (!email.subject.startsWith(EmailMessage.SUBJECT_PREFIX)) {
            throw TransportException.FormatException(
                "Invalid media subject: expected prefix '${EmailMessage.SUBJECT_PREFIX}', got '${email.subject}'"
            )
        }
        if (!email.subject.endsWith(EmailMessage.MEDIA_SUBJECT_SUFFIX)) {
            throw TransportException.FormatException(
                "Invalid media subject: expected suffix '${EmailMessage.MEDIA_SUBJECT_SUFFIX}', got '${email.subject}'"
            )
        }

        // Strip CM/1/ prefix and /M suffix, then split on /
        val stripped = email.subject
            .removePrefix(EmailMessage.SUBJECT_PREFIX)
            .removeSuffix(EmailMessage.MEDIA_SUBJECT_SUFFIX)
        val parts = stripped.split("/")
        if (parts.size != 2) {
            throw TransportException.FormatException(
                "Invalid media subject format: expected CM/1/<chatId>/<msgUuid>/M, got '${email.subject}'"
            )
        }
        val chatId = parts[0]
        val msgUuid = parts[1]

        if (chatId.isBlank() || msgUuid.isBlank()) {
            throw TransportException.FormatException(
                "chatId and msgUuid must not be blank in media subject '${email.subject}'"
            )
        }

        val rawAttachment = email.attachment
            ?: throw TransportException.FormatException(
                "Media email is missing attachment payload for subject '${email.subject}'"
            )

        val metadataWireBytes = base64Decode(email.body)
        val payloadWireBytes = base64Decode(rawAttachment)

        val metadataEnvelope = EncryptedEnvelope.fromBytes(metadataWireBytes)
        val payloadEnvelope = EncryptedEnvelope.fromBytes(payloadWireBytes)

        return ParsedMediaMessage(
            chatId = chatId,
            msgUuid = msgUuid,
            metadataEnvelope = metadataEnvelope,
            payloadEnvelope = payloadEnvelope,
            fromEmail = email.from
        )
    }

    companion object {
        /**
         * Default Base64 decoder using java.util.Base64 (works on Android 26+ and JVM).
         */
        private fun defaultBase64Decode(data: ByteArray): ByteArray {
            // MIME decoder handles line breaks (\r\n) added by mail transport
            return java.util.Base64.getMimeDecoder().decode(data)
        }
    }
}
