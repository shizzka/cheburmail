package ru.cheburmail.app.account

import android.util.Log
import kotlinx.coroutines.flow.first
import ru.cheburmail.app.repository.AccountRepository
import ru.cheburmail.app.transport.EmailConfig

/**
 * Управление несколькими email-аккаунтами.
 *
 * Поддерживает:
 * - Добавление/удаление аккаунтов (через AccountRepository)
 * - Round-robin выбор аккаунта для отправки
 * - Rate limit tracking per-account
 */
class MultiAccountManager(
    private val accountRepository: AccountRepository,
    private val rateLimitTracker: RateLimitTracker = RateLimitTracker()
) {

    private var lastUsedIndex = -1

    /**
     * Получить список всех аккаунтов.
     */
    suspend fun getAccounts(): List<EmailConfig> {
        return accountRepository.getAll().first()
    }

    /**
     * Добавить новый аккаунт.
     */
    suspend fun addAccount(config: EmailConfig) {
        accountRepository.save(config)
        Log.i(TAG, "Аккаунт ${config.email} добавлен")
    }

    /**
     * Удалить аккаунт.
     */
    suspend fun removeAccount(email: String) {
        accountRepository.delete(email)
        Log.i(TAG, "Аккаунт $email удалён")
    }

    /**
     * Получить следующий аккаунт для отправки (round-robin с учётом rate limit).
     *
     * Алгоритм:
     * 1. Получить список всех аккаунтов
     * 2. Найти аккаунт с наименьшим использованием, который не превысил лимит
     * 3. Если все превысили лимит — вернуть null
     *
     * @return EmailConfig для отправки или null если нет доступных аккаунтов
     */
    suspend fun getNextSendAccount(): EmailConfig? {
        val accounts = getAccounts()
        if (accounts.isEmpty()) {
            Log.w(TAG, "Нет доступных аккаунтов для отправки")
            return null
        }

        // Стратегия: выбрать аккаунт с минимальным использованием
        val emails = accounts.map { it.email }
        val bestEmail = rateLimitTracker.getLeastUsed(emails)

        if (bestEmail == null) {
            Log.w(TAG, "Все аккаунты превысили лимит отправки")
            return null
        }

        val config = accounts.find { it.email == bestEmail }
        Log.d(TAG, "Выбран аккаунт для отправки: $bestEmail " +
            "(использование: ${rateLimitTracker.getCount(bestEmail)})")
        return config
    }

    /**
     * Зафиксировать отправку через аккаунт (увеличить счётчик).
     */
    fun recordSend(email: String) {
        rateLimitTracker.increment(email)
    }

    /**
     * Проверить, может ли аккаунт отправлять.
     */
    fun canSend(email: String): Boolean {
        return rateLimitTracker.canSend(email)
    }

    /**
     * Получить статистику использования аккаунта.
     */
    fun getUsageCount(email: String): Int {
        return rateLimitTracker.getCount(email)
    }

    companion object {
        private const val TAG = "MultiAccountManager"
    }
}
