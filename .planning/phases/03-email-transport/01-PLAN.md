---
plan: 01
title: Email-клиент SMTP/IMAP
wave: 1
depends_on: []
autonomous: true
files_modified:
  - gradle/libs.versions.toml
  - app/build.gradle.kts
  - app/src/main/java/ru/cheburmail/app/transport/EmailConfig.kt
  - app/src/main/java/ru/cheburmail/app/transport/EmailMessage.kt
  - app/src/main/java/ru/cheburmail/app/transport/EmailProvider.kt
  - app/src/main/java/ru/cheburmail/app/transport/SmtpClient.kt
  - app/src/main/java/ru/cheburmail/app/transport/ImapClient.kt
  - app/src/main/java/ru/cheburmail/app/transport/EmailFormatter.kt
  - app/src/main/java/ru/cheburmail/app/transport/EmailParser.kt
  - app/src/main/java/ru/cheburmail/app/transport/TransportException.kt
  - app/src/test/java/ru/cheburmail/app/transport/EmailFormatterTest.kt
  - app/src/test/java/ru/cheburmail/app/transport/EmailParserTest.kt
  - app/src/test/java/ru/cheburmail/app/transport/EmailRoundTripFormatTest.kt
  - app/proguard-rules.pro
---

# Email-клиент SMTP/IMAP

## Цель

Реализовать низкоуровневый email-клиент: SMTP-отправку и IMAP-получение через JavaMail (com.sun.mail:android-mail). Определить модели данных (EmailConfig, EmailMessage, EmailProvider), формат email для CheburMail (Subject=`CM/1/<chatId>/<msgUuid>`, MIME-тип `application/x-cheburmail`, тело — Base64(nonce+ciphertext)), а также парсер/форматтер для конвертации между EncryptedEnvelope и email. Полностью покрыть формат unit-тестами (JVM).

## Задачи

<task id="1" name="Зависимость JavaMail в Gradle" type="feat">
Добавить JavaMail для Android в version catalog и app/build.gradle.kts:

1. `gradle/libs.versions.toml` — добавить:
   ```toml
   [versions]
   android-mail = "1.6.2"
   android-activation = "1.6.2"

   [libraries]
   android-mail = { group = "com.sun.mail", name = "android-mail", version.ref = "android-mail" }
   android-activation = { group = "com.sun.mail", name = "android-activation", version.ref = "android-activation" }
   ```

2. `app/build.gradle.kts` — добавить в dependencies:
   ```kotlin
   implementation(libs.android.mail)
   implementation(libs.android.activation)
   ```

3. `app/proguard-rules.pro` — добавить правила для JavaMail:
   ```
   -keep class com.sun.mail.** { *; }
   -keep class javax.mail.** { *; }
   -keep class javax.activation.** { *; }
   -dontwarn com.sun.mail.**
   -dontwarn javax.mail.**
   -dontwarn javax.activation.**
   ```
</task>

<task id="2" name="Модели данных транспорта" type="feat">
Создать data-классы в пакете `ru.cheburmail.app.transport`:

1. `EmailProvider.kt` — enum провайдеров:
   ```kotlin
   package ru.cheburmail.app.transport

   enum class EmailProvider(
       val smtpHost: String,
       val smtpPort: Int,
       val imapHost: String,
       val imapPort: Int
   ) {
       YANDEX(
           smtpHost = "smtp.yandex.ru",
           smtpPort = 465,
           imapHost = "imap.yandex.ru",
           imapPort = 993
       ),
       MAILRU(
           smtpHost = "smtp.mail.ru",
           smtpPort = 465,
           imapHost = "imap.mail.ru",
           imapPort = 993
       )
   }
   ```

2. `EmailConfig.kt` — конфигурация подключения:
   ```kotlin
   package ru.cheburmail.app.transport

   data class EmailConfig(
       val email: String,
       val password: String,
       val provider: EmailProvider
   ) {
       val smtpHost: String get() = provider.smtpHost
       val smtpPort: Int get() = provider.smtpPort
       val imapHost: String get() = provider.imapHost
       val imapPort: Int get() = provider.imapPort
   }
   ```

