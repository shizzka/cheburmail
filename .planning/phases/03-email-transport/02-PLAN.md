---
plan: 02
title: Транспортный сервис
wave: 1
depends_on: []
autonomous: true
files_modified:
  - app/src/main/java/ru/cheburmail/app/transport/RetryStrategy.kt
  - app/src/main/java/ru/cheburmail/app/transport/TransportService.kt
  - app/src/main/java/ru/cheburmail/app/transport/SendWorker.kt
  - app/src/main/java/ru/cheburmail/app/transport/ReceiveWorker.kt
  - app/src/test/java/ru/cheburmail/app/transport/RetryStrategyTest.kt
  - app/src/test/java/ru/cheburmail/app/transport/SendWorkerTest.kt
  - app/src/test/java/ru/cheburmail/app/transport/ReceiveWorkerTest.kt
---

# Транспортный сервис

## Цель

Реализовать оркестрацию отправки и получения зашифрованных сообщений: SendWorker обрабатывает очередь отправки (send_queue из Room), ReceiveWorker опрашивает IMAP и дедуплицирует входящие по UUID. RetryStrategy обеспечивает экспоненциальный backoff (1/2/4/8/16 мин, макс 5 попыток). TransportService координирует полный pipeline: encrypt → format → smtp (отправка) и imap → parse → decrypt → save (получение).

## Зависимости от Phase 2 (Room DB)

Этот план предполагает наличие следующих сущностей из фазы 2 (локальное хранилище). Если фаза 2 ещё не выполнена — создать минимальные интерфейсы/заглушки:

- `MessageDao` — DAO для таблицы messages: `insertMessage()`, `existsById(uuid): Boolean`, `getById(uuid)`
- `SendQueueDao` — DAO для таблицы send_queue: `getQueued()`, `updateStatus()`, `incrementRetry()`
- `SendQueueEntry` — entity: id, messageId, status (QUEUED/SENDING/SENT/FAILED), retryCount, nextRetryAt
- `ContactDao` — DAO для таблицы contacts: `getByEmail(email)` (для получения публичного ключа)

## Задачи

<task id="1" name="RetryStrategy — экспоненциальный backoff" type="feat">
Создать `RetryStrategy.kt` — логика повторных попыток:

```kotlin
package ru.cheburmail.app.transport

/**
 * Экспоненциальный backoff для ошибок SMTP/IMAP.
 *
 * Задержки: 1 мин, 2 мин, 4 мин, 8 мин, 16 мин (итого 5 попыток).
 * После 5 неудачных попыток — статус FAILED.
 */
class RetryStrategy(
    private val maxRetries: Int = MAX_RETRIES,
    private val baseDelayMs: Long = BASE_DELAY_MS
) {

    /**
     * Рассчитать задержку для следующей попытки.
     *
     * @param retryCount текущий номер попытки (0-based)
     * @return задержка в миллисекундах, или null если попытки исчерпаны
     */
    fun nextDelay(retryCount: Int): Long? {
        if (retryCount >= maxRetries) return null
        return baseDelayMs * (1L shl retryCount) // 60000, 120000, 240000, 480000, 960000
    }

    /**
     * Проверить, можно ли повторить попытку.
     */
    fun canRetry(retryCount: Int): Boolean = retryCount < maxRetries

    companion object {
        const val MAX_RETRIES = 5
        const val BASE_DELAY_MS = 60_000L // 1 минута
    }
}
```

ВАЖНО:
- Задержки: 1мин (2^0), 2мин (2^1), 4мин (2^2), 8мин (2^3), 16мин (2^4)
- retryCount 0-based: первая попытка — retryCount=0, задержка 1мин
- После retryCount >= 5 — canRetry возвращает false, nextDelay возвращает null
</task>

<task id="2" name="TransportService — оркестрация pipeline" type="feat">
Создать `TransportService.kt` — координация отправки и получения:

```kotlin
package ru.cheburmail.app.transport

import ru.cheburmail.app.crypto.MessageEncryptor
import ru.cheburmail.app.crypto.MessageDecryptor
import ru.cheburmail.app.crypto.model.EncryptedEnvelope

class TransportService(
    private val smtpClient: SmtpClient,
    private val imapClient: ImapClient,
    private val emailFormatter: EmailFormatter,
    private val emailParser: EmailParser,
    private val encryptor: MessageEncryptor,
    private val decryptor: MessageDecryptor
) {

    /**
     * Pipeline отправки: plaintext → encrypt → format → smtp.
     *
     * @param plaintext текст сообщения (UTF-8 bytes)
     * @param recipientPublicKey публичный ключ получателя (32 байта)
     * @param senderPrivateKey приватный ключ отправителя (32 байта)
     * @param chatId идентификатор чата
     * @param msgUuid UUID сообщения
     * @param fromEmail email отправителя
     * @param toEmail email получателя
     * @param config SMTP-конфигурация
     * @throws TransportException при ошибках отправки
     * @throws ru.cheburmail.app.crypto.CryptoException при ошибках шифрования
     */
    fun sendMessage(
        plaintext: ByteArray,
        recipientPublicKey: ByteArray,
        senderPrivateKey: ByteArray,
        chatId: String,
        msgUuid: String,
        fromEmail: String,
        toEmail: String,
        config: EmailConfig
    ) {
        // 1. Зашифровать
        val envelope = encryptor.encrypt(plaintext, recipientPublicKey, senderPrivateKey)

        // 2. Сформировать email
        val email = emailFormatter.format(envelope, chatId, msgUuid, fromEmail, toEmail)

        // 3. Отправить через SMTP
        smtpClient.send(config, email)
    }

    /**
     * Pipeline получения: imap → filter → parse → список ParsedMessage.
     * Расшифровка и сохранение выполняются вызывающим кодом (ReceiveWorker),
     * потому что для decrypt нужен приватный ключ из SecureKeyStorage.
     *
     * @param config IMAP-конфигурация
     * @return список ParsedMessage
     */
    fun receiveMessages(config: EmailConfig): List<EmailParser.ParsedMessage> {
        // 1. Получить email из CheburMail-папки
        val emails = imapClient.fetchMessages(config)

        // 2. Фильтровать и парсить CheburMail-сообщения
        return emails
            .filter { emailParser.isCheburMail(it) }
            .map { emailParser.parse(it) }
    }
}
```

Метод `sendMessage` — синхронный, блокирующий. Вызывается из SendWorker в фоновом потоке.
Метод `receiveMessages` — синхронный, возвращает список распарсенных сообщений. Расшифровка и сохранение в Room — ответственность ReceiveWorker.
</task>

<task id="3" name="SendWorker — обработка очереди отправки" type="feat">
Создать `SendWorker.kt` — воркер отправки сообщений из send_queue:

```kotlin
package ru.cheburmail.app.transport

/**
 * Обрабатывает очередь отправки (send_queue из Room).
 *
 * Алгоритм:
 * 1. Получить все записи со статусом QUEUED и nextRetryAt <= now
 * 2. Для каждой записи:
 *    a. Обновить статус → SENDING
 *    b. Получить сообщение из messages по messageId
 *    c. Получить публичный ключ получателя из contacts
 *    d. Вызвать TransportService.sendMessage()
 *    e. При успехе: обновить статус → SENT
 *    f. При ошибке:
 *       - Увеличить retryCount
 *       - Рассчитать nextDelay через RetryStrategy
 *       - Если canRetry — обновить nextRetryAt, статус → QUEUED
 *       - Если !canRetry — обновить статус → FAILED
 */
class SendWorker(
    private val transportService: TransportService,
    private val retryStrategy: RetryStrategy,
    // TODO: sendQueueDao, messageDao, contactDao — инжектируются после фазы 2
) {
    /**
     * Обработать очередь отправки.
     * Вызывается периодически (из корутины или WorkManager).
     */
    fun processQueue() { ... }
}
```

