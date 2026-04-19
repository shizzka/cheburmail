package ru.cheburmail.app.storage

import android.content.Context
import android.util.Base64
import java.io.File
import java.security.SecureRandom

/**
 * Хранит passphrase для SQLCipher-шифрования БД.
 *
 * 32 байта случайных данных (~256 бит энтропии) кодируются в base64
 * (44 символа), полученная строка используется как passphrase для PRAGMA key.
 * Файл `cheburmail_db_pass.bin` шифруется тем же Tink AEAD,
 * что и `EncryptedDataStoreFactory` — мастер-ключ в Android Keystore.
 *
 * Why: изоляция от X25519-ключа сообщений — потеря БД ≠ потеря E2E-ключа.
 * Why base64: SupportOpenHelperFactory треатит ByteArray как UTF-8-строку
 * passphrase, поэтому raw bytes неудобны (могут содержать NUL).
 *
 * If file/keyset is gone (data wipe, Keystore reset) — passphrase
 * пересоздаётся, БД станет нечитаемой → миграция упадёт → fallback в caller.
 */
object DbPassphraseStorage {

    private const val PASSPHRASE_FILE = "cheburmail_db_pass.bin"
    private const val PASSPHRASE_BYTES = 32
    private val ASSOCIATED_DATA = "cheburmail_db_passphrase".toByteArray()

    private fun file(context: Context): File =
        File(context.applicationContext.filesDir, PASSPHRASE_FILE)

    /**
     * Существует ли уже сохранённая passphrase.
     * Используется миграционной логикой: если passphrase нет — это первый
     * запуск с шифрованием, нужно генерировать и мигрировать БД.
     */
    fun exists(context: Context): Boolean = file(context).exists()

    /**
     * Получить или создать passphrase. Идемпотентно: повторный вызов
     * вернёт ту же passphrase.
     *
     * @return base64-кодированная строка из 32 случайных байт (44 символа).
     */
    @Synchronized
    fun getOrCreate(context: Context): String {
        val f = file(context)
        val aead = EncryptedDataStoreFactory.getAead(context)

        if (f.exists()) {
            val encrypted = f.readBytes()
            val plain = aead.decrypt(encrypted, ASSOCIATED_DATA)
            return String(plain, Charsets.UTF_8)
        }

        val raw = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
        val passphrase = Base64.encodeToString(raw, Base64.NO_WRAP)
        val plainBytes = passphrase.toByteArray(Charsets.UTF_8)
        val encrypted = aead.encrypt(plainBytes, ASSOCIATED_DATA)

        // atomic-ish write: tmp + rename
        val tmp = File(f.parentFile, "$PASSPHRASE_FILE.tmp")
        tmp.writeBytes(encrypted)
        if (!tmp.renameTo(f)) {
            tmp.delete()
            throw IllegalStateException("Не удалось сохранить passphrase БД")
        }
        return passphrase
    }
}