3. `EmailMessage.kt` — email-сообщение:
   ```kotlin
   package ru.cheburmail.app.transport

   data class EmailMessage(
       val from: String,
       val to: String,
       val subject: String,
       val body: ByteArray,
       val contentType: String = CHEBURMAIL_CONTENT_TYPE
   ) {
       companion object {
           const val CHEBURMAIL_CONTENT_TYPE = "application/x-cheburmail"
           const val SUBJECT_PREFIX = "CM/1/"
       }

       override fun equals(other: Any?): Boolean {
           if (this === other) return true
           if (other !is EmailMessage) return false
           return from == other.from && to == other.to &&
               subject == other.subject &&
               body.contentEquals(other.body) &&
               contentType == other.contentType
       }

       override fun hashCode(): Int {
           var result = from.hashCode()
           result = 31 * result + to.hashCode()
           result = 31 * result + subject.hashCode()
           result = 31 * result + body.contentHashCode()
           result = 31 * result + contentType.hashCode()
           return result
       }
   }
   ```

4. `TransportException.kt` — исключения транспортного уровня:
   ```kotlin
   package ru.cheburmail.app.transport

   sealed class TransportException(message: String, cause: Throwable? = null) :
       Exception(message, cause) {

       class SmtpException(message: String, cause: Throwable? = null) :
           TransportException(message, cause)

       class ImapException(message: String, cause: Throwable? = null) :
           TransportException(message, cause)

       class FormatException(message: String, cause: Throwable? = null) :
           TransportException(message, cause)
   }
   ```
</task>

<task id="3" name="SmtpClient — отправка email" type="feat">
Создать `SmtpClient.kt` — отправка email через JavaMail:

```kotlin
package ru.cheburmail.app.transport

class SmtpClient {

    /**
     * Отправить email через SMTP.
     *
     * @param config конфигурация (хост, порт, учётные данные)
     * @param message email-сообщение (from, to, subject, body, contentType)
     * @throws TransportException.SmtpException при ошибках подключения/аутентификации/отправки
     */
    fun send(config: EmailConfig, message: EmailMessage) { ... }
}
```

Реализация:
1. Создать `javax.mail.Session` с properties:
   - `mail.smtp.host` = config.smtpHost
   - `mail.smtp.port` = config.smtpPort
   - `mail.smtp.auth` = "true"
   - `mail.smtp.ssl.enable` = "true"
   - `mail.smtp.socketFactory.class` = "javax.net.ssl.SSLSocketFactory"
   - `mail.smtp.connectiontimeout` = "15000"
   - `mail.smtp.timeout` = "15000"
   - `mail.smtp.writetimeout` = "15000"

2. Аутентификация через `javax.mail.Authenticator` (email + password из config)

3. Создать `MimeMessage`:
   - `setFrom(InternetAddress(message.from))`
   - `setRecipient(TO, InternetAddress(message.to))`
   - `setSubject(message.subject)`
   - Тело: `MimeBodyPart` с `DataHandler(ByteArrayDataSource(message.body, message.contentType))`
   - Обернуть в `MimeMultipart` с единственной частью

4. Отправить через `Transport.send(mimeMessage)`

5. Все исключения JavaMail ловить и перебрасывать как `TransportException.SmtpException`

ВАЖНО: метод блокирующий (вызывается из фонового потока/корутины). Не создавать suspend-функцию — оставить синхронным для гибкости вызова.
</task>

<task id="4" name="ImapClient — получение email" type="feat">
Создать `ImapClient.kt` — получение email через IMAP:

```kotlin
package ru.cheburmail.app.transport

class ImapClient {

    /**
     * Подключиться к IMAP, создать папку CheburMail (если не существует),
     * получить новые сообщения из неё.
     *
     * @param config конфигурация подключения
     * @return список EmailMessage из папки CheburMail
     * @throws TransportException.ImapException при ошибках
     */
    fun fetchMessages(config: EmailConfig): List<EmailMessage> { ... }

    /**
     * Проверить и создать папку CheburMail на IMAP-сервере.
     * Если папка не существует — создать.
     *
     * @param config конфигурация подключения
     * @throws TransportException.ImapException при ошибках
     */
    fun ensureCheburMailFolder(config: EmailConfig) { ... }

    companion object {
        const val CHEBURMAIL_FOLDER = "CheburMail"
    }
}
```

Реализация `fetchMessages`:
1. Создать `javax.mail.Session` с properties:
   - `mail.imap.host` = config.imapHost
   - `mail.imap.port` = config.imapPort
   - `mail.imap.ssl.enable` = "true"
   - `mail.imap.connectiontimeout` = "15000"
   - `mail.imap.timeout` = "15000"

2. Подключиться через `session.getStore("imaps")`, `store.connect(host, email, password)`

3. Открыть папку "CheburMail" (READ_WRITE). Если не существует — вызвать `ensureCheburMailFolder` и повторить.

4. Получить все UNSEEN-сообщения (`folder.search(FlagTerm(Flags(Flags.Flag.SEEN), false))`)

