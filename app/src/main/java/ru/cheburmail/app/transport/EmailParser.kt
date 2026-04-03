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

    companion object {
        /**
         * Default Base64 decoder using java.util.Base64 (works on Android 26+ and JVM).
         */
        private fun defaultBase64Decode(data: ByteArray): ByteArray {
            return java.util.Base64.getDecoder().decode(data)
        }
    }
}
