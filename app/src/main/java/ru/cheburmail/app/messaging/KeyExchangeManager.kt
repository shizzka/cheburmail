package ru.cheburmail.app.messaging

import android.util.Log
import ru.cheburmail.app.crypto.FingerprintGenerator
import ru.cheburmail.app.db.TrustStatus
import ru.cheburmail.app.db.dao.ContactDao
import ru.cheburmail.app.db.dao.ProcessedKeyExchangeDao
import ru.cheburmail.app.db.entity.ContactEntity
import ru.cheburmail.app.db.entity.ProcessedKeyExchangeEntity
import ru.cheburmail.app.storage.SecureKeyStorage
import ru.cheburmail.app.transport.EmailConfig
import ru.cheburmail.app.transport.EmailMessage
import ru.cheburmail.app.transport.ImapClient
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
 * Безопасность и анти-бомбинг:
 * - VERIFIED контакты: смена ключа НЕ принимается автоматически (защита от MITM)
 * - UNVERIFIED контакты: ключ обновляется молча, ответный keyex НЕ шлётся (защита от петли)
 * - Персистентный дедуп по UUID через Room (защита от replay и перечитывания IMAP)
 * - Rate-limit: один keyex на адрес не чаще раза в [SEND_RATE_LIMIT_MS]
 */
