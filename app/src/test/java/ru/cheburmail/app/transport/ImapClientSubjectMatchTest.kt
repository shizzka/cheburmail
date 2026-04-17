package ru.cheburmail.app.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Регрессия на #8: deleteFromImap по подстроке мог удалить лишние письма,
 * если id одного было префиксом id другого. Теперь матч — только по целому
 * слэш-разделённому сегменту subject.
 */
class ImapClientSubjectMatchTest {

    @Test
    fun `exact segment match — keyex uuid`() {
        val subj = "CM/1/KEYEX/kex-abc"
        assertTrue(ImapClient.subjectMatchesId(subj, "kex-abc"))
    }

    @Test
    fun `prefix collision does not match — kex-abc vs kex-abcdef`() {
        val subj = "CM/1/KEYEX/kex-abcdef"
        assertFalse(
            "Подстрока не должна матчиться — это был баг #8 (IMAP delete удалял соседей)",
            ImapClient.subjectMatchesId(subj, "kex-abc")
        )
    }

    @Test
    fun `message uuid in chat subject`() {
        val subj = "CM/1/chat-1/msg-xyz"
        assertTrue(ImapClient.subjectMatchesId(subj, "msg-xyz"))
    }

    @Test
    fun `media subject with trailing segment still matches inner uuid`() {
        val subj = "CM/1/chat-1/msg-xyz/M"
        assertTrue(ImapClient.subjectMatchesId(subj, "msg-xyz"))
    }

    @Test
    fun `non-CheburMail subject rejected`() {
        assertFalse(ImapClient.subjectMatchesId("Re: hello", "msg-xyz"))
    }

    @Test
    fun `chat id component is not a false match`() {
        val subj = "CM/1/chat-abc/msg-xyz"
        assertTrue(ImapClient.subjectMatchesId(subj, "chat-abc"))
        assertFalse(
            "chat-ab (префикс chat-abc) не должен матчиться",
            ImapClient.subjectMatchesId(subj, "chat-ab")
        )
    }
}
