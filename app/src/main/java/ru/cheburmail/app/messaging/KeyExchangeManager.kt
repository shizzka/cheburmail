package ru.cheburmail.app.messaging

import android.util.Log
import ru.cheburmail.app.crypto.FingerprintGenerator
import ru.cheburmail.app.db.TrustStatus
import ru.cheburmail.app.db.dao.ContactDao
import ru.cheburmail.app.db.dao.ProcessedKeyExchangeDao
import ru.cheburmail.app.db.entity.ContactAliasEntity
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
 * - Body: JSON {"email":"...","publicKey":"<base64>","displayName":"...","aliases":[...]}
 * - Content-Type: application/x-cheburmail-keyex
 *
 * Поле `aliases` (с v0.4.0) — список других email-адресов той же identity
 * (других моих аккаунтов: yandex/mail.ru/...). Получатель сохранит их в
 * `contact_aliases`, чтобы матчить меня по любому из адресов.
 *
 * Протокол:
 * 1. User A вводит email User B → отправляет свой публичный ключ + список своих aliases
 * 2. User B получает → создаёт контакт (UNVERIFIED) + сохраняет aliases → отправляет свой ключ назад
 * 3. User A получает ответ → создаёт контакт (UNVERIFIED) + сохраняет aliases → чат готов
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
    private val rateLimitStore: KeyexRateLimitStore = KeyexRateLimitStore.inMemory(),
    /**
     * Поставщик email-алиасов текущей identity (всех моих аккаунтов кроме
     * того, с которого отправляем). Возвращает пустой список — keyex
     * отправится в формате v1 без поля aliases. В реальном коде
     * подставляется лямбда поверх AccountRepository / MultiAccountManager.
     */
    private val aliasProvider: suspend (myEmail: String) -> List<String> = { emptyList() }
) {

    /** Кэш в памяти — используется если нет [processedDao] (в тестах/старых воркерах). */
    private val processedKexUuids = ConcurrentHashMap<String, Long>()

    /**
     * Отправить свой публичный ключ на указанный email.
     * Повторные вызовы в пределах [SEND_RATE_LIMIT_MS] на один и тот же адрес игнорируются.
     *
     * @param explicitAliases если null — берём из aliasProvider; если задан явно
     *   (например, в тестах), используется как есть. Полезно когда вызывающий
     *   уже знает список аккаунтов и не хочет повторного DB-запроса.
     */
    suspend fun sendKeyExchange(
        config: EmailConfig,
        targetEmail: String,
        explicitAliases: List<String>? = null
    ) {
        val now = System.currentTimeMillis()
        val last = rateLimitStore.lastSent(targetEmail)
        if (last != null && now - last < SEND_RATE_LIMIT_MS) {
            Log.d(TAG, "Rate-limit: keyex to $targetEmail уже отправлен ${now - last}ms назад, пропускаем")
            return
        }

        val publicKey = keyStorage.getPublicKey()
            ?: throw IllegalStateException("Публичный ключ не найден")

        val aliases = (explicitAliases ?: runCatching { aliasProvider(config.email) }.getOrDefault(emptyList()))
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals(config.email, ignoreCase = true) }
            .distinctBy { it.lowercase() }

        val json = JSONObject().apply {
            put("email", config.email)
            put("publicKey", java.util.Base64.getEncoder().encodeToString(publicKey))
            put("displayName", config.email.substringBefore('@'))
            if (aliases.isNotEmpty()) {
                put("aliases", org.json.JSONArray(aliases))
            }
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
        Log.i(TAG, "Key exchange отправлен -> $targetEmail (aliases=${aliases.size})")
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

            // Опциональный список алиасов отправителя (его другие email-аккаунты).
            // Sanitize: trim, не пустые, не равны senderEmail, уникальные, max 16.
            val incomingAliases: List<String> = run {
                val arr = json.optJSONArray("aliases") ?: return@run emptyList()
                val out = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i, "").trim()
                    if (s.isNotBlank() && !s.equals(senderEmail, ignoreCase = true)) {
                        out += s
                    }
                }
                out.distinctBy { it.lowercase() }.take(MAX_ALIASES_PER_KEYEX)
            }

            val localKey = keyStorage.getPublicKey()
                ?: throw IllegalStateException("Локальный ключ не найден")

            val fingerprint = FingerprintGenerator.generateHex(localKey, publicKey)

            // Сначала пытаемся найти существующий контакт по pub_key — это
            // основной идентификатор identity. senderEmail может быть alias,
            // которого мы ещё не знаем.
            val byPubKey = contactDao.getByPublicKey(publicKey)
            val byEmail = contactDao.getByEmailOrAlias(senderEmail)

            // Случай A: pub_key известен — это та же identity, что у нас есть.
            // Регистрируем senderEmail и incomingAliases как alias'ы (если ещё нет).
            if (byPubKey != null) {
                val target = byPubKey
                // Аномалия: byEmail указывает на ДРУГОЙ контакт. Это редкий случай —
                // например, два разных pub_key обменивались с одного email раньше,
                // потом один сменил pub_key. Сейчас pub_key совпадает с target,
                // а email привязан к старому контакту. Не трогаем чужие алиасы;
                // просто привяжем senderEmail к target если он свободен.
                if (byEmail != null && byEmail.id != target.id) {
                    Log.w(TAG, "keyex: pub_key совпал с contact ${target.id}, но email $senderEmail уже привязан к другому contact ${byEmail.id} — пропускаем регистрацию алиаса")
                } else {
                    registerAliasIfPossible(target.id, senderEmail, ContactAliasEntity.SOURCE_LEARNED)
                }
                for (alias in incomingAliases) {
                    val existingAliasOwner = contactDao.getAliasByEmail(alias)
                    if (existingAliasOwner != null && existingAliasOwner.contactId != target.id) {
                        Log.w(TAG, "keyex: alias $alias уже принадлежит contact ${existingAliasOwner.contactId}, не трогаем")
                        continue
                    }
                    val collidesPrimary = contactDao.getByEmail(alias)
                    if (collidesPrimary != null && collidesPrimary.id != target.id) {
                        Log.w(TAG, "keyex: alias $alias = primary email contact ${collidesPrimary.id}, не дублируем")
                        continue
                    }
                    registerAliasIfPossible(target.id, alias, ContactAliasEntity.SOURCE_LEARNED)
                }
                Log.i(TAG, "Контакт ${target.email} (id=${target.id}) подтверждён по pub_key, алиасы синхронизированы (+${incomingAliases.size + 1})")
                markProcessed(kexUuid)
                return true
            }

            // Случай B: pub_key новый, но email уже известен под другим pub_key.
            // Это либо смена ключа у контакта, либо MITM. Уважаем существующее
            // поведение (UNVERIFIED — обновляем; VERIFIED — отклоняем).
            if (byEmail != null) {
                if (byEmail.publicKey.contentEquals(publicKey)) {
                    // Не должно случиться (byPubKey был бы не null), но защищаемся.
                    Log.d(TAG, "Контакт $senderEmail уже существует, ключ совпал")
                    markProcessed(kexUuid)
                    return false
                }

                if (byEmail.trustStatus == TrustStatus.VERIFIED) {
                    Log.w(TAG, "ОТКЛОНЕНО: смена ключа VERIFIED контакта $senderEmail. Требуется ручное обновление.")
                    notificationHelper?.showKeyChangeWarning(senderEmail, wasVerified = true)
                    markProcessed(kexUuid)
                    return false
                }

                if (messageTimestamp != null && messageTimestamp < byEmail.updatedAt) {
                    Log.w(TAG, "keyex stale: $senderEmail ts=$messageTimestamp < updatedAt=${byEmail.updatedAt} — игнорируем")
                    markProcessed(kexUuid)
                    return false
                }

                val updated = byEmail.copy(
                    publicKey = publicKey,
                    fingerprint = fingerprint,
                    trustStatus = TrustStatus.UNVERIFIED,
                    updatedAt = System.currentTimeMillis()
                )
                contactDao.update(updated)
                Log.i(TAG, "Публичный ключ контакта $senderEmail обновлён (UNVERIFIED, key rotation)")
                notificationHelper?.showKeyChangeWarning(senderEmail, wasVerified = false)
                // Старые алиасы не валидны для нового pub_key — но и трогать их
                // не будем, потому что getByPublicKey по новому ключу их и не
                // найдёт. Алиасы остаются «висеть» на этом contact_id, что
                // безопасно: матчинг идёт через contactDao.
                markProcessed(kexUuid)
                return true
            }

            // Случай C: новый pub_key, новый email → создаём новый контакт.
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

            val newId = contactDao.insert(contact)
            // Primary email контакта зарегистрируем как PRIMARY-алиас тоже
            // (для unified matcher через JOIN)
            registerAliasIfPossible(newId, senderEmail, ContactAliasEntity.SOURCE_PRIMARY)
            // Дополнительные алиасы из keyex — LEARNED
            for (alias in incomingAliases) {
                registerAliasIfPossible(newId, alias, ContactAliasEntity.SOURCE_LEARNED)
            }
            Log.i(TAG, "Контакт $senderEmail добавлен через key exchange (UNVERIFIED, +${incomingAliases.size} aliases)")

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

    /**
     * Зарегистрировать email как alias контакта. Если такая же связка
     * (contact_id, email) уже есть — IGNORE. Если email уже занят другим
     * contact_id (UNIQUE-индекс на email) — лог, не падаем.
     */
    private suspend fun registerAliasIfPossible(
        contactId: Long,
        email: String,
        source: String
    ) {
        try {
            val alias = ContactAliasEntity(
                contactId = contactId,
                email = email,
                source = source,
                addedAt = System.currentTimeMillis()
            )
            contactDao.insertAlias(alias)
        } catch (e: Exception) {
            // SQLiteConstraintException на uniq index email — email привязан к
            // другому контакту. Логируем, но не падаем.
            Log.w(TAG, "registerAliasIfPossible: не удалось добавить $email -> contact $contactId: ${e.message}")
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
        /** Лимит алиасов в одном keyex (защита от QR-мегапейлоадов). */
        private const val MAX_ALIASES_PER_KEYEX = 16
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
