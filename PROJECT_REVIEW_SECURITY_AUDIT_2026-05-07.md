# Полное ревью и аудит безопасности CheburMail

Дата: 2026-05-07  
Проект: `/home/q/cheburmail`  
Методика: статическое ревью Kotlin/Android-кода, проверка конфигурации Gradle/Manifest/release-скриптов, локальные Gradle-проверки `assembleDebug`, `testDebugUnitTest`, `lintDebug`.

Ограничения: это кодовый аудит и локальная инженерная проверка, не полноценный внешний pentest. Не запускались инструментальные тесты на устройстве/эмуляторе, не выполнялся сетевой аудит реальных IMAP/SMTP провайдеров и отдельный CVE/SCA-скан зависимостей.

## Краткий вывод

Проект в целом собран вокруг правильных базовых решений для приватного мессенджера: SQLCipher для базы, Tink/Android Keystore для секретов, запрет cleartext-трафика, `allowBackup=false`, TLS-проверки для SMTP/IMAP, неэкспортируемые сервисы/receiver-ы. Архитектура понятная: транспорт отделен от crypto, sync, storage и UI.

Главные риски сейчас не в выборе криптопримитивов, а в протокольных управляющих сообщениях и в операционной надежности. Есть два high-risk integrity-дефекта: удаление сообщений по удаленной команде без проверки права удалять конкретное сообщение и удаление пользователя из группы до проверки администратора. Также красные unit-тесты и падающий lint означают, что текущий baseline качества нельзя считать зеленым.

## Снимок проекта

- Android/Kotlin/Compose приложение, пакет `ru.cheburmail.app`.
- `compileSdk = 35`, `minSdk = 26`, `targetSdk = 35`.
- Версия приложения: `versionName = 0.4.0`, `versionCode = 15`.
- Production Kotlin-файлов: 127.
- Unit test Kotlin/Java-файлов: 27.
- Android instrumentation test-файлов: 6.
- Основные зависимости: SQLCipher, libsodium/lazysodium, Tink, JavaMail, Room, WorkManager, DataStore.

## High Priority

### H1. Любой известный контакт может инициировать локальное удаление сообщения

Файл: `app/src/main/java/ru/cheburmail/app/transport/ReceiveWorker.kt:215`

После успешной расшифровки обычного сообщения код проверяет `textStr.startsWith("DELETE:")` и сразу выполняет:

- `messageDao.deleteById(targetMsgId)`;
- `messageDao.insertDeleted(DeletedMessageEntity(targetMsgId))`;
- попытку удаления письма из IMAP по `targetMsgId`.

Проблема: проверяется только то, что сообщение расшифровалось ключом известного отправителя. Не проверяется, что:

- удаляемое сообщение принадлежит этому же `correctChatId`;
- отправитель является автором удаляемого сообщения;
- для группового чата отправитель имеет право удалять чужие сообщения;
- удаляемый ID вообще связан с текущим входящим сообщением.

Практический эффект: известный контакт или участник группы, знающий UUID сообщения, может изменить локальную историю получателя. UUIDы высокоэнтропийные, но в группах и заголовках сообщения ID видимы участникам.

Рекомендация: перед удалением загрузить `targetMsgId` из БД и проверять `chatId`, автора/роль, направление сообщения и политику удаления. Для групп вынести удаление в подписанное/authenticated control-сообщение с явной схемой прав. Добавить regression-тесты: чужой direct-контакт не может удалить сообщение, участник группы не может удалить чужое, admin может только если такая политика задумана.

### H2. `MEMBER_REMOVED` удаляет локальный групповой чат до проверки администратора

Файл: `app/src/main/java/ru/cheburmail/app/group/ControlMessageHandler.kt:164`

В `handleMemberRemoved` ветка `target == selfEmail` удаляет чат локально на строках 175-178 до загрузки чата и до `isFromAdmin`. Комментарий прямо фиксирует это как намеренное поведение.

Проблема: любой известный контакт, чей control-message успешно расшифровался, может отправить `MEMBER_REMOVED` с `targetEmail` жертвы и заставить ее локально удалить групповой чат, даже если отправитель не администратор этой группы.

Рекомендация: сначала загрузить чат и проверить `isFromAdmin(chat, fromEmail)`, затем применять self-removal. Если нужен recovery-путь для сломанного admin metadata, он должен быть явно ограничен legacy-группами и не применяться к группам с `createdBy`.

### H3. Unit-тесты не проходят

Команда:

```bash
env ANDROID_HOME=/home/q/android-sdk JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew testDebugUnitTest --no-daemon
```

Результат: `188 tests completed, 70 failed`.

