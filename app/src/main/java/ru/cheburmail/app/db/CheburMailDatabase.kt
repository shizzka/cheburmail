package ru.cheburmail.app.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ru.cheburmail.app.db.dao.ChatDao
import ru.cheburmail.app.db.dao.ContactDao
import ru.cheburmail.app.db.dao.MessageDao
import ru.cheburmail.app.db.dao.SendQueueDao
import ru.cheburmail.app.db.entity.ChatEntity
import ru.cheburmail.app.db.entity.ChatMemberEntity
import ru.cheburmail.app.db.entity.ContactEntity
import ru.cheburmail.app.db.entity.MessageEntity
import ru.cheburmail.app.db.entity.DeletedMessageEntity
import ru.cheburmail.app.db.entity.SendQueueEntity

@Database(
    entities = [
        ContactEntity::class,
        ChatEntity::class,
        ChatMemberEntity::class,
        MessageEntity::class,
        SendQueueEntity::class,
        DeletedMessageEntity::class
    ],
    version = 5,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class CheburMailDatabase : RoomDatabase() {

    abstract fun contactDao(): ContactDao
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun sendQueueDao(): SendQueueDao

    companion object {
        private const val DB_NAME = "cheburmail.db"

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

        @Volatile
        private var INSTANCE: CheburMailDatabase? = null

        fun getInstance(context: Context): CheburMailDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    CheburMailDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build().also { INSTANCE = it }
            }
    }
}
