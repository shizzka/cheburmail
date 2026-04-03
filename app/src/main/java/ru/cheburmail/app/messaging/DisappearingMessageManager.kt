package ru.cheburmail.app.messaging

import android.util.Log
import ru.cheburmail.app.db.dao.ChatDao
import ru.cheburmail.app.db.dao.MessageDao

/**
 * Управление исчезающими сообщениями.
 *
 * Каждый чат может иметь таймер автоудаления (disappearTimerMs).
 * Новые сообщения получают expiresAt = timestamp + timer.
 * Периодическая очистка удаляет сообщения с истёкшим expiresAt.
 *
 * Запуск очистки: WorkManager periodic (1 час).
 */
class DisappearingMessageManager(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao
) {

    /**
     * Установить таймер исчезающих сообщений для чата.
     *
     * @param chatId ID чата
     * @param durationMs длительность в миллисекундах, null для отключения
     */
    suspend fun setTimer(chatId: String, durationMs: Long?) {
        val chat = chatDao.getById(chatId) ?: run {
            Log.w(TAG, "Чат $chatId не найден для установки таймера")
            return
        }

        val updatedChat = chat.copy(
            disappearTimerMs = durationMs,
            updatedAt = System.currentTimeMillis()
        )
        chatDao.update(updatedChat)

        if (durationMs != null) {
            Log.i(TAG, "Таймер исчезающих сообщений для чата $chatId: ${durationMs / 1000}с")
        } else {
            Log.i(TAG, "Таймер исчезающих сообщений для чата $chatId отключён")
        }
    }

    /**
     * Получить текущий таймер исчезающих сообщений для чата.
     *
     * @param chatId ID чата
     * @return длительность в миллисекундах, null если таймер не установлен
     */
    suspend fun getTimer(chatId: String): Long? {
        return chatDao.getById(chatId)?.disappearTimerMs
    }

    /**
     * Рассчитать expiresAt для нового сообщения в чате.
     *
     * @param chatId ID чата
     * @param messageTimestamp время создания сообщения
     * @return timestamp истечения или null если таймер не установлен
     */
    suspend fun calculateExpiresAt(chatId: String, messageTimestamp: Long): Long? {
        val timer = getTimer(chatId) ?: return null
        return messageTimestamp + timer
    }

    /**
     * Удалить все сообщения с истёкшим expiresAt.
     * Вызывается периодически через WorkManager.
     *
     * @return количество удалённых сообщений
     */
    suspend fun cleanup(): Int {
        val now = System.currentTimeMillis()
        val deleted = messageDao.deleteExpired(now)
        if (deleted > 0) {
            Log.i(TAG, "Очистка: удалено $deleted исчезающих сообщений")
        }
        return deleted
    }

    companion object {
        private const val TAG = "DisappearingMsgManager"

        /** Предустановленные таймеры для UI выбора */
        val PRESET_TIMERS = mapOf(
            "Выкл" to null,
            "5 минут" to 5 * 60 * 1000L,
            "1 час" to 60 * 60 * 1000L,
            "24 часа" to 24 * 60 * 60 * 1000L,
            "7 дней" to 7 * 24 * 60 * 60 * 1000L,
            "30 дней" to 30L * 24 * 60 * 60 * 1000L
        )
    }
}