Основная причина по XML-результатам: `UnsupportedClassVersionError` для `com/goterl/lazysodium/interfaces/...`, class file version `65.0` при runtime Java 17, который поддерживает до `61.0`. Это ломает `TestCryptoProvider`, после чего много тестов падают каскадно через `NoClassDefFoundError`.

Файлы/места:

- `app/build.gradle.kts:168` подключает `testImplementation("com.goterl:lazysodium-java:5.1.1")`;
- отчет тестов: `app/build/reports/tests/testDebugUnitTest/index.html`.

Рекомендация: привести JVM test classpath к Java 17-совместимой версии, зафиксировать dependency resolution для lazysodium/JNA, либо запускать тесты на Java 21 только если это совместимо с Android Gradle Plugin и политикой проекта. После исправления сделать unit-тесты обязательной проверкой перед release.

## Medium Priority

### M1. Delivery ACK противоречит README и небезопасен, если будет подключен

Файлы:

- `README.ru.md:84` и `README.md:84` говорят, что delivery/read receipts не будут реализованы;
- `app/src/main/java/ru/cheburmail/app/messaging/DeliveryReceiptSender.kt`;
- `app/src/main/java/ru/cheburmail/app/messaging/DeliveryReceiptHandler.kt`;
- `app/src/main/java/ru/cheburmail/app/transport/ReceiveWorker.kt:147`.

Код ACK уже есть. `ReceiveWorker` распознает ACK по subject до расшифровки, а handler обновляет delivery status из subject. Сейчас в production `SyncFactory.buildReceiveWorker` не передает `deliveryReceiptSender`/`deliveryReceiptHandler`, поэтому функциональность фактически мертвая. Но тесты и кодовая поверхность уже закрепляют модель subject-only ACK.

Риск: если ACK будет включен без переработки, любой отправитель письма сможет подделать subject вида `CM/1/<chat>/ack-<uuid>` и локально пометить сообщение как доставленное.

Рекомендация: либо удалить dead code и тесты ACK, чтобы соответствовать README, либо сделать ACK полноценным зашифрованным и аутентифицированным сообщением с проверкой отправителя, `chatId` и связи с исходным сообщением.

### M2. App lock PIN хранится как быстрый SHA-256 от короткого PIN

Файл: `app/src/main/java/ru/cheburmail/app/security/AppLockManager.kt:23`

PIN сохраняется в `SharedPreferences` как `SHA-256(pin)` без соли и без slow KDF. В `verifyPin` идет прямое сравнение хеша. Для 4-значного PIN это перебирается мгновенно при доступе к app data. По UI также не видно надежного lockout/throttling.

Важно: это не то же самое, что компрометация SQLCipher-базы, потому что база защищается отдельно. Но как app-lock барьер это слабое место.

Рекомендация: использовать Android Keystore/biometric-gated secret или salted slow KDF с per-install salt, лимитом попыток и задержками. Минимум: хранить salt, ввести attempt counter/backoff и запретить слишком короткие PIN без биометрии.

### M3. Защита от скриншотов выключена по умолчанию, хотя README обещает защиту

Файл: `app/src/main/java/ru/cheburmail/app/storage/AppSettings.kt:110`

`screenshotsBlocked` по умолчанию `false`. При этом README заявляет защиту от скриншотов на экранах чатов. Новая установка приложения по умолчанию позволяет скриншоты/превью в recents, пока пользователь сам не найдет настройку.

Рекомендация: включить `FLAG_SECURE` по умолчанию хотя бы для chat/lock/safety screens, либо явно поправить документацию и onboarding. Для приватного мессенджера безопасный default лучше, чем opt-in.

### M4. Сохранение файлов в Downloads на API 26-28 использует внешний filename без sanitization

Файл: `app/src/main/java/ru/cheburmail/app/media/FileSaver.kt:70`

Для API ниже 29 код делает:

- `dir = Downloads/CheburMail`;
- `file = File(dir, fileName)`;
- запись через `FileOutputStream(file)`.

`fileName` приходит из расшифрованной metadata контакта. Если контакт пришлет имя с `../` или другим path-паттерном, на API 26-28 возможна запись вне ожидаемой папки. Внутреннее сохранение через `MediaFileManager.saveFile` sanitizes имя, но export path в `FileSaver` этого не делает.

Дополнительно lint нашел API-level ошибку в этом же классе: `MediaStore.Downloads.EXTERNAL_CONTENT_URI` требует API 29, а minSdk 26. Хотя вызов находится за runtime-check `SDK_INT >= Q`, lint требует `@RequiresApi`/`@TargetApi` или выделения метода.

