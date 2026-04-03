package ru.cheburmail.app.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.cheburmail.app.crypto.CryptoConstants
import ru.cheburmail.app.crypto.model.EncryptedEnvelope

class EmailParserTest {

    private val parser = EmailParser()

    private fun makeValidEmail(
        chatId: String = "chat123",
        msgUuid: String = "uuid456",
        ciphertextSize: Int = 48
    ): EmailMessage {
        val nonce = ByteArray(CryptoConstants.NONCE_BYTES) { it.toByte() }
        val ciphertext = ByteArray(ciphertextSize) { (it + 100).toByte() }
        val envelope = EncryptedEnvelope(nonce, ciphertext)
        val wireBytes = envelope.toBytes()
        val base64Body = java.util.Base64.getEncoder().withoutPadding().encode(wireBytes)

        return EmailMessage(
            from = "sender@yandex.ru",
            to = "receiver@mail.ru",
            subject = "${EmailMessage.SUBJECT_PREFIX}$chatId/$msgUuid",
            body = base64Body,
            contentType = EmailMessage.CHEBURMAIL_CONTENT_TYPE
        )
    }

    @Test
    fun parse_validEmail_returnsParsedMessage() {
        val email = makeValidEmail()
        val parsed = parser.parse(email)

        assertEquals("chat123", parsed.chatId)
        assertEquals("uuid456", parsed.msgUuid)
        assertEquals("sender@yandex.ru", parsed.fromEmail)

        val expectedNonce = ByteArray(CryptoConstants.NONCE_BYTES) { it.toByte() }
        val expectedCiphertext = ByteArray(48) { (it + 100).toByte() }
        assertTrue(expectedNonce.contentEquals(parsed.envelope.nonce))
        assertTrue(expectedCiphertext.contentEquals(parsed.envelope.ciphertext))
    }

    @Test(expected = TransportException.FormatException::class)
    fun parse_invalidSubjectPrefix_throwsFormatException() {
        val email = EmailMessage(
            from = "a@b.com",
            to = "c@d.com",
            subject = "INVALID/prefix/chat/uuid",
            body = ByteArray(0),
            contentType = EmailMessage.CHEBURMAIL_CONTENT_TYPE
        )
        parser.parse(email)
    }

    @Test(expected = TransportException.FormatException::class)
    fun parse_missingUuid_throwsFormatException() {
        val email = EmailMessage(
            from = "a@b.com",
            to = "c@d.com",
            subject = "CM/1/chatOnly",
            body = ByteArray(0),
            contentType = EmailMessage.CHEBURMAIL_CONTENT_TYPE
        )
        parser.parse(email)
    }

    @Test(expected = TransportException.FormatException::class)
    fun parse_emptyChatId_throwsFormatException() {
        val nonce = ByteArray(CryptoConstants.NONCE_BYTES) { 0 }
        val ciphertext = ByteArray(16) { 1 }
        val wireBytes = nonce + ciphertext
        val base64Body = java.util.Base64.getEncoder().withoutPadding().encode(wireBytes)

        val email = EmailMessage(
            from = "a@b.com",
            to = "c@d.com",
            subject = "CM/1//uuid",
            body = base64Body,
            contentType = EmailMessage.CHEBURMAIL_CONTENT_TYPE
        )
        parser.parse(email)
    }

    @Test
    fun isCheburMail_validEmail_returnsTrue() {
        val email = makeValidEmail()
        assertTrue(parser.isCheburMail(email))
    }

    @Test
    fun isCheburMail_wrongContentType_returnsFalse() {
        val email = makeValidEmail().copy(contentType = "text/plain")
        assertFalse(parser.isCheburMail(email))
    }

    @Test
    fun isCheburMail_wrongSubject_returnsFalse() {
        val email = makeValidEmail().copy(subject = "Hello World")
        assertFalse(parser.isCheburMail(email))
    }

    // Helper to create EmailMessage copy with changed fields
    private fun EmailMessage.copy(
        from: String = this.from,
        to: String = this.to,
        subject: String = this.subject,
        body: ByteArray = this.body,
        contentType: String = this.contentType
    ) = EmailMessage(from, to, subject, body, contentType)
}
