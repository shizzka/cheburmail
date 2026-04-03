package ru.cheburmail.app.transport

/**
 * Exponential backoff for SMTP/IMAP errors.
 *
 * Delays: 1 min, 2 min, 4 min, 8 min, 16 min (5 retries total).
 * After 5 failed attempts the message is marked FAILED.
 */
class RetryStrategy(
    private val maxRetries: Int = MAX_RETRIES,
    private val baseDelayMs: Long = BASE_DELAY_MS
) {

    /**
     * Calculate the delay before the next retry attempt.
     *
     * @param retryCount current attempt number (0-based)
     * @return delay in milliseconds, or null if retries are exhausted
     */
    fun nextDelay(retryCount: Int): Long? {
        if (retryCount >= maxRetries) return null
        return baseDelayMs * (1L shl retryCount)
    }

    /**
     * Check whether another retry is allowed.
     */
    fun canRetry(retryCount: Int): Boolean = retryCount < maxRetries

    companion object {
        const val MAX_RETRIES = 5
        const val BASE_DELAY_MS = 60_000L // 1 minute
    }
}