Рекомендация: централизовать `sanitizeFileName`, запрещать path separators/control chars, ограничить длину имени, нормализовать extension. Для API 26-28 проверить разрешения на запись и добавить `@RequiresApi(Build.VERSION_CODES.Q)` к MediaStore-ветке.

### M5. `OutboxDrainWorker` обходит `SyncFactory` и не использует multi-account fallback

Файлы:

- `app/src/main/java/ru/cheburmail/app/sync/OutboxDrainWorker.kt:61`;
- `app/src/main/java/ru/cheburmail/app/transport/SendWorker.kt:87`;
- `app/src/main/java/ru/cheburmail/app/transport/SendWorker.kt:198`.

`OutboxDrainWorker` напрямую создает `SendWorker` только с активным аккаунтом и не передает `MultiAccountManager`/`autoFallbackEnabled`. Периодический sync строит worker через factory, но immediate drain queue идет другим путем. В итоге обычные UI-отправки могут не использовать fallback, хотя настройка есть.

Отдельный дефект внутри `SendWorker`: при SMTP-ошибке `tryImmediateFallback` помечает failed `emailConfig.email`, а не реально выбранный на строке 88 `sendConfig.email`. Если `getNextSendAccount()` выбрал не базовый аккаунт, health-состояние и event `FallbackUsed` будут относиться не к тому аккаунту.

Рекомендация: сделать единый путь создания send worker через `SyncFactory` или общий provider. В `SendWorker` передавать в `tryImmediateFallback` фактический `sendConfig`, а не полагаться на `emailConfig`.

### M6. `OutboxDrainWorker` может молча удалить queued messages с большим BLOB

Файл: `app/src/main/java/ru/cheburmail/app/sync/OutboxDrainWorker.kt:45`

Перед обработкой очереди выполняется raw SQL:

```sql
DELETE FROM send_queue WHERE LENGTH(encrypted_payload) > 1000000 AND payload_file_path IS NULL
```

Это предотвращает `CursorWindow`-crash, но удаляет очередь без перевода связанного сообщения в `FAILED` и без видимого recovery-сигнала для пользователя.

Рекомендация: заменить silent delete на явный migration/repair path: пометить сообщение failed, записать диагностическое событие, показать пользователю ошибку и тестом закрепить отсутствие silent data loss.

### M7. Room schemas генерируются, но игнорируются git

Файлы:

- `app/build.gradle.kts:73` задает `schemaDirectory("$projectDir/schemas")`;
- `.gitignore:16` игнорирует `/app/schemas`.

Локально `app/schemas` существует, но `git ls-files app/schemas` ничего не возвращает. Для Room migrations это снижает воспроизводимость clean clone/CI и усложняет проверку auto-migration history.

Рекомендация: коммитить `app/schemas` или явно отключить export, если схемы сознательно не нужны. Для приложения с зашифрованной БД и миграциями лучше хранить schema history в репозитории.

### M8. `release.sh` удаляет и пересоздает существующие GitHub releases/tags

Файл: `release.sh:43`

Если release tag уже существует, скрипт удаляет release и tag, затем создает их заново. Для публичного приложения это ухудшает auditability: один и тот же tag может указывать на другой артефакт.

Рекомендация: считать опубликованные tags immutable. Для повторной публикации использовать новый versionCode/versionName или draft-release до публикации. Если нужен rebuild той же версии, фиксировать checksum и причину.

### M9. Окно с plaintext backup при миграции SQLCipher

Файл: `app/src/main/java/ru/cheburmail/app/db/DbCipherMigrator.kt`

Миграция plaintext DB в SQLCipher создает backup plaintext-файла и удаляет его после успешной миграции. Если приложение упадет между выставлением migrated-флага и удалением backup, plaintext copy может остаться в app data.

Рекомендация: при старте всегда проверять и удалять `*.plaintext.bak`, даже если migrated-флаг уже выставлен. В идеале не хранить plaintext backup дольше минимально необходимого шага и покрыть crash-recovery тестом.

## Low Priority / Maintenance

### L1. `EncryptedEnvelope.fromBytes` отвергает корректный empty-plaintext ciphertext

Файл: `app/src/main/java/ru/cheburmail/app/crypto/model/EncryptedEnvelope.kt:46`

Проверка требует `data.size > NONCE_BYTES + MAC_BYTES`, то есть минимум `nonce + mac + 1`. Для `crypto_box` пустой plaintext дает ciphertext длиной `MAC_BYTES`, и wire payload `nonce + MAC` должен быть валидным. UI сейчас, вероятно, не отправляет пустой текст, но это protocol edge case и тестовый пробел.