5. Для каждого сообщения:
   - Проверить `subject` начинается с "CM/1/"
   - Извлечь body как ByteArray (декодировать из content)
   - Создать `EmailMessage(from, to, subject, body, contentType)`
   - Пометить сообщение как SEEN

6. Закрыть store в finally-блоке

Реализация `ensureCheburMailFolder`:
1. Подключиться к IMAP
2. Получить `defaultFolder`
3. Проверить `folder.exists()` для "CheburMail"
4. Если не существует — `folder.create(Folder.HOLDS_MESSAGES)`
5. На Yandex/Mail.ru: если `create` не поддерживается — создать через INBOX.CheburMail как fallback
6. Закрыть store в finally-блоке
</task>

<task id="5" name="EmailFormatter — конвертация EncryptedEnvelope в email" type="feat">
Создать `EmailFormatter.kt` — формирование email из зашифрованного конверта:

```kotlin
package ru.cheburmail.app.transport

import android.util.Base64
import ru.cheburmail.app.crypto.model.EncryptedEnvelope

class EmailFormatter {

    /**
     * Сформировать EmailMessage из зашифрованного конверта.
     *
     * Subject: CM/1/<chatId>/<msgUuid>
     * Body: Base64(nonce || ciphertext) — wire format EncryptedEnvelope
     * Content-Type: application/x-cheburmail
     *
     * @param envelope зашифрованный конверт (nonce + ciphertext)
     * @param chatId идентификатор чата
     * @param msgUuid UUID сообщения (для дедупликации)
     * @param fromEmail email отправителя
     * @param toEmail email получателя
     * @return EmailMessage готовый к отправке
     */
    fun format(
        envelope: EncryptedEnvelope,
        chatId: String,
        msgUuid: String,
        fromEmail: String,
        toEmail: String
    ): EmailMessage {
        val subject = "${EmailMessage.SUBJECT_PREFIX}$chatId/$msgUuid"
        val wireBytes = envelope.toBytes() // nonce || ciphertext
        val base64Body = Base64.encode(wireBytes, Base64.NO_WRAP)

        return EmailMessage(
            from = fromEmail,
            to = toEmail,
            subject = subject,
            body = base64Body,
            contentType = EmailMessage.CHEBURMAIL_CONTENT_TYPE
        )
    }
}
```

ВАЖНО:
- Base64 кодирование через `android.util.Base64` (флаг NO_WRAP — без переносов строк)
- Subject строго в формате `CM/1/<chatId>/<msgUuid>` — chatId и msgUuid не должны содержать `/`
- Wire format конверта: первые 24 байта — nonce, остальное — ciphertext (как в EncryptedEnvelope.toBytes())

Для unit-тестов (JVM): использовать `java.util.Base64` вместо `android.util.Base64`. Создать внутренний интерфейс `Base64Encoder` с двумя реализациями (Android/JVM) или инжектить функцию кодирования.
</task>

<task id="6" name="EmailParser — парсинг email в EncryptedEnvelope" type="feat">
Создать `EmailParser.kt` — обратная конвертация email в зашифрованный конверт:

```kotlin
package ru.cheburmail.app.transport

import android.util.Base64
import ru.cheburmail.app.crypto.model.EncryptedEnvelope

class EmailParser {

    /**
     * Результат парсинга входящего email.
     */
    data class ParsedMessage(
        val chatId: String,
        val msgUuid: String,
        val envelope: EncryptedEnvelope,
        val fromEmail: String
    )

    /**
     * Распарсить EmailMessage обратно в структурированные данные.
     *
     * @param email входящий email
     * @return ParsedMessage с chatId, msgUuid, EncryptedEnvelope и email отправителя
     * @throws TransportException.FormatException если формат email невалиден
     */
    fun parse(email: EmailMessage): ParsedMessage {
        // 1. Валидировать subject: должен начинаться с "CM/1/"
        if (!email.subject.startsWith(EmailMessage.SUBJECT_PREFIX)) {
            throw TransportException.FormatException(
                "Invalid subject: expected prefix '${EmailMessage.SUBJECT_PREFIX}', got '${email.subject}'"
            )
        }

        // 2. Извлечь chatId и msgUuid из subject
        val parts = email.subject.removePrefix(EmailMessage.SUBJECT_PREFIX).split("/")
        if (parts.size != 2) {
            throw TransportException.FormatException(
                "Invalid subject format: expected CM/1/<chatId>/<msgUuid>, got '${email.subject}'"
            )
        }
        val chatId = parts[0]
        val msgUuid = parts[1]

        // 3. Валидировать chatId и msgUuid не пустые
        if (chatId.isBlank() || msgUuid.isBlank()) {
            throw TransportException.FormatException(
                "chatId and msgUuid must not be blank in subject '${email.subject}'"
            )
        }

        // 4. Декодировать тело из Base64
        val wireBytes = Base64.decode(email.body, Base64.NO_WRAP)

        // 5. Десериализовать EncryptedEnvelope из wire bytes
        val envelope = EncryptedEnvelope.fromBytes(wireBytes)

        return ParsedMessage(
            chatId = chatId,
            msgUuid = msgUuid,
            envelope = envelope,
            fromEmail = email.from
        )
    }

    /**
     * Проверить, является ли email сообщением CheburMail (по subject и content-type).
     */
    fun isCheburMail(email: EmailMessage): Boolean {
        return email.subject.startsWith(EmailMessage.SUBJECT_PREFIX) &&
            email.contentType == EmailMessage.CHEBURMAIL_CONTENT_TYPE
    }
}
```
</task>

