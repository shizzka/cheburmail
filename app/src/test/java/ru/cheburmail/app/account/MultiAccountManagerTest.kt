package ru.cheburmail.app.account

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import ru.cheburmail.app.transport.EmailConfig
import ru.cheburmail.app.transport.EmailProvider

/**
 * Тесты MultiAccountManager.getNextSendAccount — проверяем взаимодействие
 * rate-limit + health-aware фильтров и ивент-флоу.
 */
class MultiAccountManagerTest {

    private var now = 1_000_000L
    private val baseQuarantine = 5 * 60 * 1000L

    private fun acc(email: String, provider: EmailProvider = EmailProvider.YANDEX) =
        EmailConfig(email = email, password = "x", provider = provider)

    private fun mam(
        accounts: List<EmailConfig>,
        rate: RateLimitTracker = RateLimitTracker(dailyLimit = 100, clock = { now }),
        health: SmtpHealthTracker = SmtpHealthTracker(quarantineMs = baseQuarantine, clock = { now })
    ) = MultiAccountManager(
        accountSource = { accounts },
        rateLimitTracker = rate,
        healthTracker = health
    )

    @Before
    fun setup() {
        now = 1_000_000L
    }

    @Test
    fun emptyAccounts_returnsNull() = runTest {
        val m = mam(emptyList())
        assertNull(m.getNextSendAccount())
    }

    @Test
    fun singleHealthy_returnsIt() = runTest {
        val a = acc("a@yandex.ru")
        val m = mam(listOf(a))
        assertEquals(a, m.getNextSendAccount())
    }

    @Test
    fun excludesGivenEmails() = runTest {
        val a = acc("a@yandex.ru")
        val b = acc("b@mail.ru")
        val m = mam(listOf(a, b))
        // Исключаем a → должны получить b
        assertEquals(b, m.getNextSendAccount(exclude = setOf("a@yandex.ru")))
        // Исключаем оба → null
        assertNull(m.getNextSendAccount(exclude = setOf("a@yandex.ru", "b@mail.ru")))
    }

    @Test
    fun sickAccountSkipped_healthyChosen() = runTest {
        val a = acc("a@yandex.ru")
        val b = acc("b@mail.ru")
        val health = SmtpHealthTracker(quarantineMs = baseQuarantine, clock = { now })
        health.recordFail("a@yandex.ru")
        val m = mam(listOf(a, b), health = health)
        assertEquals(b, m.getNextSendAccount())
    }

    @Test
    fun allSick_returnsLeastSick() = runTest {
        // Оба больны, но a — давно (карантин почти истёк), b — только что
        val health = SmtpHealthTracker(quarantineMs = baseQuarantine, clock = { now })
        val a = acc("a@yandex.ru")
        val b = acc("b@mail.ru")

        health.recordFail("a@yandex.ru")
        now += baseQuarantine - 10_000L  // 4m50s прошло
        health.recordFail("b@mail.ru")

        val m = mam(listOf(a, b), health = health)
        // a: остался 10s, b: остался 5min → берём a (наименее больной)
        assertEquals(a, m.getNextSendAccount())
    }

    @Test
    fun rateLimitedAccountsSkipped() = runTest {
        val a = acc("a@yandex.ru")
        val b = acc("b@mail.ru")
        val rate = RateLimitTracker(dailyLimit = 2, clock = { now })
        // a превысил rate-limit
        rate.increment("a@yandex.ru")
        rate.increment("a@yandex.ru")

        val m = mam(listOf(a, b), rate = rate)
        assertEquals(b, m.getNextSendAccount())
    }

    @Test
    fun allRateLimited_returnsNull() = runTest {
        val a = acc("a@yandex.ru")
        val rate = RateLimitTracker(dailyLimit = 1, clock = { now })
        rate.increment("a@yandex.ru")

        val m = mam(listOf(a), rate = rate)
        assertNull(m.getNextSendAccount())
    }

    @Test
    fun afterQuarantineExpires_accountReturned() = runTest {
        val a = acc("a@yandex.ru")
        val health = SmtpHealthTracker(quarantineMs = baseQuarantine, clock = { now })
        health.recordFail("a@yandex.ru")

        val m = mam(listOf(a), health = health)
        // Сразу после fail — single account, all-sick fallback вернёт его (наименее больной)
        assertEquals(a, m.getNextSendAccount())

        // После карантина — снова healthy
        now += baseQuarantine + 1
        assertEquals(a, m.getNextSendAccount())
    }

    @Test
    fun healthyPreferredOverSick_evenIfRateLow() = runTest {
        // Сценарий: a (sick) использован 0 раз, b (healthy) использован 5 раз.
        // Должны выбрать b — health override rate-min.
        val a = acc("a@yandex.ru")
        val b = acc("b@mail.ru")
        val rate = RateLimitTracker(dailyLimit = 100, clock = { now })
        repeat(5) { rate.increment("b@mail.ru") }
        val health = SmtpHealthTracker(quarantineMs = baseQuarantine, clock = { now })
        health.recordFail("a@yandex.ru")

        val m = mam(listOf(a, b), rate = rate, health = health)
        assertEquals(b, m.getNextSendAccount())
    }

    @Test
    fun emit_eventsBufferDeliversToCollector() = runTest {
        val m = mam(emptyList())
        val event = withTimeout(2000L) {
            coroutineScope {
                val deferred = async { m.events.first() }
                // Дать async-ной collect стартовать перед emit
                yield()
                yield()
                m.emit(
                    MultiAccountManager.SendEvent.FallbackUsed(
                        originalAccount = "a@yandex.ru",
                        fallbackAccount = "b@mail.ru",
                        reason = "test"
                    )
                )
                deferred.await()
            }
        }
        val fallback = event as MultiAccountManager.SendEvent.FallbackUsed
        assertEquals("a@yandex.ru", fallback.originalAccount)
        assertEquals("b@mail.ru", fallback.fallbackAccount)
    }
}
