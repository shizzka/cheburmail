package ru.cheburmail.app.db

import android.content.Context
import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * Одноразовая миграция БД из plaintext в SQLCipher-encrypted формат.
 *
 * Поток:
 * 1. Если флаг `db_cipher_migrated_v1=true` → миграция уже сделана, выход.
 * 2. Если plaintext-файла нет → свежая установка, ставим флаг и выходим
 *    (Room сразу создаст encrypted).
 * 3. Открываем plaintext SQLiteDatabase через SQLCipher API с пустым ключом.
 * 4. ATTACH DATABASE ... KEY '<pass>' + SELECT sqlcipher_export('encrypted').
 * 5. DETACH, close, удаляем plaintext + WAL/SHM, переименовываем encrypted.
 * 6. Ставим флаг.
 *
 * Failure:
 * - Любая ошибка → удаляем encrypted временный файл, флаг НЕ ставим,
 *   plaintext остаётся нетронутым. Caller получит exception → может
 *   решить: повторить позже или fallback на новую БД.
 */
object DbCipherMigrator {

    private const val TAG = "DbCipherMigrator"
    private const val PREFS = "cheburmail_db_state"
    private const val FLAG_MIGRATED = "db_cipher_migrated_v1"
    private const val DB_NAME = "cheburmail.db"
    private const val ENCRYPTED_TMP = "cheburmail.db.encrypted"

    fun isMigrated(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(FLAG_MIGRATED, false)

    private fun setMigrated(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(FLAG_MIGRATED, true)
            .apply()
    }

    /**
     * Идемпотентно мигрирует cheburmail.db из plaintext в SQLCipher-encrypted.
     * Должна вызываться ДО открытия БД через Room.
     */
    fun migrateIfNeeded(context: Context, passphrase: String) {
        if (isMigrated(context)) return

        val plaintextFile = context.getDatabasePath(DB_NAME)
        if (!plaintextFile.exists()) {
            // Свежая установка — Room сразу создаст encrypted БД с переданной passphrase.
            setMigrated(context)
            Log.i(TAG, "no plaintext DB; flagging as migrated")
            return
        }

        // Гарантируем, что директория существует (на всякий случай).
        plaintextFile.parentFile?.mkdirs()
        val encryptedTmp = File(plaintextFile.parentFile, ENCRYPTED_TMP)
        if (encryptedTmp.exists()) {
            encryptedTmp.delete()
        }

        // SQLCipher native libs.
        System.loadLibrary("sqlcipher")

        // 1. Pre-create encrypted target file: SQLCipher ATTACH won't create it.
        try {
            val seed = SQLiteDatabase.openOrCreateDatabase(
                encryptedTmp.absolutePath,
                passphrase.toByteArray(Charsets.UTF_8),
                null,
                null
            )
            seed.close()
        } catch (e: Throwable) {
            Log.e(TAG, "failed to create encrypted target file", e)
            encryptedTmp.delete()
            throw e
        }

        var db: SQLiteDatabase? = null
        try {
            // Open plaintext (empty password = no encryption).
            db = SQLiteDatabase.openDatabase(
                plaintextFile.absolutePath,
                ByteArray(0),
                null,
                SQLiteDatabase.OPEN_READWRITE,
                null
            )

            // Flush WAL into main DB so sqlcipher_export sees latest data.
            db.rawQuery("PRAGMA wal_checkpoint(FULL)", emptyArray()).use { it.moveToNext() }

            // ATTACH the now-existing encrypted DB with passphrase.
            // base64 passphrase contains only safe chars; literal is safe.
            db.execSQL(
                "ATTACH DATABASE '${encryptedTmp.absolutePath}' AS encrypted KEY '$passphrase'"
            )

            // sqlcipher_export копирует все таблицы и индексы из main в encrypted.
            db.rawQuery("SELECT sqlcipher_export('encrypted')", emptyArray()).use {
                it.moveToNext()
            }

            // Перенесём user_version (Room хранит schemaVersion здесь).
            val userVersion = db.rawQuery("PRAGMA user_version", emptyArray()).use { c ->
                if (c.moveToNext()) c.getInt(0) else 0
            }
            db.execSQL("PRAGMA encrypted.user_version = $userVersion")

            db.execSQL("DETACH DATABASE encrypted")
            db.close()
            db = null
        } catch (e: Throwable) {
            Log.e(TAG, "migration failed, leaving plaintext intact", e)
            try { db?.close() } catch (_: Throwable) {}
            encryptedTmp.delete()
            throw e
        }

        // Переименуем encrypted в основной + удалим plaintext WAL/SHM.
        val backup = File(plaintextFile.parentFile, "$DB_NAME.plaintext.bak")
        try {
            // 1. plaintext → backup (для отладки/восстановления при крахе rename)
            if (backup.exists()) backup.delete()
            if (!plaintextFile.renameTo(backup)) {
                throw IllegalStateException("Не удалось переместить plaintext в .bak")
            }
            // 2. encrypted → основной
            if (!encryptedTmp.renameTo(plaintextFile)) {
                // откат: вернём backup
                backup.renameTo(plaintextFile)
                throw IllegalStateException("Не удалось переименовать encrypted в основную БД")
            }
            // 3. Удалим WAL/SHM от plaintext (от encrypted Room сам пересоздаст).
            File(plaintextFile.parentFile, "$DB_NAME-wal").delete()
            File(plaintextFile.parentFile, "$DB_NAME-shm").delete()
            // 4. Удалим backup. Если упадёт питание — на следующем запуске видим
            //    что plaintext уже зашифрован (будет fail decrypt пустым ключом),
            //    флаг не стоит → пробуем повторить → но encrypted уже на месте.
            //    Поэтому ставим флаг ДО удаления backup, чтобы повторного цикла
            //    миграции не было.
            setMigrated(context)
            backup.delete()
            Log.i(TAG, "migration complete")
        } catch (e: Throwable) {
            Log.e(TAG, "rename phase failed", e)
            // НЕ ставим флаг → следующий запуск повторит, но encrypted уже там.
            // Caller может детектировать частичное состояние и сделать fallback.
            throw e
        }
    }
}