Обработка ошибок:
- `TransportException.SmtpException` — ошибка SMTP → retry с backoff
- `CryptoException` — ошибка шифрования → FAILED сразу (нет смысла повторять)
- `TransportException.FormatException` — ошибка формата → FAILED сразу

Логирование:
- `Log.d("SendWorker", "Sending message $msgUuid, attempt ${retryCount + 1}")` — при каждой попытке
- `Log.e("SendWorker", "Failed to send $msgUuid: ${e.message}")` — при ошибке
- `Log.i("SendWorker", "Message $msgUuid sent successfully")` — при успехе
- `Log.w("SendWorker", "Message $msgUuid FAILED after $maxRetries retries")` — при исчерпании попыток
</task>

<task id="4" name="ReceiveWorker — получение и дедупликация" type="feat">
Создать `ReceiveWorker.kt` — воркер получения сообщений:

```kotlin
package ru.cheburmail.app.transport

/**
 * Опрашивает IMAP, парсит входящие CheburMail-сообщения,
 * дедуплицирует по UUID и сохраняет новые сообщения в Room.
 *
 * Алгоритм:
 * 1. Вызвать TransportService.receiveMessages(config)
 * 2. Для каждого ParsedMessage:
 *    a. Проверить дедупликацию: messageDao.existsById(msgUuid)
 *    b. Если уже существует — пропустить (лог: "Duplicate message $msgUuid, skipping")
 *    c. Если новое:
 *       - Получить публичный ключ отправителя из contacts по fromEmail
 *       - Расшифровать: decryptor.decrypt(envelope, senderPublicKey, recipientPrivateKey)
 *       - Сохранить в Room: messageDao.insertMessage(...)
 * 3. При ошибке IMAP — retry с backoff (через RetryStrategy)
 * 4. При ошибке расшифровки конкретного сообщения — логировать и пропустить (не блокировать остальные)
 */
class ReceiveWorker(
    private val transportService: TransportService,
    private val decryptor: MessageDecryptor,
    private val retryStrategy: RetryStrategy,
    // TODO: messageDao, contactDao, secureKeyStorage — инжектируются после фазы 2
) {
    /**
     * Опросить IMAP и обработать новые сообщения.
     *
     * @param config IMAP-конфигурация
     * @return количество новых сообщений, сохранённых в Room
     */
    fun pollAndProcess(config: EmailConfig): Int { ... }
}
```

Дедупликация (критически важно):
- `messageDao.existsById(msgUuid)` проверяется ДО расшифровки — экономит CPU
- UUID берётся из subject email (CM/1/<chatId>/<msgUuid>), НЕ из содержимого
- Если email с таким UUID уже есть в Room — сообщение пропускается полностью

Обработка ошибок:
- Ошибка IMAP-подключения → возвращаем 0, retry на следующем цикле
- Ошибка расшифровки одного сообщения → логируем, пропускаем, продолжаем с остальными
- Неизвестный отправитель (нет в contacts) → логируем, пропускаем (нет публичного ключа для расшифровки)
</task>

<task id="5" name="Управление IMAP-папкой CheburMail" type="feat">
Расширить `ImapClient` (из плана 01) логикой управления папкой:

1. При первом подключении — вызвать `ensureCheburMailFolder()`
2. Фильтрация входящих:
   - На Yandex/Mail.ru нет серверных правил через IMAP
   - Стратегия: при fetchMessages проверять INBOX + CheburMail-папку
   - Сообщения с subject "CM/1/*" в INBOX → переместить в CheburMail-папку (IMAP COPY + DELETE)
   - Все последующие fetch — только из CheburMail-папки

