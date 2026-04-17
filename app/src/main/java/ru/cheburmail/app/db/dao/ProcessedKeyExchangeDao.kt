package ru.cheburmail.app.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ru.cheburmail.app.db.entity.ProcessedKeyExchangeEntity

@Dao
interface ProcessedKeyExchangeDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ProcessedKeyExchangeEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM processed_keyex WHERE kex_uuid = :kexUuid)")
    suspend fun exists(kexUuid: String): Boolean

    @Query("DELETE FROM processed_keyex WHERE processed_at < :threshold")
    suspend fun deleteOlderThan(threshold: Long): Int
}
