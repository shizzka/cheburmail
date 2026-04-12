package ru.cheburmail.app.messaging

import android.util.Log
import ru.cheburmail.app.crypto.FingerprintGenerator
import ru.cheburmail.app.db.TrustStatus
import ru.cheburmail.app.db.dao.ContactDao
import ru.cheburmail.app.db.entity.ContactEntity
import ru.cheburmail.app.storage.SecureKeyStorage
import ru.cheburmail.app.transport.EmailConfig
import ru.cheburmail.app.transport.EmailMessage
import ru.cheburmail.app.transport.SmtpClient
import ru.cheburmail.app.transport.TransportException
import ru.cheburmail.app.notification.NotificationHelper
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Обмен публичными ключами через email.
 *
 * Формат:
 * - Subject: CM/1/KEYEX/kex-<uuid>
 * - Body: JSON {"email":"...", "publicKey":"<base64>", "displayName":"..."}
 * - Content-Type: application/x-cheburmail-keyex
 *
 * Протокол:
 * 1. User A вводит email User B → отправляет свой публичный ключ
 * 2. User B получает → создаёт контакт (UNVERIFIED) → автоматически отправляет свой ключ назад
 * 3. User A получает ответ → создаёт контакт (UNVERIFIED) → чат готов
 *
 * Безопасность:
 * - VERIFIED контакты: смена ключа НЕ принимается автоматически (защита от MITM)
 * - UNVERIFIED контакты: ключ обновляется, но показывается предупреждение
 * - Дедупликация по UUID key exchange сообщений (защита от replay)
 */
