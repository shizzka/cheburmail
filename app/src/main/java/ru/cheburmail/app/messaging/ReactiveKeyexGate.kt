package ru.cheburmail.app.messaging

import android.content.Context
import android.content.SharedPreferences

/**
 * Гейт для "реактивных" keyex: когда B видит письмо от неизвестного отправителя
 * и автоматически шлёт свой pubkey, чтобы восстановить связь после reinstall.
 *
 * Две линии защиты от спама:
 * 1. Per-email cooldown [PER_EMAIL_COOLDOWN_MS] — если A после reinstall залил
 *    в inbox B пачку старых писем, B пошлёт keyex один раз и замолчит на час.
 * 2. Global cap [GLOBAL_CAP] keyex в [GLOBAL_WINDOW_MS] — если кто-то подделал
 *    From под 100 разных адресов, B не выстрелит 100 keyex.
 *
 * Существующий [KeyexRateLimitStore] (1 минута) здесь НЕ подходит: он защищает
 * от повторной отправки того же ответа, а не от спама разных триггеров.
 */
interface ReactiveKeyexGate {
    /** true если можно слать reactive keyex на этот email сейчас. */
    fun shouldSend(email: String, now: Long = System.currentTimeMillis()): Boolean

    /** Зафиксировать факт отправки. Вызывать только после успешного send. */
    fun markSent(email: String, now: Long = System.currentTimeMillis())

    companion object {
        const val PER_EMAIL_COOLDOWN_MS = 60L * 60L * 1000L // 1 час
        const val GLOBAL_WINDOW_MS = 60L * 60L * 1000L // 1 час
        const val GLOBAL_CAP = 10

        fun sharedPrefs(context: Context): ReactiveKeyexGate =
            SharedPrefsGate(context.applicationContext)
    }
}

private class SharedPrefsGate(context: Context) : ReactiveKeyexGate {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun shouldSend(email: String, now: Long): Boolean {
        val key = perEmailKey(email)
        val last = prefs.getLong(key, -1L)
        if (last > 0 && now - last < ReactiveKeyexGate.PER_EMAIL_COOLDOWN_MS) return false

        val window = loadWindow()
        val fresh = window.filter { now - it < ReactiveKeyexGate.GLOBAL_WINDOW_MS }
        return fresh.size < ReactiveKeyexGate.GLOBAL_CAP
    }

    override fun markSent(email: String, now: Long) {
        val window = loadWindow()
            .filter { now - it < ReactiveKeyexGate.GLOBAL_WINDOW_MS }
            .toMutableList()
        window.add(now)
        prefs.edit()
            .putLong(perEmailKey(email), now)
            .putString(KEY_GLOBAL_WINDOW, window.joinToString(","))
            .apply()
    }

    private fun loadWindow(): List<Long> {
        val csv = prefs.getString(KEY_GLOBAL_WINDOW, null) ?: return emptyList()
        if (csv.isEmpty()) return emptyList()
        return csv.split(",").mapNotNull { it.trim().toLongOrNull() }
    }

    private fun perEmailKey(email: String) = "per_email:" + email.lowercase()

    companion object {
        private const val PREFS_NAME = "reactive_keyex_gate"
        private const val KEY_GLOBAL_WINDOW = "__global_window"
    }
}
