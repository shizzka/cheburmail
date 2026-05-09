package ru.cheburmail.app.account

import android.content.SharedPreferences
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Отслеживает здоровье SMTP-эндпойнтов per-account.
 *
 * Работает в паре с [MultiAccountManager]: при отправке через аккаунт получаем
 * успех или [TransportException.SmtpException] — фиксируем здесь. При выборе
 * следующего аккаунта пропускаем «больные» (failed_at < now - QUARANTINE_MS).
 *
 * Опциональный [persistence] — SharedPreferences (или другая реализация)
 * для сохранения состояния между рестартами процесса (защита от агрессивного
 * OOM-kill на MIUI: без persistence юзер заново получал retry на упавший
 * SMTP перед карантином).
 *
 * Если persistence=null — поведение как раньше (in-memory, обнуляется при
 * рестарте — намеренно для тестов и для случая, когда хочется forget'нуть
 * историю).
 */
class SmtpHealthTracker(
    private val quarantineMs: Long = DEFAULT_QUARANTINE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val persistence: Persistence? = null
) {

    /**
     * Контракт хранилища. Реализация поверх SharedPreferences живёт в
     * [SharedPrefsPersistence].
     */
    interface Persistence {
        fun load(): Map<String, PersistedHealth>
        fun save(email: String, h: PersistedHealth)
    }

    data class PersistedHealth(
        val lastFailAt: Long,
        val lastOkAt: Long,
        val consecutiveFails: Long
    )

    private data class AccountHealth(
        val lastFailAt: AtomicLong = AtomicLong(0),
        val lastOkAt: AtomicLong = AtomicLong(0),
        val consecutiveFails: AtomicLong = AtomicLong(0)
    )

    private val state = ConcurrentHashMap<String, AccountHealth>()

    init {
        // Восстанавливаем persisted state при инициализации
        persistence?.load()?.forEach { (email, ph) ->
            val h = AccountHealth()
            h.lastFailAt.set(ph.lastFailAt)
            h.lastOkAt.set(ph.lastOkAt)
            h.consecutiveFails.set(ph.consecutiveFails)
            state[email] = h
        }
    }

    /** Зарегистрировать успешную отправку через аккаунт. Сбрасывает карантин. */
    fun recordOk(email: String) {
        val h = stateFor(email)
        h.lastOkAt.set(clock())
        h.consecutiveFails.set(0)
        persist(email, h)
    }

    /**
     * Зарегистрировать SMTP-фейл аккаунта. Будет skip'аться [quarantineMs]
     * после fail'а. Каждый последующий consecutive fail увеличивает карантин
     * экспоненциально, до [MAX_QUARANTINE_MS] (защита от ретрай-шторма).
     */
    fun recordFail(email: String) {
        val h = stateFor(email)
        h.lastFailAt.set(clock())
        val n = h.consecutiveFails.incrementAndGet()
        Log.w(TAG, "SMTP fail #$n для $email — карантин ${effectiveQuarantine(n) / 1000}с")
        persist(email, h)
    }

    private fun persist(email: String, h: AccountHealth) {
        persistence?.save(email, PersistedHealth(
            lastFailAt = h.lastFailAt.get(),
            lastOkAt = h.lastOkAt.get(),
            consecutiveFails = h.consecutiveFails.get()
        ))
    }

    /**
     * true если аккаунт сейчас «здоров» (нет недавнего fail'а или карантин
     * прошёл). Для аккаунта без записей — true (оптимистично, попробуем).
     */
    fun isHealthy(email: String): Boolean {
        val h = state[email] ?: return true
        val fails = h.consecutiveFails.get()
        if (fails == 0L) return true
        val sinceFail = clock() - h.lastFailAt.get()
        return sinceFail >= effectiveQuarantine(fails)
    }

    /**
     * Сколько секунд ещё в карантине. 0 если здоров. Для UI/логов.
     */
    fun quarantineRemainingMs(email: String): Long {
        val h = state[email] ?: return 0
        val fails = h.consecutiveFails.get()
        if (fails == 0L) return 0
        val remaining = effectiveQuarantine(fails) - (clock() - h.lastFailAt.get())
        return remaining.coerceAtLeast(0)
    }

    /** Полный сброс — для тестов. */
    fun reset() {
        state.clear()
    }

    private fun stateFor(email: String): AccountHealth =
        state.getOrPut(email) { AccountHealth() }

    private fun effectiveQuarantine(consecutiveFails: Long): Long {
        // Экспоненциальный backoff: base * 2^(n-1), capped.
        val multiplier = 1L shl ((consecutiveFails - 1).coerceIn(0, 6).toInt())
        return (quarantineMs * multiplier).coerceAtMost(MAX_QUARANTINE_MS)
    }

    companion object {
        private const val TAG = "SmtpHealthTracker"

        /** Базовый карантин: 5 минут. После N consecutive fails — экспонента. */
        const val DEFAULT_QUARANTINE_MS = 5 * 60 * 1000L

        /** Потолок: после 7+ fails не штрафуем больше 1 часа. */
        const val MAX_QUARANTINE_MS = 60 * 60 * 1000L
    }
}

/**
 * SharedPreferences-реализация SmtpHealthTracker.Persistence.
 * Каждый аккаунт хранится как 3 ключа: <email>.lastFailAt, .lastOkAt, .fails.
 */
class SharedPrefsHealthPersistence(
    private val prefs: SharedPreferences
) : SmtpHealthTracker.Persistence {

    override fun load(): Map<String, SmtpHealthTracker.PersistedHealth> {
        val all = prefs.all
        val out = mutableMapOf<String, SmtpHealthTracker.PersistedHealth>()
        // Группируем ключи по email-префиксу
        val emails = all.keys.mapNotNull { key ->
            when {
                key.endsWith(SUFFIX_FAILS) -> key.removeSuffix(SUFFIX_FAILS)
                else -> null
            }
        }.toSet()
        for (email in emails) {
            out[email] = SmtpHealthTracker.PersistedHealth(
                lastFailAt = prefs.getLong("$email$SUFFIX_LAST_FAIL", 0),
                lastOkAt = prefs.getLong("$email$SUFFIX_LAST_OK", 0),
                consecutiveFails = prefs.getLong("$email$SUFFIX_FAILS", 0)
            )
        }
        return out
    }

    override fun save(email: String, h: SmtpHealthTracker.PersistedHealth) {
        prefs.edit()
            .putLong("$email$SUFFIX_LAST_FAIL", h.lastFailAt)
            .putLong("$email$SUFFIX_LAST_OK", h.lastOkAt)
            .putLong("$email$SUFFIX_FAILS", h.consecutiveFails)
            .apply()
    }

    companion object {
        private const val SUFFIX_LAST_FAIL = ".lastFailAt"
        private const val SUFFIX_LAST_OK = ".lastOkAt"
        private const val SUFFIX_FAILS = ".fails"
    }
}