Рекомендация: заменить условие на `>= NONCE_BYTES + MAC_BYTES`, если криптобиблиотека поддерживает empty plaintext, и добавить round-trip тест именно через serialization/email parsing.

### L2. Локальный Telegram bot хранит PII и пишет временный inbox-log

Файлы:

- `bot/distro_bot.py`;
- `bot/subscribers.json` находится под ignored `bot/`;
- `/tmp/cheburmail-inbox.jsonl` используется как временный лог входящих сообщений.

Это не Android-приложение, но часть проекта и релизной цепочки. В subscribers есть Telegram IDs/usernames, а временный лог в `/tmp` может пережить процесс и попасть в системные backup/diagnostics.

Рекомендация: документировать retention, права доступа к файлам, очистку `/tmp`, и не включать эти данные в backup/архивы.

### L3. Lint предупреждает о backup policy для Android ниже 12

Файл: `app/src/main/AndroidManifest.xml:14`

`android:dataExtractionRules` применяется с Android 12, lint рекомендует также задать `android:fullBackupContent` для старых версий. При `allowBackup=false` риск низкий, но для ясности политики стоит добавить явный `fullBackupContent` с deny-all или подтвердить, что `allowBackup=false` полностью покрывает поддерживаемые устройства.

## Security Baseline: что сделано хорошо

- `AndroidManifest.xml` содержит `android:allowBackup="false"`.
- `app/src/main/res/xml/data_extraction_rules.xml` исключает root/file/database/sharedpref/external из cloud/device transfer.
- `app/src/main/res/xml/network_security_config.xml` запрещает cleartext traffic.
- Сервисы/receiver-ы в manifest в основном `exported=false`; debug receiver также не экспортируется и включается через debug placeholder.
- SMTP/IMAP клиенты используют SSL и `mail.*.ssl.checkserveridentity=true`.
- SQLCipher passphrase генерируется случайно и хранится через Tink/Android Keystore.
- Account credentials хранятся через encrypted DataStore/Tink.
- Key exchange блокирует замену `VERIFIED` ключа.
- Reactive keyex имеет rate limiting.

## Результаты локальных проверок

### `assembleDebug`

Команда:

```bash
env ANDROID_HOME=/home/q/android-sdk JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug --no-daemon
```

Результат: успешно, `BUILD SUCCESSFUL in 31s`.

### `testDebugUnitTest`

Команда:

```bash
env ANDROID_HOME=/home/q/android-sdk JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew testDebugUnitTest --no-daemon
```

Результат: неуспешно, `188 tests completed, 70 failed`.

Причина: несовместимость class file version `65.0` с Java 17 runtime. HTML-отчет: `app/build/reports/tests/testDebugUnitTest/index.html`.

### `lintDebug`

Команда:

```bash
env ANDROID_HOME=/home/q/android-sdk JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew lintDebug --no-daemon
```

Результат: неуспешно, `1 error, 78 warnings, 2 hints`.

Главная ошибка:

- `app/src/main/java/ru/cheburmail/app/media/FileSaver.kt:59`
- `MediaStore.Downloads.EXTERNAL_CONTENT_URI` требует API 29 при `minSdk = 26`.

Отчеты:

- HTML: `app/build/reports/lint-results-debug.html`;
- text: `app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt`.

## Рекомендованный порядок исправлений

1. Исправить авторизацию `DELETE:` и `MEMBER_REMOVED`, добавить regression-тесты на чужие/неадминские control messages.
2. Решить судьбу delivery ACK: удалить dead code или сделать encrypted/authenticated ACK. Привести README и тесты к фактической политике.
3. Починить unit test classpath/Java version и сделать `testDebugUnitTest` зеленым.
4. Починить `lintDebug` API-level ошибку в `FileSaver`, затем разобрать security/privacy warnings.
5. Провести все отправки через единый `SyncFactory` path и исправить health tracking фактического аккаунта в `SendWorker`.
6. Harden app lock: slow KDF/Keystore, throttling/lockout, тесты.
7. Санитизировать имена файлов на export path и проверить API 26-28 permissions.
8. Закоммитить Room schemas или отключить export осознанно.
9. Сделать release tags immutable и добавить checksum/release notes в процесс.
10. Добавить cleanup plaintext migration backups при каждом старте.

## Примечания по рабочему дереву

До создания этого отчета в рабочем дереве уже было пользовательское изменение `release.sh`: proxy в Telegram upload изменен с `127.0.0.1:7897` на `127.0.0.1:10809`. Оно не относится к аудиту и не изменялось.