<task id="7" name="Unit-тесты EmailFormatter и EmailParser" type="test">
Создать unit-тесты в `app/src/test/java/ru/cheburmail/app/transport/`:

1. `EmailFormatterTest.kt`:
   - `format_correctSubjectFormat` — subject = "CM/1/chat123/uuid456"
   - `format_bodyIsBase64OfEnvelopeBytes` — декодируем body обратно и сравниваем с envelope.toBytes()
   - `format_contentTypeIsCheburMail` — contentType = "application/x-cheburmail"
   - `format_fromAndToPreserved` — from/to совпадают с переданными

2. `EmailParserTest.kt`:
   - `parse_validEmail_returnsParsedMessage` — корректный email парсится в chatId, msgUuid, envelope
   - `parse_invalidSubjectPrefix_throwsFormatException` — subject "INVALID/..." выбрасывает FormatException
   - `parse_missingUuid_throwsFormatException` — subject "CM/1/chatOnly" выбрасывает FormatException
   - `parse_emptyChatId_throwsFormatException` — subject "CM/1//uuid" выбрасывает FormatException
   - `isCheburMail_validEmail_returnsTrue`
   - `isCheburMail_wrongContentType_returnsFalse`
   - `isCheburMail_wrongSubject_returnsFalse`

3. `EmailRoundTripFormatTest.kt`:
   - `formatThenParse_roundTrip` — format → parse → проверить что chatId, msgUuid и envelope совпадают с оригиналом
   - `formatThenParse_variousEnvelopeSizes` — тест с разными размерами ciphertext (минимальный, 1KB, 64KB)
   - `formatThenParse_specialCharactersInChatId` — chatId с цифрами и дефисами

Для JVM-тестов: использовать `java.util.Base64` вместо `android.util.Base64`. Абстрагировать Base64 через internal интерфейс или companion-функцию, которая подменяется в тестах.
</task>

## must_haves

- [ ] JavaMail 1.6.2 (android-mail + android-activation) добавлена в libs.versions.toml и app/build.gradle.kts
- [ ] SmtpClient: подключение SSL к smtp.yandex.ru:465 и smtp.mail.ru:465, аутентификация, отправка с MIME-типом application/x-cheburmail
- [ ] ImapClient: подключение SSL к imap.yandex.ru:993 и imap.mail.ru:993, создание папки CheburMail, выборка UNSEEN-сообщений
- [ ] EmailFormatter: EncryptedEnvelope → EmailMessage (Subject=CM/1/<chatId>/<msgUuid>, Body=Base64(nonce||ciphertext))
- [ ] EmailParser: EmailMessage → ParsedMessage (chatId, msgUuid, EncryptedEnvelope, fromEmail)
- [ ] Все unit-тесты EmailFormatter/EmailParser проходят через `./gradlew test`
- [ ] ProGuard-правила для JavaMail (keep com.sun.mail.**, javax.mail.**, javax.activation.**)
- [ ] Таймауты на SMTP/IMAP: 15 секунд (connect, read, write)

## Верификация

1. `./gradlew test --tests "ru.cheburmail.app.transport.*"` — все unit-тесты проходят
2. `./gradlew assembleDebug` — проект компилируется с новой зависимостью
3. `./gradlew dependencies --configuration releaseRuntimeClasspath | grep android-mail` — JavaMail 1.6.2 в дереве зависимостей
4. Ручная проверка: создать EmailFormatter → format → EmailParser → parse, убедиться что round-trip сохраняет все данные
