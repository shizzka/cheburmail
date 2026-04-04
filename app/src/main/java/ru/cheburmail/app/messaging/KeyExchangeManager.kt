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
import org.json.JSONObject
import java.util.UUID

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
 */
class KeyExchangeManager(
    private val smtpClient: SmtpClient,
    private val contactDao: ContactDao,
    private val keyStorage: SecureKeyStorage
) {

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
     * @return true если контакт создан, false если уже существовал
     */
    suspend fun handleKeyExchange(
        body: ByteArray,
        fromEmail: String,
        config: EmailConfig?
    ): Boolean {
        try {
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
                // Обновляем публичный ключ если он изменился (переустановка, регенерация)
                if (!existing.publicKey.contentEquals(publicKey)) {
                    val updated = existing.copy(
                        publicKey = publicKey,
                        fingerprint = fingerprint,
                        trustStatus = TrustStatus.UNVERIFIED,
                        updatedAt = System.currentTimeMillis()
                    )
                    contactDao.update(updated)
                    Log.i(TAG, "Публичный ключ $senderEmail обновлён (сброшен в UNVERIFIED)")
                } else {
                    Log.d(TAG, "Контакт $senderEmail уже существует, ключ не изменился")
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
