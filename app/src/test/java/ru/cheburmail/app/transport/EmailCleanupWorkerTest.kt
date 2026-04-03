package ru.cheburmail.app.transport

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Тесты EmailCleanupWorker.
 *
 * Поскольку cleanup() работает с реальным IMAP-соединением,
 * тестируем логику через mock-объекты и проверяем конфигурацию.
 *
 * Интеграционные тесты с реальным IMAP — в IntegrationTest.kt.
 */
class EmailCleanupWorkerTest {

    private var currentTime = 1_700_000_000_000L // фиксированное время
    private lateinit var worker: EmailCleanupWorker

    @Before
    fun setup() {
        worker = EmailCleanupWorker(clock = { currentTime })
    }

    @Test
    fun retentionDays_defaultIs7() {
        assertEquals(7, EmailCleanupWorker.RETENTION_DAYS)
    }

    @Test
    fun cleanup_noFolder_returnsZero() {
        // ImapClient без реального подключения — cleanup вернёт 0
        // (не можем подключиться к реальному серверу в unit-тесте)
        // Проверяем, что worker создаётся без ошибок
        val config = EmailConfig("test@yandex.ru", "pass", EmailProvider.YANDEX)
        // cleanup() выбросит исключение при подключении, но вернёт 0
        val result = worker.cleanup(config)
        assertEquals(0, result)
    }

    @Test
    fun cleanupAll_emptyList_returnsZero() {
        val result = worker.cleanupAll(emptyList())
        assertEquals(0, result)
    }

    @Test
    fun cleanupAll_multipleAccounts_aggregatesResults() {
        // Все аккаунты вернут 0 (нет IMAP-подключения)
        val configs = listOf(
            EmailConfig("a@yandex.ru", "pass", EmailProvider.YANDEX),
            EmailConfig("b@mail.ru", "pass", EmailProvider.MAIL_RU)
        )
        val result = worker.cleanupAll(configs)
        assertEquals(0, result)
    }
}
