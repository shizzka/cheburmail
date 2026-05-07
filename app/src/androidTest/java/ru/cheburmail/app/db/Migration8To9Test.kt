package ru.cheburmail.app.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Тест миграции БД v8 → v9 (multi-email identity).
 *
 * Проверяет:
 * 1. Таблица contact_aliases создана
 * 2. Бэкфилл: для каждого contacts.email появилась PRIMARY-запись с тем же
 *    contact_id и created_at→added_at
 * 3. Уникальный индекс на email + индекс на contact_id созданы
 * 4. FK CASCADE: удаление contact удаляет связанные алиасы
 *
 * Используется в-memory FrameworkSQLiteOpenHelperFactory (без SQLCipher,
 * чтобы тест был быстрым и не требовал нативки).
 */
@RunWith(AndroidJUnit4::class)
class Migration8To9Test {

    private val DB_NAME = "test_migration_8_9.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CheburMailDatabase::class.java
    )

    @Test
    fun migrate8To9_createsTableAndBackfills() {
        // 1. Создаём v8 БД с двумя контактами
        helper.createDatabase(DB_NAME, 8).apply {
            execSQL(
                """
                INSERT INTO contacts (email, display_name, public_key, fingerprint, trust_status, created_at, updated_at)
                VALUES ('alice@mail.ru', 'Alice', X'010203', 'fp_alice', 'VERIFIED', 1000, 1000)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO contacts (email, display_name, public_key, fingerprint, trust_status, created_at, updated_at)
                VALUES ('bob@yandex.ru', 'Bob', X'040506', 'fp_bob', 'UNVERIFIED', 2000, 2000)
                """.trimIndent()
            )
            close()
        }

        // 2. Запускаем миграцию
        val db = helper.runMigrationsAndValidate(
            DB_NAME,
            9,
            true,
            CheburMailDatabase.MIGRATION_8_9
        )

        // 3. Проверяем что таблица создана и бэкфил отработал
        db.query("SELECT contact_id, email, source, added_at FROM contact_aliases ORDER BY contact_id").use { c ->
            assertTrue("contact_aliases должна содержать ≥2 записи", c.count >= 2)

            assertTrue(c.moveToFirst())
            val aliceContactId = c.getLong(0)
            assertEquals("alice@mail.ru", c.getString(1))
            assertEquals("PRIMARY", c.getString(2))
            assertEquals(1000L, c.getLong(3))

            assertTrue(c.moveToNext())
            val bobContactId = c.getLong(0)
            assertEquals("bob@yandex.ru", c.getString(1))
            assertEquals("PRIMARY", c.getString(2))
            assertEquals(2000L, c.getLong(3))

            // ID должны быть разные (контакты разные)
            assertTrue(aliceContactId != bobContactId)
        }

        // 4. Проверяем уникальный индекс на email
        db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='contact_aliases'").use { c ->
            val indices = mutableListOf<String>()
            while (c.moveToNext()) indices += c.getString(0)
            assertTrue(
                "Должен быть индекс на email: $indices",
                indices.any { it.contains("email") }
            )
            assertTrue(
                "Должен быть индекс на contact_id: $indices",
                indices.any { it.contains("contact_id") }
            )
        }

        // 5. FK CASCADE: удаляем contact → его алиасы должны исчезнуть
        // (Включаем foreign_keys явно — Room делает это при open, но через raw db нет)
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM contacts WHERE email = 'alice@mail.ru'")
        db.query("SELECT COUNT(*) FROM contact_aliases WHERE email = 'alice@mail.ru'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(
                "После CASCADE-удаления contact алиасы должны исчезнуть",
                0, c.getInt(0)
            )
        }
        // bob жив
        db.query("SELECT COUNT(*) FROM contact_aliases WHERE email = 'bob@yandex.ru'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }

        db.close()
    }

    @Test
    fun migrate8To9_emptyDatabase_noErrors() {
        // v8 без контактов — миграция должна работать
        helper.createDatabase(DB_NAME, 8).close()

        val db = helper.runMigrationsAndValidate(
            DB_NAME,
            9,
            true,
            CheburMailDatabase.MIGRATION_8_9
        )

        db.query("SELECT COUNT(*) FROM contact_aliases").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        db.close()
    }
}