class KeyExchangeManager(
    private val smtpClient: SmtpClient,
    private val contactDao: ContactDao,
    private val keyStorage: SecureKeyStorage,
    private val notificationHelper: NotificationHelper? = null,
    private val processedDao: ProcessedKeyExchangeDao? = null,
    private val imapClient: ImapClient? = null,
    /**
     * Персистентное хранилище rate-limit. По умолчанию in-memory —
     * после рестарта процесса rate-limit обнуляется.
     * В проде подставляется [KeyexRateLimitStore.sharedPrefs], чтобы
     * флуд-защита пережила перезапуск service'а.
     */
    private val rateLimitStore: KeyexRateLimitStore = KeyexRateLimitStore.inMemory()
) {

    /** Кэш в памяти — используется если нет [processedDao] (в тестах/старых воркерах). */
    private val processedKexUuids = ConcurrentHashMap<String, Long>()

    /**
     * Отправить свой публичный ключ на указанный email.
     * Повторные вызовы в пределах [SEND_RATE_LIMIT_MS] на один и тот же адрес игнорируются.
     */
    suspend fun sendKeyExchange(config: EmailConfig, targetEmail: String) {
        val now = System.currentTimeMillis()
        val last = rateLimitStore.lastSent(targetEmail)
        if (last != null && now - last < SEND_RATE_LIMIT_MS) {
            Log.d(TAG, "Rate-limit: keyex to $targetEmail уже отправлен ${now - last}ms назад, пропускаем")
            return
        }

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
        rateLimitStore.markSent(targetEmail, now)
        Log.i(TAG, "Key exchange отправлен -> $targetEmail")
    }

    /**
     * Обработать входящее key exchange сообщение.
     *
     * Поведение:
     * - Уже обработанный UUID (по persistent-дедупу) → skip.
     * - Новый контакт → создаём, отправляем наш ключ назад (однократно, с rate-limit).
     * - Существующий контакт, ключ совпал → ничего.
     * - Существующий VERIFIED, ключ изменился → ОТКЛОНЯЕМ, уведомляем.
     * - Существующий UNVERIFIED, ключ изменился → обновляем МОЛЧА, БЕЗ ответного keyex
     *   (так мы разрываем петлю, когда у партнёра в IMAP лежат старые keyex с разными ключами).
     * - В любом случае возвращаем пометку, обработали ли UUID, чтобы вызывающий мог
     *   удалить письмо из IMAP и не перечитывать его снова.
     *
     * @return true если контакт создан/обновлён, false если skip/отклонено
     */
    suspend fun handleKeyExchange(
        body: ByteArray,
        fromEmail: String,
        config: EmailConfig?,
        kexUuid: String? = null,
        messageTimestamp: Long? = null
    ): Boolean {
        try {
            // Дедупликация по UUID (персистентная если есть DAO, иначе in-memory)
            if (kexUuid != null && isAlreadyProcessed(kexUuid)) {
                Log.d(TAG, "Key exchange $kexUuid уже обработан, пропускаем")
                return false
            }

            val jsonStr = String(body, Charsets.UTF_8)
            val json = JSONObject(jsonStr)

            val senderEmail = json.getString("email")
            // Защита от MITM: email из JSON-тела должен совпадать с envelope From.
            // Иначе атакующий с доступом к SMTP может подменить ключ для чужого адреса.
            if (!senderEmail.equals(fromEmail, ignoreCase = true)) {
                Log.w(TAG, "keyex envelope/json email mismatch: envelope=$fromEmail json=$senderEmail — отклонено")
                markProcessed(kexUuid)
                return false
            }
            val publicKeyBase64 = json.getString("publicKey")
            val displayName = json.optString("displayName", senderEmail.substringBefore('@'))

            val publicKey = java.util.Base64.getDecoder().decode(publicKeyBase64)

            if (publicKey.size != 32) {
                Log.e(TAG, "Невалидный публичный ключ от $senderEmail: длина ${publicKey.size}")
                markProcessed(kexUuid)
                return false
            }

            val localKey = keyStorage.getPublicKey()
                ?: throw IllegalStateException("Локальный ключ не найден")

            val fingerprint = FingerprintGenerator.generateHex(localKey, publicKey)

            val existing = contactDao.getByEmail(senderEmail)
            if (existing != null) {
                if (existing.publicKey.contentEquals(publicKey)) {
                    Log.d(TAG, "Контакт $senderEmail уже существует, ключ не изменился")
                    markProcessed(kexUuid)
                    return false
                }

                if (existing.trustStatus == TrustStatus.VERIFIED) {
                    Log.w(TAG, "ОТКЛОНЕНО: смена ключа VERIFIED контакта $senderEmail. Требуется ручное обновление.")
                    notificationHelper?.showKeyChangeWarning(senderEmail, wasVerified = true)
                    markProcessed(kexUuid)
                    return false
                }

                // Race guard: IMAP может вернуть устаревший keyex позже свежего.
                // Если timestamp письма старее, чем updatedAt текущего контакта — игнорируем,
                // иначе старый ключ перезатрёт актуальный и связь порвётся.
                if (messageTimestamp != null && messageTimestamp < existing.updatedAt) {
                    Log.w(TAG, "keyex stale: $senderEmail ts=$messageTimestamp < updatedAt=${existing.updatedAt} — игнорируем")
                    markProcessed(kexUuid)
                    return false
                }

                // UNVERIFIED + ключ изменился: обновляем молча, БЕЗ ответного keyex.
                // Ответный keyex здесь — главный источник петли при мусорных keyex в IMAP партнёра.
                val updated = existing.copy(
                    publicKey = publicKey,
                    fingerprint = fingerprint,
                    trustStatus = TrustStatus.UNVERIFIED,
                    updatedAt = System.currentTimeMillis()
                )
                contactDao.update(updated)
                Log.i(TAG, "Публичный ключ контакта $senderEmail обновлён (UNVERIFIED), ответный keyex не шлём")
                notificationHelper?.showKeyChangeWarning(senderEmail, wasVerified = false)
                markProcessed(kexUuid)
                return true
            }

            // Новый контакт
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
            Log.i(TAG, "Контакт $senderEmail добавлен через key exchange (UNVERIFIED)")

            // Отправляем свой ключ назад (с rate-limit внутри sendKeyExchange)
            if (config != null) {
                try {
                    sendKeyExchange(config, senderEmail)
                    Log.i(TAG, "Ответный key exchange отправлен -> $senderEmail")
                } catch (e: Exception) {
                    Log.w(TAG, "Не удалось отправить ответный key exchange: ${e.message}")
                }
            }

            markProcessed(kexUuid)
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обработки key exchange от $fromEmail: ${e.message}")
            // Помечаем UUID обработанным чтобы не крутиться на одном и том же испорченном письме.
            markProcessed(kexUuid)
            return false
        }
    }

    /**
     * Удалить keyex-письмо с указанным UUID из IMAP.
     * Вызывается из ReceiveWorker после обработки, чтобы не накапливались сотни keyex-писем.
     */
    fun deleteKeyExchangeEmail(config: EmailConfig, kexUuid: String) {
        val imap = imapClient ?: return
        try {
            // deleteFromImap matches by whole slash-separated segment; "kex-<uuid>"
            // is one full segment of subject "CM/1/KEYEX/kex-<uuid>", no substring collisions.
            imap.deleteFromImap(config, "$KEX_PREFIX$kexUuid")
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось удалить keyex $kexUuid из IMAP: ${e.message}")
        }
    }

    private suspend fun isAlreadyProcessed(kexUuid: String): Boolean {
        val dao = processedDao
        if (dao != null) {
            return dao.exists(kexUuid)
        }
        val now = System.currentTimeMillis()
        if (processedKexUuids.putIfAbsent(kexUuid, now) != null) {
            return true
        }
        processedKexUuids.entries.removeAll { now - it.value > KEX_DEDUP_TTL_MS }
        return false
    }

    private suspend fun markProcessed(kexUuid: String?) {
        if (kexUuid == null) return
        val dao = processedDao ?: return
        try {
            dao.insert(ProcessedKeyExchangeEntity(kexUuid = kexUuid))
            // Ленивый GC старых записей (>24ч)
            dao.deleteOlderThan(System.currentTimeMillis() - KEX_DEDUP_TTL_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось сохранить processed keyex $kexUuid: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "KeyExchangeManager"
        private const val KEX_DEDUP_TTL_MS = 24 * 60 * 60 * 1000L // 24 часа
        /** Минимальный интервал между отправками keyex на один email. */
        private const val SEND_RATE_LIMIT_MS = 60 * 1000L // 1 минута
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
