package ru.cheburmail.app.account

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Отслеживает количество отправленных email per-account.
 *
 * In-memory счётчик, сбрасывается каждый час.
 * Консервативный лимит по умолчанию: 400 писем/день (~17/час).
 * Почтовые провайдеры имеют разные лимиты (Yandex: 500/день, Mail.ru: 500/день, Gmail: 500/день).
 */
class RateLimitTracker(
    private val dailyLimit: Int = DEFAULT_DAILY_LIMIT,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private data class AccountCounter(
        val count: AtomicInteger = AtomicInteger(0),
        val resetAt: AtomicLong = AtomicLong(0)
    )

    private val counters = ConcurrentHashMap<String, AccountCounter>()

    /**
     * Увеличить счётчик отправок для аккаунта.
     *
     * @param email email аккаунта
     */
    fun increment(email: String) {
        val counter = getOrCreateCounter(email)
        checkAndResetIfNeeded(email, counter)
        counter.count.incrementAndGet()
        Log.d(TAG, "Счётчик для $email: ${counter.count.get()}")
    }

    /**
     * Проверить, может ли аккаунт отправлять (не превышен ли лимит).
     *
     * @param email email аккаунта
     * @return true если лимит не превышен
     */
    fun canSend(email: String): Boolean {
        val counter = getOrCreateCounter(email)
        checkAndResetIfNeeded(email, counter)
        return counter.count.get() < dailyLimit
    }

    /**
     * Получить текущее количество отправок для аккаунта.
     *
     * @param email email аккаунта
     * @return количество отправок с последнего сброса
     */
    fun getCount(email: String): Int {
        val counter = getOrCreateCounter(email)
        checkAndResetIfNeeded(email, counter)
        return counter.count.get()
    }

    /**
     * Получить аккаунт с минимальным использованием из переданных.
     *
     * @param emails список email-аккаунтов
     * @return email с наименьшим счётчиком, или null если все превысили лимит
     */
    fun getLeastUsed(emails: List<String>): String? {
        return emails
            .filter { canSend(it) }
            .minByOrNull { getCount(it) }
    }

    /**
     * Сбросить счётчик для всех аккаунтов.
     * Вызывается при ручном тестировании.
     */
    fun resetAll() {
        counters.clear()
    }

    private fun getOrCreateCounter(email: String): AccountCounter {
        return counters.getOrPut(email) {
            AccountCounter(resetAt = AtomicLong(clock() + RESET_INTERVAL_MS))
        }
    }

    private fun checkAndResetIfNeeded(email: String, counter: AccountCounter) {
        val now = clock()
        if (now >= counter.resetAt.get()) {
            counter.count.set(0)
            counter.resetAt.set(now + RESET_INTERVAL_MS)
            Log.d(TAG, "Счётчик для $email сброшен")
        }
    }

    companion object {
        private const val TAG = "RateLimitTracker"

        /** Лимит по умолчанию: 400 писем/день */
        const val DEFAULT_DAILY_LIMIT = 400

        /** Интервал сброса: 1 час */
        const val RESET_INTERVAL_MS = 60 * 60 * 1000L
    }
}
