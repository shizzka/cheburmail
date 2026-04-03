package ru.cheburmail.app.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.cheburmail.app.crypto.CryptoConstants
import ru.cheburmail.app.crypto.model.EncryptedEnvelope

class EmailFormatterTest {

    private val formatter = EmailFormatter()

    private fun makeEnvelope(ciphertextSize: Int = 48): EncryptedEnvelope {
        val nonce = ByteArray(CryptoConstants.NONCE_BYTES) { it.toByte() }
        val ciphertext = ByteArray(ciphertextSize) { (it + 100).toByte() }
        return EncryptedEnvelope(nonce, ciphertext)
    }

    @Test
    fun format_correctSubjectFormat() {
        val envelope = makeEnvelope()
        val email = formatter.format(envelope, "chat123", "uuid456", "a@b.com", "c@d.com")

        assertEquals("CM/1/chat123/uuid456", email.subject)
    }

    @Test
    fun format_bodyIsBase64OfEnvelopeBytes() {
        val envelope = makeEnvelope()
        val email = formatter.format(envelope, "chat1", "uuid1", "a@b.com", "c@d.com")

        val decoded = java.util.Base64.getDecoder().decode(email.body)
        assertArrayEquals(envelope.toBytes(), decoded)
    }

    @Test
    fun format_contentTypeIsCheburMail() {
        val envelope = makeEnvelope()
        val email = formatter.format(envelope, "chat1", "uuid1", "a@b.com", "c@d.com")

        assertEquals(EmailMessage.CHEBURMAIL_CONTENT_TYPE, email.contentType)
    }

    @Test
    fun format_fromAndToPreserved() {
        val envelope = makeEnvelope()
        val email = formatter.format(envelope, "chat1", "uuid1", "sender@yandex.ru", "receiver@mail.ru")

        assertEquals("sender@yandex.ru", email.from)
        assertEquals("receiver@mail.ru", email.to)
    }
}
