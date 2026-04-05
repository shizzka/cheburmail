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

    /**
     * Format a media message (image/file/voice) into an EmailMessage with /M suffix subject.
     * Both metadataEnvelope (JSON) and payloadEnvelope (binary) are Base64-encoded.
     * Body = Base64(metadataEnvelope wire bytes), Attachment = Base64(payloadEnvelope wire bytes).
     *
     * Subject: CM/1/<chatId>/<msgUuid>/M
     * Content-Type: application/x-cheburmail-media
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
        val metadataBytes = base64Encode(metadataEnvelope.toBytes())
        val payloadBytes = base64Encode(payloadEnvelope.toBytes())

        return EmailMessage(
            from = fromEmail,
            to = toEmail,
            subject = subject,
            body = metadataBytes,
            contentType = EmailMessage.CHEBURMAIL_MEDIA_CONTENT_TYPE,
            attachment = payloadBytes
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
