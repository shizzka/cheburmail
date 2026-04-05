# PLAN-02 Summary: Transport Layer for Media Attachments

## Что сделано

Расширен SMTP/IMAP транспортный слой для отправки и приёма мультипарт-писем с зашифрованными бинарными вложениями.

### Task 1 — EmailMessage.kt
- Добавлены константы `MEDIA_SUBJECT_SUFFIX = "/M"` и `CHEBURMAIL_MEDIA_CONTENT_TYPE = "application/x-cheburmail-media"`
- Добавлено опциональное поле `attachment: ByteArray? = null`
- Обновлены `equals()` и `hashCode()` с учётом `attachment`
- Добавлен хелпер `isMediaMessage()` (проверяет суффикс `/M`)

### Task 2 — SmtpClient.kt
- Добавлен метод `sendWithAttachment()` для отправки `multipart/mixed` с двумя MIME-частями
- Part 0: зашифрованные метаданные, Part 1: зашифрованный payload с именем `payload.enc`
- `MEDIA_WRITE_TIMEOUT = 120000` (2 минуты) для крупных бинарных вложений
- Существующий метод `send()` не изменён

### Task 3 — ImapClient.kt
- Добавлен data class `ExtractedMediaParts(metadataBytes, payloadBytes)` с корректными `equals`/`hashCode`
- `fetchMessages()` теперь определяет медиа-сообщения (суффикс `/M`), устанавливает `CHEBURMAIL_MEDIA_CONTENT_TYPE` и вызывает `extractAttachment()`
- Добавлен приватный метод `extractAttachment()` — извлекает Part 1 из `multipart/mixed`
- Поле `attachment` в `EmailMessage` заполняется для медиа-сообщений, `null` для текстовых

### Task 4 — EmailFormatter.kt
- Добавлен метод `formatMedia()` — создаёт `EmailMessage` с суффиксом `/M` в Subject
- Оба конверта (`metadataEnvelope` и `payloadEnvelope`) Base64-кодируются
- Body = Base64(metadataEnvelope), Attachment = Base64(payloadEnvelope)
- Content-Type: `application/x-cheburmail-media`

### Task 5 — EmailParser.kt
- Добавлен data class `ParsedMediaMessage(chatId, msgUuid, metadataEnvelope, payloadEnvelope, fromEmail)`
- Добавлен хелпер `isCheburMailMedia()` — проверяет prefix `CM/1/`, suffix `/M` и content-type
- Добавлен метод `parseMedia()` — снимает prefix и suffix, парсит chatId и msgUuid, Base64-декодирует body и attachment, конвертирует в `EncryptedEnvelope`

## Результат сборки

`./gradlew assembleDebug --no-daemon` — BUILD SUCCESSFUL

## Затронутые файлы

- `app/src/main/java/ru/cheburmail/app/transport/EmailMessage.kt`
- `app/src/main/java/ru/cheburmail/app/transport/SmtpClient.kt`
- `app/src/main/java/ru/cheburmail/app/transport/ImapClient.kt`
- `app/src/main/java/ru/cheburmail/app/transport/EmailFormatter.kt`
- `app/src/main/java/ru/cheburmail/app/transport/EmailParser.kt`

## Инварианты

- Существующий текстовый flow (`send()`, `parse()`, `isCheburMail()`) не изменён
- Медиа-письма однозначно идентифицируются по суффиксу `/M` в Subject и content-type `application/x-cheburmail-media`
- Текстовые письма: `attachment = null`; медиа-письма: `attachment != null`
