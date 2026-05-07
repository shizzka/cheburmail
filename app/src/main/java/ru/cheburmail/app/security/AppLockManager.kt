package ru.cheburmail.app.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Управление PIN-кодом приложения.
 *
 * Хранилище: per-install salt (16 байт) + PBKDF2WithHmacSHA256 хеш PIN'а
 * с 200k итераций. Раньше использовался простой SHA-256 без соли — при
 * доступе к app data 4-значный PIN перебирался мгновенно. Старый формат
 * прозрачно мигрируется на новый при первом успешном verify.
 *
 * Throttling: счётчик неуспешных попыток + экспоненциальная задержка.
 * После 10 fails — блок на 5 минут (и далее с экспонентой). Вычисляется
 * в [getLockoutRemainingMs] чтобы UI мог показывать таймер.
 */
class AppLockManager private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("cheburmail_lock", Context.MODE_PRIVATE)

    val isLockEnabled: Boolean
        get() = prefs.contains(KEY_PIN_HASH) || prefs.contains(KEY_LEGACY_HASH)

    val isBiometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC, false)

    fun setPin(pin: String) {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(pin, salt)
        prefs.edit()
            .putString(KEY_PIN_HASH, hash.toHex())
            .putString(KEY_PIN_SALT, salt.toHex())
            .remove(KEY_LEGACY_HASH)  // после установки нового PIN — legacy не нужен
            .putInt(KEY_FAIL_COUNT, 0)
            .putLong(KEY_LAST_FAIL_AT, 0L)
            .apply()
    }

    fun removePin() {
        prefs.edit()
            .remove(KEY_PIN_HASH)
            .remove(KEY_PIN_SALT)
            .remove(KEY_LEGACY_HASH)
            .remove(KEY_BIOMETRIC)
            .remove(KEY_FAIL_COUNT)
            .remove(KEY_LAST_FAIL_AT)
            .apply()
    }

    /**
     * Сколько мс осталось до возможности нового verify. 0 если не залочено.
     */
    fun getLockoutRemainingMs(): Long {
        val fails = prefs.getInt(KEY_FAIL_COUNT, 0)
        if (fails < FAIL_THRESHOLD) return 0
        val lastFail = prefs.getLong(KEY_LAST_FAIL_AT, 0L)
        if (lastFail == 0L) return 0
        // Экспоненциальный backoff поверх FAIL_THRESHOLD: 5min, 10min, 20min...
        val multiplier = 1L shl (fails - FAIL_THRESHOLD).coerceIn(0, 6)
        val lockoutMs = (LOCKOUT_BASE_MS * multiplier).coerceAtMost(LOCKOUT_MAX_MS)
        val remaining = lockoutMs - (System.currentTimeMillis() - lastFail)
        return remaining.coerceAtLeast(0)
    }

    fun verifyPin(pin: String): Boolean {
        if (getLockoutRemainingMs() > 0) {
            Log.w(TAG, "verifyPin: locked out (${getLockoutRemainingMs()}ms remaining)")
            return false
        }

        val saltHex = prefs.getString(KEY_PIN_SALT, null)
        val storedHashHex = prefs.getString(KEY_PIN_HASH, null)

        val ok = if (saltHex != null && storedHashHex != null) {
            // Новый формат
            val salt = saltHex.fromHex()
            val computed = pbkdf2(pin, salt)
            constantTimeEquals(storedHashHex.fromHex(), computed)
        } else {
            // Legacy SHA-256 без соли
            val legacyHashHex = prefs.getString(KEY_LEGACY_HASH, null)
                ?: prefs.getString("pin_hash", null)  // старый ключ
            if (legacyHashHex == null) return false
            val computed = sha256(pin)
            val match = constantTimeEquals(legacyHashHex.fromHex(), computed)
            if (match) {
                // Прозрачная миграция: переписываем в новый формат тем же PIN'ом
                Log.i(TAG, "verifyPin: миграция legacy PIN-хеша на PBKDF2")
                setPin(pin)
            }
            match
        }

        if (ok) {
            prefs.edit().putInt(KEY_FAIL_COUNT, 0).putLong(KEY_LAST_FAIL_AT, 0L).apply()
        } else {
            val fails = prefs.getInt(KEY_FAIL_COUNT, 0) + 1
            prefs.edit()
                .putInt(KEY_FAIL_COUNT, fails)
                .putLong(KEY_LAST_FAIL_AT, System.currentTimeMillis())
                .apply()
            Log.w(TAG, "verifyPin: fail #$fails")
        }
        return ok
    }

    fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_BIOMETRIC, enabled)
            .apply()
    }

    // ---- helpers ----

    private fun pbkdf2(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    private fun sha256(s: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(s.toByteArray(Charsets.UTF_8))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.fromHex(): ByteArray {
        require(length % 2 == 0) { "odd-length hex" }
        return ByteArray(length / 2) {
            ((Character.digit(this[it * 2], 16) shl 4) or Character.digit(this[it * 2 + 1], 16)).toByte()
        }
    }

    /** Не светим время сравнения через раннее return. */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    companion object {
        private const val TAG = "AppLockManager"
        private const val KEY_PIN_HASH = "pin_hash_v2"
        private const val KEY_PIN_SALT = "pin_salt"
        // Старый ключ "pin_hash" → читаем для миграции.
        private const val KEY_LEGACY_HASH = "pin_hash"
        private const val KEY_BIOMETRIC = "biometric_enabled"
        private const val KEY_FAIL_COUNT = "fail_count"
        private const val KEY_LAST_FAIL_AT = "last_fail_at"

        private const val SALT_BYTES = 16
        private const val PBKDF2_ITERATIONS = 200_000
        private const val KEY_BITS = 256

        /** После N подряд ошибок начинается lockout. */
        private const val FAIL_THRESHOLD = 5
        /** Базовая длительность lockout (5 минут). */
        private const val LOCKOUT_BASE_MS = 5 * 60 * 1000L
        /** Потолок lockout (1 час). */
        private const val LOCKOUT_MAX_MS = 60 * 60 * 1000L

        @Volatile
        private var instance: AppLockManager? = null

        fun getInstance(context: Context): AppLockManager {
            return instance ?: synchronized(this) {
                instance ?: AppLockManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
