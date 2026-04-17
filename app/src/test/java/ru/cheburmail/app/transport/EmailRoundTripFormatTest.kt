package ru.cheburmail.app.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.cheburmail.app.crypto.CryptoConstants
import ru.cheburmail.app.crypto.model.EncryptedEnvelope

class EmailRoundTripFormatTest {

    private val formatter = EmailFormatter()
    private val parser = EmailParser()

    private fun makeEnvelope(ciphertextSize: Int): EncryptedEnvelope {
        val nonce = ByteArray(CryptoConstants.NONCE_BYTES) { (it * 7).toByte() }
        val ciphertext = ByteArray(ciphertextSize) { (it * 3 + 42).toByte() }
        return EncryptedEnvelope(nonce, ciphertext)
    }

    @Test
    fun formatThenParse_roundTrip() {
        val chatId = "chat-abc-123"
        val msgUuid = "550e8400-e29b-41d4-a716-446655440000"
        val envelope = makeEnvelope(64)

        val email = formatter.format(envelope, chatId, msgUuid, "a@yandex.ru", "b@mail.ru")
        val parsed = parser.parse(email)

        assertEquals(chatId, parsed.chatId)
        assertEquals(msgUuid, parsed.msgUuid)
        assertEquals("a@yandex.ru", parsed.fromEmail)
        assertTrue(envelope.nonce.contentEquals(parsed.envelope.nonce))
        assertTrue(envelope.ciphertext.contentEquals(parsed.envelope.ciphertext))
    }

    @Test
    fun formatThenParse_variousEnvelopeSizes() {
        // Minimum realistic ciphertext = MAC_BYTES + 1 = 17 (XSalsa20-Poly1305 always adds the MAC).
        val sizes = listOf(17, 64, 1024, 65536)

        for (size in sizes) {
            val chatId = "chat-size-$size"
            val msgUuid = "uuid-size-$size"
            val envelope = makeEnvelope(size)

            val email = formatter.format(envelope, chatId, msgUuid, "x@y.ru", "z@w.ru")
            val parsed = parser.parse(email)

            assertEquals("chatId mismatch for size $size", chatId, parsed.chatId)
            assertEquals("msgUuid mismatch for size $size", msgUuid, parsed.msgUuid)
            assertTrue(
                "nonce mismatch for size $size",
                envelope.nonce.contentEquals(parsed.envelope.nonce)
            )
            assertTrue(
                "ciphertext mismatch for size $size",
                envelope.ciphertext.contentEquals(parsed.envelope.ciphertext)
            )
        }
    }

    @Test
    fun formatThenParse_specialCharactersInChatId() {
        val chatIds = listOf("chat-123", "abc-def-456", "0123456789", "a-b-c-d-e")

        for (chatId in chatIds) {
            val msgUuid = "uuid-for-$chatId"
            val envelope = makeEnvelope(32)

            val email = formatter.format(envelope, chatId, msgUuid, "a@b.com", "c@d.com")
            val parsed = parser.parse(email)

            assertEquals("chatId mismatch for '$chatId'", chatId, parsed.chatId)
            assertEquals("msgUuid mismatch for '$chatId'", msgUuid, parsed.msgUuid)
        }
    }
}
