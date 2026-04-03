---
plan: 03
title: Интеграция и дедупликация
wave: 2
depends_on: [01, 02]
autonomous: true
files_modified:
  - app/src/main/java/ru/cheburmail/app/transport/MessageRepository.kt
  - app/src/main/java/ru/cheburmail/app/transport/TransportModule.kt
  - app/src/test/java/ru/cheburmail/app/transport/MessageRepositoryTest.kt
  - app/src/test/java/ru/cheburmail/app/transport/IntegrationTest.kt
---

# Интеграция и дедупликация

## Цель

Связать все компоненты email-транспорта в единое целое: MessageRepository предоставляет высокоуровневый API для отправки/получения сообщений (скрывая детали шифрования, форматирования, SMTP/IMAP). TransportModule собирает граф зависимостей. Интеграционные тесты проверяют полный pipeline от plaintext до расшифровки, включая дедупликацию.

## Зависимости

- **План 01** (Email-клиент): SmtpClient, ImapClient, EmailFormatter, EmailParser, модели данных
- **План 02** (Транспортный сервис): TransportService, SendWorker, ReceiveWorker, RetryStrategy
- **Фаза 1** (Крипто-модуль): MessageEncryptor, MessageDecryptor, EncryptedEnvelope
- **Фаза 2** (Room DB): MessageDao, SendQueueDao, ContactDao, ChatDao (или интерфейсы-заглушки)

## Задачи

<task id="1" name="MessageRepository — высокоуровневый API" type="feat">
Создать `MessageRepository.kt` — фасад для отправки и получения сообщений:

```kotlin
package ru.cheburmail.app.transport

import ru.cheburmail.app.crypto.MessageDecryptor
import ru.cheburmail.app.crypto.MessageEncryptor

/**
 * Высокоуровневый API для отправки и получения сообщений.
 * Скрывает детали шифрования, формата email и SMTP/IMAP.
 *
 * Вызывающий код (UI/ViewModel) работает только с plaintext + chatId + contactEmail.
 */
class MessageRepository(
    private val transportService: TransportService,
    private val sendWorker: SendWorker,
    private val receiveWorker: ReceiveWorker,
    private val config: EmailConfig
    // TODO: messageDao, sendQueueDao, contactDao, secureKeyStorage — после фазы 2
) {

    /**
     * Поставить сообщение в очередь отправки.
     *
     * Алгоритм:
     * 1. Генерировать UUID для сообщения
     * 2. Сохранить сообщение в Room (messages) со статусом SENDING
     * 3. Создать запись в send_queue (status=QUEUED, retryCount=0, nextRetryAt=now)
     * 4. Триггернуть SendWorker (немедленная попытка отправки)
     *
     * @param plaintext текст сообщения
     * @param chatId идентификатор чата
     * @param recipientEmail email получателя
     * @return UUID отправленного сообщения
     */
    suspend fun sendMessage(
        plaintext: String,
        chatId: String,
        recipientEmail: String
    ): String { ... }

    /**
     * Проверить новые входящие сообщения.
     *
     * Алгоритм:
     * 1. Вызвать ReceiveWorker.pollAndProcess(config)
     * 2. Вернуть количество новых сообщений
     *
     * @return количество новых сообщений
     */
    suspend fun checkIncoming(): Int { ... }

    /**
     * Повторить отправку всех QUEUED-сообщений.
     * Вызывается при восстановлении сети.
     */
    suspend fun retrySending() { ... }
}
```

Генерация UUID:
- `java.util.UUID.randomUUID().toString()` — стандартный формат без дефисов (или с дефисами — выбрать один вариант и зафиксировать)
- UUID записывается в subject email: `CM/1/<chatId>/<msgUuid>`
- UUID — primary key в таблице messages (для дедупликации)

Конкурентность:
- `sendMessage` — suspend-функция, вызывается из UI (Dispatchers.IO внутри)
- `checkIncoming` — suspend-функция, вызывается периодически
- Защита от параллельного вызова sendWorker/receiveWorker — через Mutex или single-threaded dispatcher
</task>

<task id="2" name="TransportModule — сборка графа зависимостей" type="feat">
Создать `TransportModule.kt` — фабрика/DI для компонентов транспорта:

