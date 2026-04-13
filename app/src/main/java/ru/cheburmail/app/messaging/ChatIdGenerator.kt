package ru.cheburmail.app.messaging

import java.util.UUID

/**
 * Deterministic chat ID generation from a pair of email addresses.
 *
 * Both sides of a conversation compute the same chatId because the emails
 * are sorted alphabetically before hashing:
 *   chatId("alice@x.com", "bob@y.com") == chatId("bob@y.com", "alice@x.com")
 *
 * Each unique pair produces a unique chatId, preventing message cross-routing.
 */
object ChatIdGenerator {

    /**
     * Generate a deterministic direct-chat ID for two participants.
     *
     * @param email1 one participant's email
     * @param email2 the other participant's email
     * @return UUID v3 string, stable for any ordering of the same pair
     */
    fun directChatId(email1: String, email2: String): String {
        val sorted = listOf(email1.lowercase(), email2.lowercase()).sorted()
        return UUID.nameUUIDFromBytes(
            "direct:${sorted[0]}:${sorted[1]}".toByteArray()
        ).toString()
    }
}
