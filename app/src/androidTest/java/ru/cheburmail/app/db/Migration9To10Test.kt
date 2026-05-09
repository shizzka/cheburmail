package ru.cheburmail.app.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Тест миграции v9 → v10: добавление индекса на contacts.public_key.
 * Используется новым DAO-запросом getByPublicKey (multi-email identity).
 */
@RunWith(AndroidJUnit4::class)
class Migration9To10Test {

    private val DB_NAME = "test_migration_9_10.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CheburMailDatabase::class.java
    )

    @Test
    fun migrate9To10_createsIndexOnPublicKey() {
        // 1. Создаём v9 БД через цепочку миграций (v1→v9). Сразу с v8 → v9
        // через MigrationTestHelper не получится — нам нужна базовая v9
        // структура. Используем createDatabase(9) который применит
        // последовательно все миграции из addMigrations.
        helper.createDatabase(DB_NAME, 9).apply {
            execSQL(
                """
                INSERT INTO contacts (email, display_name, public_key, fingerprint, trust_status, created_at, updated_at)
                VALUES ('alice@mail.ru', 'Alice', X'010203', 'fp_alice', 'VERIFIED', 1000, 1000)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            DB_NAME,
            10,
            true,
            CheburMailDatabase.MIGRATION_9_10
        )

        // Проверяем что индекс на public_key создан
        db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='contacts'"
        ).use { c ->
            val indices = mutableListOf<String>()
            while (c.moveToNext()) indices += c.getString(0)
            assertTrue(
                "Должен быть индекс на public_key: $indices",
                indices.any { it.contains("public_key") }
            )
        }

        // SELECT по public_key должен использовать индекс (не full scan).
        // Через EXPLAIN QUERY PLAN — должны увидеть USING INDEX.
        db.query(
            "EXPLAIN QUERY PLAN SELECT * FROM contacts WHERE public_key = X'010203'"
        ).use { c ->
            val plans = mutableListOf<String>()
            while (c.moveToNext()) {
                val detail = c.getString(c.getColumnIndexOrThrow("detail"))
                plans += detail
            }
            assertTrue(
                "QUERY PLAN должен использовать индекс: $plans",
                plans.any { it.contains("INDEX") || it.contains("idx") || it.contains("public_key") }
            )
        }

        db.close()
    }
}
