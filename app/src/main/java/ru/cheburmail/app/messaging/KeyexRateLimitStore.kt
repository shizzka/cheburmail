package ru.cheburmail.app.messaging

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Персистентное хранилище rate-limit меток отправки keyex.
 *
 * Нужно, чтобы перезапуск процесса не обнулял rate-limit и спам-защита
 * не сбрасывалась на каждый рестарт сервиса.
 */
interface KeyexRateLimitStore {
    /** Последнее время отправки keyex на `email` (epoch ms) или `null` если не отправляли. */
    fun lastSent(email: String): Long?

    /** Зафиксировать факт отправки keyex на `email` во время `at`. */
    fun markSent(email: String, at: Long)

    companion object {
        /** Fallback in-memory реализация для тестов и мест без Context. */
        fun inMemory(): KeyexRateLimitStore = InMemoryStore()

        fun sharedPrefs(context: Context): KeyexRateLimitStore =
            SharedPrefsStore(context.applicationContext)
    }
}

private class InMemoryStore : KeyexRateLimitStore {
    private val map = ConcurrentHashMap<String, Long>()
    override fun lastSent(email: String): Long? = map[email.lowercase()]
    override fun markSent(email: String, at: Long) { map[email.lowercase()] = at }
}

private class SharedPrefsStore(context: Context) : KeyexRateLimitStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun lastSent(email: String): Long? {
        val v = prefs.getLong(email.lowercase(), -1L)
        return if (v < 0) null else v
    }

    override fun markSent(email: String, at: Long) {
        prefs.edit().putLong(email.lowercase(), at).apply()
    }

    companion object {
        private const val PREFS_NAME = "keyex_rate_limit"
    }
}