```kotlin
// Добавить в ImapClient:

/**
 * Переместить CheburMail-сообщения из INBOX в папку CheburMail.
 * Вызывается при каждом fetchMessages для сбора разбросанных сообщений.
 */
fun moveCheburMailFromInbox(config: EmailConfig) {
    // 1. Открыть INBOX (READ_WRITE)
    // 2. Найти сообщения с subject "CM/1/*" (SubjectTerm("CM/1/"))
    // 3. Скопировать в CheburMail-папку (folder.copyMessages)
    // 4. Пометить оригиналы в INBOX как DELETED
    // 5. folder.expunge()
}
```

Порядок вызова в `fetchMessages`:
1. `ensureCheburMailFolder(config)` — создать папку (idempotent)
2. `moveCheburMailFromInbox(config)` — собрать из INBOX
3. Fetch UNSEEN из CheburMail-папки
</task>

<task id="6" name="Unit-тесты RetryStrategy, SendWorker, ReceiveWorker" type="test">
Создать unit-тесты:

1. `RetryStrategyTest.kt`:
   - `nextDelay_retry0_returns60000ms` — первая задержка 1 мин
   - `nextDelay_retry1_returns120000ms` — вторая задержка 2 мин
   - `nextDelay_retry4_returns960000ms` — пятая задержка 16 мин
   - `nextDelay_retry5_returnsNull` — попытки исчерпаны
   - `canRetry_under5_returnsTrue`
   - `canRetry_5orMore_returnsFalse`
   - `exponentialProgression_allDelays` — проверить всю последовательность [60, 120, 240, 480, 960] сек

2. `SendWorkerTest.kt` (с mock TransportService):
   - `processQueue_successfulSend_statusSent` — при успешной отправке статус → SENT
   - `processQueue_smtpError_retryQueued` — при SmtpException → статус QUEUED + retryCount++
   - `processQueue_cryptoError_statusFailed` — при CryptoException → сразу FAILED
   - `processQueue_maxRetriesExhausted_statusFailed` — после 5 ошибок → FAILED
   - `processQueue_emptyQueue_noAction` — пустая очередь — ничего не делать

3. `ReceiveWorkerTest.kt` (с mock TransportService):
   - `pollAndProcess_newMessage_savedToRoom` — новое сообщение сохраняется
   - `pollAndProcess_duplicateUuid_skipped` — дубликат по UUID пропускается
   - `pollAndProcess_unknownSender_skipped` — неизвестный отправитель пропускается
   - `pollAndProcess_decryptError_skippedContinues` — ошибка расшифровки одного сообщения не блокирует остальные
   - `pollAndProcess_imapError_returns0` — ошибка IMAP → возвращает 0

Для моков: если Mockito/Mockk ещё не в зависимостях — добавить `mockk` (testImplementation) в libs.versions.toml и build.gradle.kts.
</task>

## must_haves

- [ ] RetryStrategy: экспоненциальный backoff 1/2/4/8/16 мин, max 5 попыток, после 5 — null/false
- [ ] TransportService: pipeline encrypt→format→smtp (отправка) и imap→parse (получение)
- [ ] SendWorker: обработка send_queue — QUEUED→SENDING→SENT/FAILED, retry с backoff
- [ ] ReceiveWorker: IMAP poll → дедупликация по UUID (existsById) → decrypt → save
- [ ] Перемещение CM/* сообщений из INBOX в папку CheburMail
- [ ] Ошибка расшифровки одного сообщения не блокирует обработку остальных
- [ ] CryptoException при отправке → сразу FAILED (без retry)
- [ ] Все unit-тесты проходят через `./gradlew test`

## Верификация

1. `./gradlew test --tests "ru.cheburmail.app.transport.RetryStrategyTest"` — проходит
2. `./gradlew test --tests "ru.cheburmail.app.transport.SendWorkerTest"` — проходит
3. `./gradlew test --tests "ru.cheburmail.app.transport.ReceiveWorkerTest"` — проходит
4. Ручная проверка: SendWorker при 5 последовательных ошибках SMTP помечает сообщение как FAILED
5. Ручная проверка: ReceiveWorker при повторном получении email с тем же UUID не создаёт дубликат
