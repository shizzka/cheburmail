package ru.cheburmail.app.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import ru.cheburmail.app.db.entity.ContactAliasEntity
import ru.cheburmail.app.db.entity.ContactEntity

@Dao
interface ContactDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(contact: ContactEntity): Long

    @Query("SELECT * FROM contacts WHERE id = :id")
    suspend fun getById(id: Long): ContactEntity?

    @Query("SELECT * FROM contacts WHERE email = :email")
    suspend fun getByEmail(email: String): ContactEntity?

    /**
     * Поиск контакта по email с учётом алиасов: сначала по primary email из
     * contacts.email, затем по contact_aliases.email. Используется при
     * обработке входящих писем — From: может быть alias.
     */
    @Query(
        """
        SELECT c.* FROM contacts c
        WHERE c.email = :email
        UNION
        SELECT c.* FROM contacts c
        INNER JOIN contact_aliases a ON a.contact_id = c.id
        WHERE a.email = :email
        LIMIT 1
        """
    )
    suspend fun getByEmailOrAlias(email: String): ContactEntity?

    /**
     * Поиск контакта по публичному ключу. Используется как primary identity
     * matcher для входящих писем — From: может прийти с любого alias, но
     * pub_key должен совпасть с известным контактом.
     */
    @Query("SELECT * FROM contacts WHERE public_key = :publicKey LIMIT 1")
    suspend fun getByPublicKey(publicKey: ByteArray): ContactEntity?

    @Query("SELECT * FROM contacts ORDER BY display_name ASC")
    fun getAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts ORDER BY display_name ASC")
    suspend fun getAllOnce(): List<ContactEntity>

    @Update
    suspend fun update(contact: ContactEntity)

    @Delete
    suspend fun delete(contact: ContactEntity)

    @Query("DELETE FROM contacts WHERE id = :id")
    suspend fun deleteById(id: Long)

    // ---- Aliases ----

    /**
     * Добавить email-алиас контакту. Если такой email уже привязан к этому
     * контакту — IGNORE (no-op). Если привязан к другому контакту — ABORT
     * (Room бросит SQLiteConstraintException, ловим выше).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlias(alias: ContactAliasEntity): Long

    @Query("SELECT * FROM contact_aliases WHERE contact_id = :contactId ORDER BY added_at ASC")
    suspend fun getAliases(contactId: Long): List<ContactAliasEntity>

    @Query("SELECT email FROM contact_aliases WHERE contact_id = :contactId ORDER BY added_at ASC")
    suspend fun getAliasEmails(contactId: Long): List<String>

    @Query("SELECT * FROM contact_aliases WHERE email = :email LIMIT 1")
    suspend fun getAliasByEmail(email: String): ContactAliasEntity?

    @Query("DELETE FROM contact_aliases WHERE contact_id = :contactId AND email = :email")
    suspend fun deleteAlias(contactId: Long, email: String)
}