class KeyExchangeManager(
    private val smtpClient: SmtpClient,
    private val contactDao: ContactDao,
    private val keyStorage: SecureKeyStorage,
    private val notificationHelper: NotificationHelper? = null
) {

    /** Кэш обработанных UUID key exchange для защиты от replay-атак */
    private val processedKexUuids = ConcurrentHashMap<String, Long>()

    /**
     * Отправить свой публичный ключ на указанный email.
     */
    suspend fun sendKeyExchange(config: EmailConfig, targetEmail: String) {
        val publicKey = keyStorage.getPublicKey()
            ?: throw IllegalStateException("Публичный ключ не найден")

        val json = JSONObject().apply {
            put("email", config.email)
            put("publicKey", java.util.Base64.getEncoder().encodeToString(publicKey))
            put("displayName", config.email.substringBefore('@'))
        }

        val uuid = UUID.randomUUID().toString()
        val subject = "${EmailMessage.SUBJECT_PREFIX}$KEYEX_CHAT_ID/$KEX_PREFIX$uuid"

        val emailMessage = EmailMessage(
            from = config.email,
            to = targetEmail,
            subject = subject,
            body = json.toString().toByteArray(Charsets.UTF_8),
            contentType = EmailMessage.CHEBURMAIL_CONTENT_TYPE
        )

        smtpClient.send(config, emailMessage)
        Log.i(TAG, "Key exchange отправлен: ${config.email} -> $targetEmail")
    }

    /**
     * Обработать входящее key exchange сообщение.
     * Создаёт контакт и отправляет свой ключ назад (если контакта ещё нет).
     *
     * Безопасность:
     * - Для VERIFIED контактов ключ НЕ обновляется автоматически. Пользователь должен
     *   вручную нажать «Обновить ключ» и заново верифицировать контакт.
     * - Для UNVERIFIED контактов ключ обновляется, но показывается уведомление.
     * - UUID дедупликация предотвращает повторную обработку.
     *
     * @return true если контакт создан, false если уже существовал или ключ отклонён
     */
    suspend fun handleKeyExchange(
        body: ByteArray,
        fromEmail: String,
        config: EmailConfig?,
        kexUuid: String? = null
    ): Boolean {
        try {
            // Дедупликация по UUID — защита от replay-атак
            if (kexUuid != null) {
                val now = System.currentTimeMillis()
                if (processedKexUuids.putIfAbsent(kexUuid, now) != null) {
                    Log.d(TAG, "Key exchange $kexUuid уже обработан, пропускаем")
                    return false
                }
                // Чистим старые записи (>24ч) чтобы не накапливались
                processedKexUuids.entries.removeAll { now - it.value > KEX_DEDUP_TTL_MS }
            }

            val jsonStr = String(body, Charsets.UTF_8)
            val json = JSONObject(jsonStr)

            val senderEmail = json.getString("email")
            val publicKeyBase64 = json.getString("publicKey")
            val displayName = json.optString("displayName", senderEmail.substringBefore('@'))

            val publicKey = java.util.Base64.getDecoder().decode(publicKeyBase64)

            if (publicKey.size != 32) {
                Log.e(TAG, "Невалидный публичный ключ от $senderEmail: длина ${publicKey.size}")
                return false
            }

            // Получаем локальный ключ для fingerprint
            val localKey = keyStorage.getPublicKey()
                ?: throw IllegalStateException("Локальный ключ не найден")

            val fingerprint = FingerprintGenerator.generateHex(localKey, publicKey)

            // Проверяем, не добавлен ли уже
            val existing = contactDao.getByEmail(senderEmail)
            if (existing != null) {
                // Ключ не изменился — ничего не делаем
                if (existing.publicKey.contentEquals(publicKey)) {
                    Log.d(TAG, "Контакт $senderEmail уже существует, ключ не изменился")
                    return false
                }

                // Ключ изменился! Поведение зависит от статуса верификации
                if (existing.trustStatus == TrustStatus.VERIFIED) {
                    // VERIFIED контакт: НЕ обновляем ключ автоматически (защита от MITM)
                    Log.w(TAG, "ОТКЛОНЕНО: смена ключа VERIFIED контакта $senderEmail. " +
                        "Требуется ручное обновление.")
                    notificationHelper?.showKeyChangeWarning(senderEmail, wasVerified = true)
                    return false
                }

                // UNVERIFIED контакт: обновляем ключ, показываем предупреждение
                val updated = existing.copy(
                    publicKey = publicKey,
                    fingerprint = fingerprint,
                    trustStatus = TrustStatus.UNVERIFIED,
                    updatedAt = System.currentTimeMillis()
                )
                contactDao.update(updated)
                Log.i(TAG, "Публичный ключ $senderEmail обновлён (UNVERIFIED)")
                notificationHelper?.showKeyChangeWarning(senderEmail, wasVerified = false)

                // Отправляем свой ключ назад — собеседник переустановил и ему нужен наш ключ
                if (config != null) {
                    try {
                        sendKeyExchange(config, senderEmail)
                    } catch (e: Exception) {
                        Log.w(TAG, "Не удалось отправить ответный key exchange: ${e.message}")
                    }
                }
                return false
            }

            val now = System.currentTimeMillis()
            val contact = ContactEntity(
                email = senderEmail,
                displayName = displayName,
                publicKey = publicKey,
                fingerprint = fingerprint,
                trustStatus = TrustStatus.UNVERIFIED,
                createdAt = now,
                updatedAt = now
            )

            contactDao.insert(contact)
            Log.i(TAG, "Контакт добавлен через key exchange: $senderEmail (UNVERIFIED)")

            // Отправляем свой ключ назад
            if (config != null) {
                try {
                    sendKeyExchange(config, senderEmail)
                    Log.i(TAG, "Ответный key exchange отправлен: -> $senderEmail")
                } catch (e: Exception) {
                    Log.w(TAG, "Не удалось отправить ответный key exchange: ${e.message}")
                    // Контакт уже создан — это не критично
                }
            }

            return true

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обработки key exchange от $fromEmail: ${e.message}")
            return false
        }
    }

    companion object {
        private const val TAG = "KeyExchangeManager"
        private const val KEX_DEDUP_TTL_MS = 24 * 60 * 60 * 1000L // 24 часа
        const val KEX_PREFIX = "kex-"
        const val KEYEX_CHAT_ID = "KEYEX"
        const val KEYEX_CONTENT_TYPE = "application/x-cheburmail-keyex"

        /**
         * Проверяет, является ли subject key exchange сообщением.
         */
        fun isKeyExchangeSubject(subject: String): Boolean {
            if (!subject.startsWith(EmailMessage.SUBJECT_PREFIX)) return false
            val parts = subject.removePrefix(EmailMessage.SUBJECT_PREFIX).split("/")
            return parts.size == 2 && parts[0] == KEYEX_CHAT_ID && parts[1].startsWith(KEX_PREFIX)
        }
    }
}
