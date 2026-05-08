package ru.cheburmail.app.security

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Тесты для AppLockManager (M2 security audit 2026-05-07):
 * - PIN хранится как PBKDF2 + соль, не SHA-256
 * - Throttling: после 5 неудач — lockout 5min, экспоненциальный backoff
 * - Прозрачная миграция legacy SHA-256 PIN
 * - Constant-time сравнение (косвенно — через корректность verify)
 *
 * In-memory FakePrefs + fake clock — без Android dependencies.
 */
class AppLockManagerTest {

    private class FakePrefs : SharedPreferences {
        val data = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = data
        override fun getString(key: String, defValue: String?): String? =
            data[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?) =
            @Suppress("UNCHECKED_CAST") (data[key] as? MutableSet<String> ?: defValues)
        override fun getInt(key: String, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            data[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) {}
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) {}

        inner class FakeEditor : SharedPreferences.Editor {
            override fun putString(key: String, value: String?) = apply { data[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) =
                apply { data[key] = values }
            override fun putInt(key: String, value: Int) = apply { data[key] = value }
            override fun putLong(key: String, value: Long) = apply { data[key] = value }
            override fun putFloat(key: String, value: Float) = apply { data[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { data[key] = value }
            override fun remove(key: String) = apply { data.remove(key) }
            override fun clear() = apply { data.clear() }
            override fun commit(): Boolean = true
            override fun apply() {}
        }
    }

    private lateinit var prefs: FakePrefs
    private var now = 1_000_000L
    private lateinit var manager: AppLockManager

    @Before
    fun setup() {
        prefs = FakePrefs()
        now = 1_000_000L
        manager = AppLockManager(prefs, clock = { now })
    }

    @Test
    fun freshState_lockNotEnabled() {
        assertFalse(manager.isLockEnabled)
    }

    @Test
    fun setPin_enablesLock() {
        manager.setPin("1234")
        assertTrue(manager.isLockEnabled)
    }

    @Test
    fun setPin_storesAsPBKDF2WithSalt() {
        manager.setPin("1234")
        val storedHash = prefs.data["pin_hash_v2"] as? String
        val storedSalt = prefs.data["pin_salt"] as? String
        assertNotNull(storedHash)
        assertNotNull(storedSalt)
        assertEquals("PBKDF2 hash 32 bytes = 64 hex chars", 64, storedHash!!.length)
        assertEquals("Salt 16 bytes = 32 hex chars", 32, storedSalt!!.length)
        assertFalse("Hash не должен содержать сам PIN", storedHash.contains("1234"))
    }

    @Test
    fun setPin_eachCallGeneratesNewSalt() {
        manager.setPin("1234")
        val salt1 = prefs.data["pin_salt"] as String
        val hash1 = prefs.data["pin_hash_v2"] as String
        manager.setPin("1234")
        val salt2 = prefs.data["pin_salt"] as String
        val hash2 = prefs.data["pin_hash_v2"] as String
        assertFalse("Соль должна быть рандомной каждый раз", salt1 == salt2)
        assertFalse("Хеш должен отличаться при разных солях", hash1 == hash2)
    }

    @Test
    fun verifyPin_correctPin_returnsTrue() {
        manager.setPin("1234")
        assertTrue(manager.verifyPin("1234"))
    }

    @Test
    fun verifyPin_wrongPin_returnsFalse() {
        manager.setPin("1234")
        assertFalse(manager.verifyPin("5678"))
    }

    @Test
    fun verifyPin_failsIncrementCounter() {
        manager.setPin("1234")
        repeat(3) { manager.verifyPin("0000") }
        assertEquals(3, prefs.data["fail_count"])
    }

    @Test
    fun verifyPin_successResetsCounter() {
        manager.setPin("1234")
        manager.verifyPin("0000")
        manager.verifyPin("0000")
        assertEquals(2, prefs.data["fail_count"])
        manager.verifyPin("1234")
        assertEquals(0, prefs.data["fail_count"])
    }

    @Test
    fun afterThresholdFails_lockoutActive() {
        manager.setPin("1234")
        repeat(5) { manager.verifyPin("0000") }
        assertTrue(
            "Должен быть lockout после 5 неудач: ${manager.getLockoutRemainingMs()}",
            manager.getLockoutRemainingMs() > 0
        )
    }

    @Test
    fun duringLockout_correctPinIsRejected() {
        manager.setPin("1234")
        repeat(5) { manager.verifyPin("0000") }
        assertFalse(
            "Правильный PIN не должен проходить в lockout",
            manager.verifyPin("1234")
        )
    }

    @Test
    fun afterLockoutExpires_canVerifyAgain() {
        manager.setPin("1234")
        repeat(5) { manager.verifyPin("0000") }
        // Симулируем что прошло 6 минут (больше 5min lockout)
        now += 6 * 60 * 1000L + 1
        assertTrue("После lockout PIN должен снова работать", manager.verifyPin("1234"))
    }

    @Test
    fun consecutiveLockouts_exponentialBackoff() {
        manager.setPin("1234")
        // 5 fails → lockout1 = 5min
        repeat(5) { manager.verifyPin("0000") }
        val lock1 = manager.getLockoutRemainingMs()
        // ещё 1 fail после lockout (но всё ещё в его рамках) — игнорируется
        // отдельно тестируем после истечения первого lockout
        now += 6 * 60 * 1000L + 1
        repeat(1) { manager.verifyPin("0000") }
        val lock2 = manager.getLockoutRemainingMs()
        assertTrue(
            "Lockout #2 должен быть >= первого: lock1=$lock1 lock2=$lock2",
            lock2 >= lock1
        )
    }

    @Test
    fun setPin_afterLockout_resetsState() {
        manager.setPin("1234")
        repeat(5) { manager.verifyPin("0000") }
        assertTrue(manager.getLockoutRemainingMs() > 0)
        manager.setPin("9999")
        assertEquals(0, prefs.data["fail_count"])
        assertEquals(0L, prefs.data["last_fail_at"])
        assertEquals(0L, manager.getLockoutRemainingMs())
    }

    @Test
    fun removePin_disablesLock() {
        manager.setPin("1234")
        assertTrue(manager.isLockEnabled)
        manager.removePin()
        assertFalse(manager.isLockEnabled)
        assertNull(prefs.data["pin_hash_v2"])
        assertNull(prefs.data["pin_salt"])
    }

    @Test
    fun legacyHash_migratedOnFirstSuccessfulVerify() {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val legacyHashBytes = md.digest("1234".toByteArray(Charsets.UTF_8))
        val legacyHashHex = legacyHashBytes.joinToString("") { "%02x".format(it) }
        prefs.data["pin_hash"] = legacyHashHex

        assertTrue(manager.verifyPin("1234"))
        assertNotNull("Новый формат должен появиться", prefs.data["pin_hash_v2"])
        assertNotNull("Соль должна появиться", prefs.data["pin_salt"])
    }

    @Test
    fun legacyHash_wrongPin_doesNotMigrate() {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val legacyHashBytes = md.digest("1234".toByteArray(Charsets.UTF_8))
        val legacyHashHex = legacyHashBytes.joinToString("") { "%02x".format(it) }
        prefs.data["pin_hash"] = legacyHashHex

        assertFalse(manager.verifyPin("9999"))
        // Миграция только при успехе
        assertNull(prefs.data["pin_hash_v2"])
    }
}
