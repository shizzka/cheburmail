package ru.cheburmail.app.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Персистентная дедупликация keyex-писем по UUID.
 * Защищает от повторной обработки старых keyex после рестарта приложения.
 */
@Entity(tableName = "processed_keyex")
data class ProcessedKeyExchangeEntity(
    @PrimaryKey
    @ColumnInfo(name = "kex_uuid")
    val kexUuid: String,

    @ColumnInfo(name = "processed_at")
    val processedAt: Long = System.currentTimeMillis()
)
