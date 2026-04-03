package ru.cheburmail.app.messaging

import android.util.Log
import ru.cheburmail.app.db.MessageStatus
import ru.cheburmail.app.db.dao.MessageDao

/**
 * Обрабатывает входящие ACK (подтверждения доставки).
 *
 * При получении ACK обновляет статус оригинального сообщения на DELIVERED.
 * ACK определяется по наличию "ack-" в subject email.
 */
class DeliveryReceiptHandler(
    private val messageDao: MessageDao
) {

    /**
     * Обработать входящий ACK.
     *
     * @param subject subject email-сообщения (формат: CM/1/<chatId>/ack-<msgUuid>)
     * @return true если ACK обработан успешно, false если формат невалидный или сообщение не найдено
     */
    suspend fun handleAck(subject: String): Boolean {
        val originalMsgUuid = DeliveryReceiptSender.extractOriginalMsgUuid(subject)
        if (originalMsgUuid == null) {
            Log.w(TAG, "Невалидный формат ACK subject: $subject")
            return false
        }

        // Проверяем существование оригинального сообщения
        val message = messageDao.getByIdOnce(originalMsgUuid)
        if (message == null) {
            Log.w(TAG, "Сообщение $originalMsgUuid не найдено для ACK")
            return false
        }

        // Обновляем статус только если текущий статус — SENT
        // (DELIVERED не перезаписываем, FAILED не трогаем)
        if (message.status == MessageStatus.SENT) {
            messageDao.updateStatus(originalMsgUuid, MessageStatus.DELIVERED)
            Log.i(TAG, "Сообщение $originalMsgUuid обновлено -> DELIVERED")
            return true
        }

        Log.d(TAG, "Сообщение $originalMsgUuid имеет статус ${message.status}, ACK пропущен")
        return false
    }

    companion object {
        private const val TAG = "DeliveryReceiptHandler"
    }
}
