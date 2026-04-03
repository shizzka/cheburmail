package ru.cheburmail.app.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
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
    version = 1,
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

        @Volatile
        private var INSTANCE: CheburMailDatabase? = null

        fun getInstance(context: Context): CheburMailDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    CheburMailDatabase::class.java,
                    DB_NAME
                ).build().also { INSTANCE = it }
            }
    }
}
