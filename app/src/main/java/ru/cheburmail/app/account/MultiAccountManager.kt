package ru.cheburmail.app.account

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    /** Источник списка аккаунтов. В проде — AccountRepository.getAll().first() */
    private val accountSource: suspend () -> List<EmailConfig>,
    private val rateLimitTracker: RateLimitTracker = RateLimitTracker(),
    private val healthTracker: SmtpHealthTracker = SmtpHealthTracker()
) {

    /** Конструктор для прода — оборачивает AccountRepository в accountSource. */
    constructor(
        accountRepository: AccountRepository,
        rateLimitTracker: RateLimitTracker = RateLimitTracker(),
        healthTracker: SmtpHealthTracker = SmtpHealthTracker()
    ) : this(
        accountSource = { accountRepository.getAll().first() },
        rateLimitTracker = rateLimitTracker,
        healthTracker = healthTracker
    )

    private var lastUsedIndex = -1

    /** Доступ к health-трекеру — для интеграции SendWorker и UI. */
    fun health(): SmtpHealthTracker = healthTracker

    /**
     * События отправки — для snackbar и UI-индикаторов. Replay=0,
     * extraBufferCapacity=8 (DROP_OLDEST) чтобы не блокировать SendWorker
     * если UI отвалился.
     */
    private val _events = MutableSharedFlow<SendEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<SendEvent> = _events.asSharedFlow()

    /** Эмитит событие. Вызывается из SendWorker. */
    fun emit(event: SendEvent) {
        _events.tryEmit(event)
    }

    sealed class SendEvent {
        /** Сработал авто-fallback с одного аккаунта на другой. */
        data class FallbackUsed(
            val originalAccount: String,
            val fallbackAccount: String,
            val reason: String
        ) : SendEvent()

        /** Все аккаунты больны — отправка не прошла даже через fallback. */
        data class AllAccountsFailed(val originalAccount: String, val reason: String) : SendEvent()
    }

    /**
     * Получить список всех аккаунтов.
     */
    suspend fun getAccounts(): List<EmailConfig> {
        return accountSource()
    }

    /**
     * Получить следующий аккаунт для отправки.
     *
     * Алгоритм (по убыванию приоритета):
     * 1. Отфильтровать аккаунты, не превысившие rate limit (`canSend`).
     * 2. Из оставшихся отфильтровать «здоровые» (`healthTracker.isHealthy`),
     *    т.е. без недавнего SMTP-фейла или с истёкшим карантином.
     * 3. Из здоровых выбрать с минимальным rate-limit-счётчиком.
     * 4. Если здоровых нет — берём наименее «больного» (с истекающим карантином),
     *    чтобы не блокировать отправку полностью при глобальной блокировке.
     *
     * @param exclude email, которые НЕ подходят (например, primary только что
     *   упал — pop'аем со списка для немедленного fallback'а).
     */
    suspend fun getNextSendAccount(exclude: Set<String> = emptySet()): EmailConfig? {
        val accounts = getAccounts().filter { it.email !in exclude }
        if (accounts.isEmpty()) {
            Log.w(TAG, "Нет доступных аккаунтов для отправки (exclude=$exclude)")
            return null
        }

        val rateOk = accounts.filter { rateLimitTracker.canSend(it.email) }
        if (rateOk.isEmpty()) {
            Log.w(TAG, "Все аккаунты превысили лимит отправки")
            return null
        }

        val healthy = rateOk.filter { healthTracker.isHealthy(it.email) }
        val pool = healthy.ifEmpty {
            Log.w(TAG, "Нет здоровых аккаунтов, fallback на наименее больной")
            rateOk.sortedBy { healthTracker.quarantineRemainingMs(it.email) }
        }

        val best = pool.minByOrNull { rateLimitTracker.getCount(it.email) }
        if (best != null) {
            Log.d(TAG, "Выбран аккаунт для отправки: ${best.email} " +
                "(rate=${rateLimitTracker.getCount(best.email)}, " +
                "healthy=${healthTracker.isHealthy(best.email)})")
        }
        return best
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
