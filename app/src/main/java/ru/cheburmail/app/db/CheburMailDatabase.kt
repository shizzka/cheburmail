package ru.cheburmail.app.db

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import ru.cheburmail.app.storage.DbPassphraseStorage
import ru.cheburmail.app.db.dao.ChatDao
import ru.cheburmail.app.db.dao.ContactDao
import ru.cheburmail.app.db.dao.MessageDao
import ru.cheburmail.app.db.dao.PendingAddRequestDao
import ru.cheburmail.app.db.dao.ProcessedKeyExchangeDao
import ru.cheburmail.app.db.dao.SendQueueDao
import ru.cheburmail.app.db.entity.ChatEntity
import ru.cheburmail.app.db.entity.ChatMemberEntity
import ru.cheburmail.app.db.entity.ContactEntity
import ru.cheburmail.app.db.entity.MessageEntity
import ru.cheburmail.app.db.entity.DeletedMessageEntity
import ru.cheburmail.app.db.entity.PendingAddRequestEntity
import ru.cheburmail.app.db.entity.ProcessedKeyExchangeEntity
import ru.cheburmail.app.db.entity.SendQueueEntity

@Database(
    entities = [
        ContactEntity::class,
        ChatEntity::class,
        ChatMemberEntity::class,
        MessageEntity::class,
        SendQueueEntity::class,
        DeletedMessageEntity::class,
        ProcessedKeyExchangeEntity::class,
        PendingAddRequestEntity::class
    ],
    version = 8,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class CheburMailDatabase : RoomDatabase() {

    abstract fun contactDao(): ContactDao
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun sendQueueDao(): SendQueueDao
    abstract fun processedKeyExchangeDao(): ProcessedKeyExchangeDao
    abstract fun pendingAddRequestDao(): PendingAddRequestDao

    companion object {
        private const val DB_NAME = "cheburmail.db"

        /**
         * v8: добавление колонки chats.created_by (admin email, NULL для старых
         * групп и direct-чатов) и таблицы pending_add_requests для approval-флоу
         * добавления участников verified-не-админами.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chats ADD COLUMN created_by TEXT DEFAULT NULL")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pending_add_requests (
                        chat_id TEXT NOT NULL,
                        target_email TEXT NOT NULL,
                        requester_email TEXT NOT NULL,
                        target_public_key BLOB NOT NULL,
                        target_display_name TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        PRIMARY KEY(chat_id, target_email),
                        FOREIGN KEY(chat_id) REFERENCES chats(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_pending_add_at " +
                        "ON pending_add_requests(created_at)"
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Индекс для ленивого GC processed_keyex по processed_at:
                // deleteOlderThan() без индекса делает full-scan после накопления
                // нескольких тысяч записей → заметные лаги на poll.
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_processed_keyex_at " +
                        "ON processed_keyex(processed_at)"
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS processed_keyex (
                        kex_uuid TEXT NOT NULL PRIMARY KEY,
                        processed_at INTEGER NOT NULL
                    )
                """)
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS deleted_messages (
                        message_id TEXT NOT NULL PRIMARY KEY,
                        deleted_at INTEGER NOT NULL
                    )
                """)
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN reply_to_id TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN reply_to_text TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE send_queue ADD COLUMN payload_file_path TEXT DEFAULT NULL")
                // Clean up stuck entries with oversized BLOBs that can't be read by CursorWindow
                db.execSQL("DELETE FROM send_queue WHERE LENGTH(encrypted_payload) > 1000000")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN media_type TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE messages ADD COLUMN local_media_uri TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN file_name TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN file_size INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN mime_type TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN thumbnail_uri TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN voice_duration_ms INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN waveform_data TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN media_download_status TEXT NOT NULL DEFAULT 'NONE'")
            }
        }

        private const val TAG = "CheburMailDatabase"

        @Volatile
        private var INSTANCE: CheburMailDatabase? = null

        fun getInstance(context: Context): CheburMailDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildEncrypted(context.applicationContext)
                    .also { INSTANCE = it }
            }

        private fun buildEncrypted(context: Context): CheburMailDatabase {
            val passphrase = DbPassphraseStorage.getOrCreate(context)

            // SQLCipher native libs: грузим ДО SupportOpenHelperFactory.
            // Мигратор грузит свою копию только если миграция ещё нужна;
            // после setMigrated=true этот путь не вызывается — Room бы
            // открыл БД без нативки и упал UnsatisfiedLinkError.
            System.loadLibrary("sqlcipher")

            try {
                DbCipherMigrator.migrateIfNeeded(context, passphrase)
            } catch (e: Throwable) {
                // Миграция упала — Room попробует открыть старый plaintext-файл
                // с шифрованным ключом и тоже упадёт. Логируем и пробрасываем,
                // приложение упадёт явно, бэкап `cheburmail.db.plaintext.bak`
                // (если успел создаться) останется на диске для восстановления.
                Log.e(TAG, "DbCipherMigrator failed; DB will likely fail to open", e)
            }

            val factory = SupportOpenHelperFactory(passphrase.toByteArray(Charsets.UTF_8))

            return Room.databaseBuilder(
                context,
                CheburMailDatabase::class.java,
                DB_NAME
            )
                .openHelperFactory(factory)
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                    MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                    MIGRATION_7_8
                )
                .build()
        }
    }
}
