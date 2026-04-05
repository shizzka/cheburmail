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
import ru.cheburmail.app.db.entity.SendQueueEntity

@Database(
    entities = [
        ContactEntity::class,
        ChatEntity::class,
        ChatMemberEntity::class,
        MessageEntity::class,
        SendQueueEntity::class
    ],
    version = 2,
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
                    .addMigrations(MIGRATION_1_2)
                    .build().also { INSTANCE = it }
            }
    }
}
