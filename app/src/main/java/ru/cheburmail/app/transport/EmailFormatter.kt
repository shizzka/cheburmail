package ru.cheburmail.app.transport

import ru.cheburmail.app.crypto.model.EncryptedEnvelope

/**
 * Converts an EncryptedEnvelope into an EmailMessage ready for SMTP send.
 *
 * Subject: CM/1/<chatId>/<msgUuid>
 * Body: Base64(nonce || ciphertext)
 * Content-Type: application/x-cheburmail
 */
class EmailFormatter(
    private val base64Encode: (ByteArray) -> ByteArray = Companion::defaultBase64Encode
) {

    fun format(
        envelope: EncryptedEnvelope,
        chatId: String,
        msgUuid: String,
        fromEmail: String,
        toEmail: String
    ): EmailMessage {
        val subject = "${EmailMessage.SUBJECT_PREFIX}$chatId/$msgUuid"
        val wireBytes = envelope.toBytes()
        val base64Body = base64Encode(wireBytes)

        return EmailMessage(
            from = fromEmail,
            to = toEmail,
            subject = subject,
            body = base64Body,
            contentType = EmailMessage.CHEBURMAIL_CONTENT_TYPE
        )
    }

    companion object {
        /**
         * Default Base64 encoder using java.util.Base64 (works on Android 26+ and JVM).
         */
        private fun defaultBase64Encode(data: ByteArray): ByteArray {
            return java.util.Base64.getEncoder().withoutPadding().encode(data)
        }
    }
}