```kotlin
package ru.cheburmail.app.transport

import ru.cheburmail.app.crypto.CryptoProvider
import ru.cheburmail.app.crypto.MessageDecryptor
import ru.cheburmail.app.crypto.MessageEncryptor
import ru.cheburmail.app.crypto.NonceGenerator

/**
 * Собирает граф зависимостей транспортного модуля.
 *
 * В будущем заменится на Hilt/Koin DI. Сейчас — ручная сборка
 * для минимизации зависимостей.
 */
object TransportModule {

    fun provideSmtpClient(): SmtpClient = SmtpClient()

    fun provideImapClient(): ImapClient = ImapClient()

    fun provideEmailFormatter(): EmailFormatter = EmailFormatter()

    fun provideEmailParser(): EmailParser = EmailParser()

    fun provideRetryStrategy(): RetryStrategy = RetryStrategy()

    fun provideTransportService(
        cryptoProvider: CryptoProvider
    ): TransportService {
        val box = cryptoProvider.box()
        return TransportService(
            smtpClient = provideSmtpClient(),
            imapClient = provideImapClient(),
            emailFormatter = provideEmailFormatter(),
            emailParser = provideEmailParser(),
            encryptor = MessageEncryptor(box, NonceGenerator(cryptoProvider.random())),
            decryptor = MessageDecryptor(box)
        )
    }

    fun provideMessageRepository(
        config: EmailConfig,
        cryptoProvider: CryptoProvider
    ): MessageRepository {
        val service = provideTransportService(cryptoProvider)
        return MessageRepository(
            transportService = service,
            sendWorker = SendWorker(service, provideRetryStrategy()),
            receiveWorker = ReceiveWorker(service, MessageDecryptor(cryptoProvider.box()), provideRetryStrategy()),
            config = config
        )
    }
}
```

ВАЖНО:
- Пока без DI-фреймворка (Hilt/Koin добавится позже)
- CryptoProvider берётся из фазы 1 — предоставляет Lazysodium Box.Native и Random
- EmailConfig создаётся из пользовательских настроек (фаза 4)
- DAO-зависимости добавятся после интеграции с Room (фаза 2)
</task>

<task id="3" name="Дедупликация — гарантии уникальности" type="feat">
Обеспечить надёжную дедупликацию на всех уровнях:

1. **Room уровень** (фаза 2 — подготовить контракт):
   - `messages.uuid` — PRIMARY KEY или UNIQUE INDEX
   - `MessageDao.existsById(uuid): Boolean` — лёгкий запрос `SELECT EXISTS(SELECT 1 FROM messages WHERE uuid = :uuid)`
   - INSERT с `OnConflictStrategy.IGNORE` — если UUID уже есть, вставка молча пропускается

2. **ReceiveWorker уровень** (план 02):
   - Проверка `existsById(msgUuid)` ПЕРЕД расшифровкой — экономит CPU на crypto_box_open
   - Логирование: `"Duplicate $msgUuid from $fromEmail — skipping"` (DEBUG level)

3. **IMAP уровень**:
   - Сообщение помечается как SEEN после обработки (успешной или дубликата)
   - Повторный fetch не вернёт уже обработанные сообщения (UNSEEN filter)
   - Если IMAP SEEN-флаг потерялся (reconnect) — Room дедупликация подстрахует

4. **UUID формат**:
   - `java.util.UUID.randomUUID()` — 128 бит, вероятность коллизии ~0 для нашего масштаба
   - Формат в subject: `CM/1/<chatId>/<uuid>` — uuid включает дефисы (e.g. `550e8400-e29b-41d4-a716-446655440000`)
   - Парсер извлекает uuid как всё после последнего `/` в subject после `CM/1/<chatId>/`
</task>

<task id="4" name="Unit-тесты MessageRepository" type="test">
Создать `MessageRepositoryTest.kt`:

1. `sendMessage_createsQueueEntryAndTriggersWorker` — вызов sendMessage создаёт запись в send_queue и запускает SendWorker
2. `sendMessage_generatesUniqueUuid` — два вызова sendMessage генерируют разные UUID
3. `checkIncoming_delegatesToReceiveWorker` — checkIncoming вызывает ReceiveWorker.pollAndProcess
4. `retrySending_processesQueuedMessages` — retrySending обрабатывает все QUEUED-записи

Моки: TransportService, SendWorker, ReceiveWorker (через интерфейсы или MockK).
</task>

<task id="5" name="Интеграционный тест — полный pipeline" type="test">
Создать `IntegrationTest.kt` — сквозной тест без реальных SMTP/IMAP-серверов:

