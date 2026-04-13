package ru.cheburmail.app.messaging

import android.util.Log
import ru.cheburmail.app.crypto.MessageEncryptor
import ru.cheburmail.app.crypto.model.EncryptedEnvelope
import ru.cheburmail.app.db.dao.ContactDao
import ru.cheburmail.app.transport.EmailConfig
import ru.cheburmail.app.transport.EmailFormatter
import ru.cheburmail.app.transport.EmailMessage
import ru.cheburmail.app.transport.SmtpClient
import ru.cheburmail.app.transport.TransportException

/**
 * Отправляет ACK (подтверждение доставки) при получении сообщения.
 *
 * Формат ACK:
 * - Subject: CM/1/<chatId>/ack-<originalMsgUuid>
 * - Body: зашифрованный JSON {"type":"ack","msgId":"<uuid>","timestamp":<millis>}
 *
 * ACK шифруется public key отправителя и private key получателя,
 * чтобы только оригинальный отправитель мог его прочитать.
 */
class DeliveryReceiptSender(
    private val smtpClient: SmtpClient,
    private val encryptor: MessageEncryptor,
    private val contactDao: ContactDao,
    private val senderPrivateKey: ByteArray
) {

    /**
     * Отправить ACK для полученного сообщения.
     *
     * @param config SMTP-конфигурация для отправки
     * @param chatId ID чата, в котором получено сообщение
     * @param originalMsgUuid UUID оригинального сообщения
     * @param senderEmail email отправителя оригинального сообщения (получатель ACK)
     * @param timestamp время получения сообщения
     */
    suspend fun sendAck(
        config: EmailConfig,
        chatId: String,
        originalMsgUuid: String,
        senderEmail: String,
        timestamp: Long = System.currentTimeMillis()
    ) {
        try {
            // Получаем публичный ключ отправителя для шифрования ACK
            val contact = contactDao.getByEmail(senderEmail) ?: run {
                Log.w(TAG, "Контакт не найден, ACK не отправлен")
                return
            }

            // Формируем JSON тела ACK
            val ackJson = """{"type":"ack","msgId":"$originalMsgUuid","timestamp":$timestamp}"""
            val ackBytes = ackJson.toByteArray(Charsets.UTF_8)

            // Шифруем ACK
            val envelope = encryptor.encrypt(
                message = ackBytes,
                recipientPublicKey = contact.publicKey,
                senderPrivateKey = senderPrivateKey
            )

            // Формируем email
            val subject = "${EmailMessage.SUBJECT_PREFIX}$chatId/$ACK_PREFIX$originalMsgUuid"
            val base64Body = java.util.Base64.getEncoder().withoutPadding().encode(envelope.toBytes())

            val emailMessage = EmailMessage(
                from = config.email,
                to = senderEmail,
                subject = subject,
                body = base64Body,
                contentType = EmailMessage.CHEBURMAIL_CONTENT_TYPE
            )

            smtpClient.send(config, emailMessage)
            Log.i(TAG, "ACK отправлен")

        } catch (e: TransportException.SmtpException) {
            Log.e(TAG, "Ошибка SMTP при отправке ACK для $originalMsgUuid: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка отправки ACK для $originalMsgUuid: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "DeliveryReceiptSender"
        const val ACK_PREFIX = "ack-"

        /**
         * Проверяет, является ли subject ACK-сообщением.
         */
        fun isAckSubject(subject: String): Boolean {
            if (!subject.startsWith(EmailMessage.SUBJECT_PREFIX)) return false
            val parts = subject.removePrefix(EmailMessage.SUBJECT_PREFIX).split("/")
            return parts.size == 2 && parts[1].startsWith(ACK_PREFIX)
        }

        /**
         * Извлекает UUID оригинального сообщения из ACK subject.
         *
         * @return UUID оригинального сообщения или null если формат невалидный
         */
        fun extractOriginalMsgUuid(subject: String): String? {
            if (!isAckSubject(subject)) return null
            val parts = subject.removePrefix(EmailMessage.SUBJECT_PREFIX).split("/")
            return parts[1].removePrefix(ACK_PREFIX).takeIf { it.isNotBlank() }
        }

        /**
         * Извлекает chatId из ACK subject.
         */
        fun extractChatId(subject: String): String? {
            if (!isAckSubject(subject)) return null
            val parts = subject.removePrefix(EmailMessage.SUBJECT_PREFIX).split("/")
            return parts[0].takeIf { it.isNotBlank() }
        }
    }
}
