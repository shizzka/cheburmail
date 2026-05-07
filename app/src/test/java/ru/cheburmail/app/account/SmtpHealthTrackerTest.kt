package ru.cheburmail.app.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Тесты SmtpHealthTracker. Используем fake clock чтобы не спать в тестах.
 */
class SmtpHealthTrackerTest {

    private var now = 1_000_000L
    private lateinit var tracker: SmtpHealthTracker

    private val baseQuarantine = 5 * 60 * 1000L  // 5 минут — соответствует DEFAULT_QUARANTINE_MS

    @Before
    fun setup() {
        now = 1_000_000L
        tracker = SmtpHealthTracker(quarantineMs = baseQuarantine, clock = { now })
    }

    @Test
    fun unknownAccount_isHealthy() {
        // Для неизвестного email — true (оптимистично, попробуем)
        assertTrue(tracker.isHealthy("alice@mail.ru"))
        assertEquals(0L, tracker.quarantineRemainingMs("alice@mail.ru"))
    }

    @Test
    fun recordOk_keepsHealthy() {
        tracker.recordOk("alice@mail.ru")
        assertTrue(tracker.isHealthy("alice@mail.ru"))
    }

    @Test
    fun recordFail_makesUnhealthy() {
        tracker.recordFail("alice@mail.ru")
        assertFalse(tracker.isHealthy("alice@mail.ru"))
        assertTrue(tracker.quarantineRemainingMs("alice@mail.ru") > 0)
    }

    @Test
    fun afterQuarantineExpires_healthyAgain() {
        tracker.recordFail("alice@mail.ru")
        assertFalse(tracker.isHealthy("alice@mail.ru"))

        now += baseQuarantine + 1
        assertTrue(tracker.isHealthy("alice@mail.ru"))
        assertEquals(0L, tracker.quarantineRemainingMs("alice@mail.ru"))
    }

    @Test
    fun recordOk_resetsCarantineEarly() {
        tracker.recordFail("alice@mail.ru")
        assertFalse(tracker.isHealthy("alice@mail.ru"))

        // Успешная отправка очищает счётчик
        tracker.recordOk("alice@mail.ru")
        assertTrue(tracker.isHealthy("alice@mail.ru"))
    }

    @Test
    fun consecutiveFails_exponentialBackoff() {
        // 1 fail -> 5min
        tracker.recordFail("a@x")
        val after1 = tracker.quarantineRemainingMs("a@x")
        assertTrue("1 fail должен быть ~baseQuarantine", after1 in (baseQuarantine - 100)..baseQuarantine)

        // 2 fail -> 10min
        tracker.recordFail("a@x")
        val after2 = tracker.quarantineRemainingMs("a@x")
        assertTrue("2 fails должен быть ~2×baseQuarantine", after2 in (2 * baseQuarantine - 100)..(2 * baseQuarantine))

        // 3 fail -> 20min
        tracker.recordFail("a@x")
        val after3 = tracker.quarantineRemainingMs("a@x")
        assertTrue("3 fails должен быть ~4×baseQuarantine", after3 in (4 * baseQuarantine - 100)..(4 * baseQuarantine))
    }

    @Test
    fun cappedAt_MAX_QUARANTINE() {
        // 7+ consecutive fails — cap на 1 час
        repeat(10) { tracker.recordFail("a@x") }
        val remaining = tracker.quarantineRemainingMs("a@x")
        assertTrue(
            "После 10 fails карантин не превышает MAX_QUARANTINE_MS, было=$remaining",
            remaining <= SmtpHealthTracker.MAX_QUARANTINE_MS
        )
    }

    @Test
    fun perAccountIsolation() {
        tracker.recordFail("alice@mail.ru")
        // bob не пострадал
        assertTrue(tracker.isHealthy("bob@yandex.ru"))
        assertFalse(tracker.isHealthy("alice@mail.ru"))
    }

    @Test
    fun reset_clearsAll() {
        tracker.recordFail("a@x")
        tracker.recordFail("b@y")
        assertFalse(tracker.isHealthy("a@x"))
        assertFalse(tracker.isHealthy("b@y"))

        tracker.reset()
        assertTrue(tracker.isHealthy("a@x"))
        assertTrue(tracker.isHealthy("b@y"))
    }

    @Test
    fun quarantineRemaining_decreasesOverTime() {
        tracker.recordFail("a@x")
        val initial = tracker.quarantineRemainingMs("a@x")
        now += 60_000L  // +1 минута
        val later = tracker.quarantineRemainingMs("a@x")
        assertTrue("Карантин должен уменьшиться: $initial -> $later", later < initial)
        // Должно быть примерно на 60s меньше
        assertEquals((initial - later).toFloat(), 60_000f, 1000f)
    }
}
