package ru.cheburmail.app.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "cheburmail_settings"
)

/**
 * Персистентное хранилище настроек приложения через DataStore Preferences.
 */
class AppSettings private constructor(private val context: Context) {

    private val ds get() = context.settingsDataStore

    // ── Ключи ─────────────────────────────────────────────────────────────

    private object Keys {
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        val DISAPPEAR_TIMER_MS = longPreferencesKey("disappear_timer_ms")
        val CHAT_SYNC_INTERVAL_SEC = longPreferencesKey("chat_sync_interval_sec")
        val BACKGROUND_SYNC_INTERVAL_MIN = longPreferencesKey("background_sync_interval_min")
        val SCREENSHOTS_BLOCKED = booleanPreferencesKey("screenshots_blocked")
    }

    // ── Значения по умолчанию ─────────────────────────────────────────────

    companion object {
        const val DEFAULT_CHAT_SYNC_SEC = 30L
        const val DEFAULT_BACKGROUND_SYNC_MIN = 15L

        val CHAT_SYNC_OPTIONS = listOf(15L, 30L, 60L, 120L)       // секунды
        val BACKGROUND_SYNC_OPTIONS = listOf(15L, 30L, 60L)       // минуты

        @Volatile
        private var instance: AppSettings? = null

        fun getInstance(context: Context): AppSettings {
            return instance ?: synchronized(this) {
                instance ?: AppSettings(context.applicationContext).also { instance = it }
            }
        }
    }

    // ── Уведомления ───────────────────────────────────────────────────────

    val notificationsEnabled: Flow<Boolean> = ds.data.map { it[Keys.NOTIFICATIONS_ENABLED] ?: true }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        ds.edit { it[Keys.NOTIFICATIONS_ENABLED] = enabled }
    }

    val soundEnabled: Flow<Boolean> = ds.data.map { it[Keys.SOUND_ENABLED] ?: true }

    suspend fun setSoundEnabled(enabled: Boolean) {
        ds.edit { it[Keys.SOUND_ENABLED] = enabled }
    }

    // ── Исчезающие сообщения ──────────────────────────────────────────────

    /** null = выключено, >0 = таймер в мс */
    val disappearTimerMs: Flow<Long?> = ds.data.map {
        val v = it[Keys.DISAPPEAR_TIMER_MS]
        if (v == null || v <= 0L) null else v
    }

    suspend fun setDisappearTimerMs(ms: Long?) {
        ds.edit {
            if (ms == null || ms <= 0L) {
                it.remove(Keys.DISAPPEAR_TIMER_MS)
            } else {
                it[Keys.DISAPPEAR_TIMER_MS] = ms
            }
        }
    }

    // ── Интервал синхронизации в открытом чате ─────────────────────────────

    val chatSyncIntervalSec: Flow<Long> = ds.data.map {
        it[Keys.CHAT_SYNC_INTERVAL_SEC] ?: DEFAULT_CHAT_SYNC_SEC
    }

    suspend fun setChatSyncIntervalSec(sec: Long) {
        ds.edit { it[Keys.CHAT_SYNC_INTERVAL_SEC] = sec }
    }

    // ── Интервал фоновой синхронизации ─────────────────────────────────────

    val backgroundSyncIntervalMin: Flow<Long> = ds.data.map {
        it[Keys.BACKGROUND_SYNC_INTERVAL_MIN] ?: DEFAULT_BACKGROUND_SYNC_MIN
    }

    suspend fun setBackgroundSyncIntervalMin(min: Long) {
        ds.edit { it[Keys.BACKGROUND_SYNC_INTERVAL_MIN] = min }
    }

    // ── Запрет скриншотов ──────────────────────────────────────────────────

    val screenshotsBlocked: Flow<Boolean> = ds.data.map {
        it[Keys.SCREENSHOTS_BLOCKED] ?: false
    }

    suspend fun setScreenshotsBlocked(blocked: Boolean) {
        ds.edit { it[Keys.SCREENSHOTS_BLOCKED] = blocked }
    }
}