```kotlin
package ru.cheburmail.app.transport

/**
 * Интеграционный тест: полный pipeline отправки и получения.
 *
 * Использует TestCryptoProvider (из фазы 1) для реальных crypto-операций
 * и мок-реализации SmtpClient/ImapClient для имитации сети.
 */
class IntegrationTest {

    /**
     * Сценарий: Алиса отправляет сообщение Бобу.
     *
     * 1. Алиса генерирует ключевую пару (TestCryptoProvider)
     * 2. Боб генерирует ключевую пару
     * 3. Алиса шифрует "Привет, Боб!" → format → EmailMessage
     * 4. EmailMessage "доставляется" через MockSmtpClient → MockImapClient
     * 5. Боб получает email → parse → decrypt → "Привет, Боб!"
     */
    @Test
    fun fullPipeline_aliceToBob_messageDecrypted() { ... }

    /**
     * Сценарий: дубликат не создаёт второе сообщение.
     *
     * 1. Боб получает email с UUID "abc-123"
     * 2. Боб получает тот же email с UUID "abc-123" повторно
     * 3. В "Room" (in-memory map) только одно сообщение
     */
    @Test
    fun duplicateUuid_secondMessageIgnored() { ... }

    /**
     * Сценарий: ошибка SMTP → retry → успех.
     *
     * 1. SmtpClient выбрасывает SmtpException на первый вызов
     * 2. SendWorker обрабатывает retry
     * 3. SmtpClient успешно отправляет на второй вызов
     * 4. Статус сообщения → SENT
     */
    @Test
    fun smtpError_retrySucceeds_messageSent() { ... }

    /**
     * Сценарий: 5 ошибок SMTP → FAILED.
     *
     * 1. SmtpClient выбрасывает SmtpException 5 раз
     * 2. SendWorker исчерпывает попытки
     * 3. Статус сообщения → FAILED
     */
    @Test
    fun smtpError_maxRetries_messageFailed() { ... }

    /**
     * Сценарий: format → parse round-trip с реальной криптографией.
     *
     * 1. Зашифровать "Тестовое сообщение" через MessageEncryptor
     * 2. EmailFormatter.format → EmailMessage
     * 3. EmailParser.parse → ParsedMessage
     * 4. MessageDecryptor.decrypt → "Тестовое сообщение"
     */
    @Test
    fun cryptoFormatParse_roundTrip_plaintextPreserved() { ... }
}
```

Мок-реализации для тестов:

```kotlin
class MockSmtpClient : SmtpClient() {
    val sentEmails = mutableListOf<EmailMessage>()
    var errorCount = 0 // количество ошибок перед успехом

    override fun send(config: EmailConfig, message: EmailMessage) {
        if (errorCount > 0) {
            errorCount--
            throw TransportException.SmtpException("Mock SMTP error")
        }
        sentEmails.add(message)
    }
}

class MockImapClient : ImapClient() {
    val inbox = mutableListOf<EmailMessage>()

    override fun fetchMessages(config: EmailConfig): List<EmailMessage> = inbox.toList()
    override fun ensureCheburMailFolder(config: EmailConfig) { /* noop */ }
}
```

ВАЖНО: TestCryptoProvider из фазы 1 (`app/src/test/java/ru/cheburmail/app/crypto/TestCryptoProvider.kt`) используется для реальных crypto-операций в тестах — НЕ мокать криптографию.
</task>

## must_haves

- [ ] MessageRepository: высокоуровневый API — sendMessage(plaintext, chatId, recipientEmail), checkIncoming(), retrySending()
- [ ] TransportModule: сборка графа зависимостей без DI-фреймворка
- [ ] Дедупликация на 3 уровнях: IMAP SEEN → Room existsById → INSERT IGNORE
- [ ] UUID формат зафиксирован: java.util.UUID.randomUUID() с дефисами
- [ ] Интеграционный тест: полный pipeline Алиса→Боб (encrypt→format→parse→decrypt) проходит
- [ ] Интеграционный тест: дубликат UUID не создаёт второе сообщение
- [ ] Интеграционный тест: retry после SMTP-ошибки → SENT; 5 ошибок → FAILED
- [ ] Все тесты проходят через `./gradlew test`

## Верификация

1. `./gradlew test --tests "ru.cheburmail.app.transport.MessageRepositoryTest"` — проходит
2. `./gradlew test --tests "ru.cheburmail.app.transport.IntegrationTest"` — проходит
3. Ручная проверка: fullPipeline тест — plaintext "Привет, Боб!" зашифровывается, форматируется в email, парсится обратно и расшифровывается в "Привет, Боб!"
4. Ручная проверка: при подключении реального SMTP/IMAP (Yandex) — email отправляется и принимается (опционально, требует учётные данные)
5. `./gradlew test --tests "ru.cheburmail.app.transport.*"` — все тесты транспортного модуля проходят
