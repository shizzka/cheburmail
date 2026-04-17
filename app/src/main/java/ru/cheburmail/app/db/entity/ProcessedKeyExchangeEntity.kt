package ru.cheburmail.app.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Персистентная дедупликация keyex-писем по UUID.
 * Защищает от повторной обработки старых keyex после рестарта приложения.
 *
 * Индекс по `processed_at` — для ленивого GC через `deleteOlderThan()`:
 * без индекса full-scan проявляется когда таблица разрастается до тысяч записей.
 */
@Entity(
    tableName = "processed_keyex",
    indices = [Index(value = ["processed_at"], name = "idx_processed_keyex_at")]
)
data class ProcessedKeyExchangeEntity(
    @PrimaryKey
    @ColumnInfo(name = "kex_uuid")
    val kexUuid: String,

    @ColumnInfo(name = "processed_at")
    val processedAt: Long = System.currentTimeMillis()
)
