package ru.cheburmail.app.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Тесты RateLimitTracker.
 * Проверяет increment, canSend, reset, getLeastUsed.
 */
class RateLimitTrackerTest {

    private var currentTime = 1000000L
    private lateinit var tracker: RateLimitTracker

    @Before
    fun setup() {
        currentTime = 1000000L
        tracker = RateLimitTracker(dailyLimit = 10, clock = { currentTime })
    }

    @Test
    fun increment_increasesCount() {
        tracker.increment("alice@mail.ru")
        assertEquals(1, tracker.getCount("alice@mail.ru"))

        tracker.increment("alice@mail.ru")
        assertEquals(2, tracker.getCount("alice@mail.ru"))
    }

    @Test
    fun canSend_underLimit_returnsTrue() {
        assertTrue(tracker.canSend("alice@mail.ru"))
    }

    @Test
    fun canSend_atLimit_returnsFalse() {
        repeat(10) { tracker.increment("alice@mail.ru") }
        assertFalse(tracker.canSend("alice@mail.ru"))
    }

    @Test
    fun canSend_afterReset_returnsTrue() {
        repeat(10) { tracker.increment("alice@mail.ru") }
        assertFalse(tracker.canSend("alice@mail.ru"))

        // Продвигаем время на 1 час + 1ms
        currentTime += RateLimitTracker.RESET_INTERVAL_MS + 1

        assertTrue(tracker.canSend("alice@mail.ru"))
        assertEquals(0, tracker.getCount("alice@mail.ru"))
    }

    @Test
    fun getCount_newAccount_returnsZero() {
        assertEquals(0, tracker.getCount("new@mail.ru"))
    }

    @Test
    fun getCount_afterResetInterval_returnsZero() {
        tracker.increment("alice@mail.ru")
        tracker.increment("alice@mail.ru")
        assertEquals(2, tracker.getCount("alice@mail.ru"))

        currentTime += RateLimitTracker.RESET_INTERVAL_MS + 1
        assertEquals(0, tracker.getCount("alice@mail.ru"))
    }

    @Test
    fun getLeastUsed_returnsLeastUsedAccount() {
        tracker.increment("alice@mail.ru")
        tracker.increment("alice@mail.ru")
        tracker.increment("alice@mail.ru")
        tracker.increment("bob@mail.ru")

        val emails = listOf("alice@mail.ru", "bob@mail.ru")
        assertEquals("bob@mail.ru", tracker.getLeastUsed(emails))
    }

    @Test
    fun getLeastUsed_allOverLimit_returnsNull() {
        val emails = listOf("alice@mail.ru", "bob@mail.ru")
        repeat(10) { tracker.increment("alice@mail.ru") }
        repeat(10) { tracker.increment("bob@mail.ru") }

        assertNull(tracker.getLeastUsed(emails))
    }

    @Test
    fun getLeastUsed_emptyList_returnsNull() {
        assertNull(tracker.getLeastUsed(emptyList()))
    }

    @Test
    fun getLeastUsed_oneAvailable_returnsIt() {
        val emails = listOf("alice@mail.ru", "bob@mail.ru")
        repeat(10) { tracker.increment("alice@mail.ru") }
        tracker.increment("bob@mail.ru")

        assertEquals("bob@mail.ru", tracker.getLeastUsed(emails))
    }

    @Test
    fun separateAccounts_independentCounters() {
        tracker.increment("alice@mail.ru")
        tracker.increment("alice@mail.ru")
        tracker.increment("bob@mail.ru")

        assertEquals(2, tracker.getCount("alice@mail.ru"))
        assertEquals(1, tracker.getCount("bob@mail.ru"))
    }

    @Test
    fun resetAll_clearsAllCounters() {
        tracker.increment("alice@mail.ru")
        tracker.increment("bob@mail.ru")

        tracker.resetAll()

        assertEquals(0, tracker.getCount("alice@mail.ru"))
        assertEquals(0, tracker.getCount("bob@mail.ru"))
    }
}
