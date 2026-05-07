package ru.cheburmail.app.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Дополнительный email-алиас контакта.
 *
 * Один pub_key (= одна identity на стороне отправителя) может быть привязан к
 * нескольким email-адресам, потому что мы можем отправлять с разных аккаунтов
 * (mail.ru, yandex и т.п.). Получатель учится новым адресам автоматически —
 * либо passive (получено письмо с pub_key match, но новый From:), либо через
 * X-CheburMail-Aliases в зашифрованном envelope.
 *
 * `contacts.email` остаётся primary email (для backward compat и UI как
 * «основной»). Все остальные адреса — здесь.
 */
@Entity(
    tableName = "contact_aliases",
    primaryKeys = ["contact_id", "email"],
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["id"],
            childColumns = ["contact_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["email"], unique = true),
        Index(value = ["contact_id"])
    ]
)
data class ContactAliasEntity(
    @ColumnInfo(name = "contact_id")
    val contactId: Long,

    @ColumnInfo(name = "email")
    val email: String,

    @ColumnInfo(name = "source")
    val source: String, // "PRIMARY" | "LEARNED" | "MANUAL"

    @ColumnInfo(name = "added_at")
    val addedAt: Long
) {
    companion object {
        const val SOURCE_PRIMARY = "PRIMARY"
        const val SOURCE_LEARNED = "LEARNED"
        const val SOURCE_MANUAL = "MANUAL"
    }
}
