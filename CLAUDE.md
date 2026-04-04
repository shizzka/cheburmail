# CheburMail — CLAUDE.md

## Что это
E2E зашифрованный мессенджер для Android поверх Yandex Mail / Mail.ru.
Сообщения шифруются клиентски (X25519 + XSalsa20-Poly1305 через libsodium), отправляются как email через IMAP/SMTP.
Провайдер видит только метаданные (кто с кем), но не содержимое.

## Стек
- Kotlin 2.3.20, Jetpack Compose (BOM 2026.03.00), AGP 9.1.0, Gradle 9.3.1
- minSdk 26, targetSdk 35
- Crypto: lazysodium-android 5.2.0 + JNA 5.18.1 + Tink 1.21.0
- Transport: JavaMail 1.6.2 (IMAP/SMTP)
- Storage: Room 2.8.4, DataStore 1.2.1 (encrypted via Tink AEAD)
- Sync: WorkManager 2.10.0
- QR: ZXing 3.5.3 (генерация), Google Code Scanner (сканирование)

## Сборка
```bash
export ANDROID_HOME=/home/q/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

./gradlew assembleDebug --no-daemon
# APK: app/build/outputs/apk/debug/app-debug.apk

./gradlew assembleRelease --no-daemon
# Потом подписать: apksigner sign --ks cheburmail-release.jks ...
```

## Подписание
- Release keystore: `/home/q/cheburmail/cheburmail-release.jks`, alias `cheburmail`, пароль `***REDACTED***`
- Debug: стандартный `~/.android/debug.keystore`
- НЕЛЬЗЯ ставить release поверх debug и наоборот — разные подписи

## Установка на устройства
```bash
# Eugene (Xiaomi 13T Pro): обычный adb install
adb install -r app-debug.apk

# Marina (Xiaomi, серийник HYMRLR6TJJONCMAY): adb install блокируется MIUI
adb push app-debug.apk /data/local/tmp/cheburmail.apk
adb shell pm install -r -t --user 0 /data/local/tmp/cheburmail.apk
```

## Telegram для отправки APK
- Bot: @my_fabrica_bot, token: `***REDACTED_BOT_TOKEN***`
- Eugene chat_id: `***REDACTED_CHAT_ID***`
- Marina chat_id: `***REDACTED_CHAT_ID***`

## Известные грабли сборки
1. **JNA конфликт**: lazysodium тянет JNA jar транзитивно. Нужен exclude + явный JNA @aar
2. **ABI фильтр**: JNA тянет armeabi/mips. Нужен `ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }`
3. **DataStore синглтон**: AccountStorage и EncryptedDataStoreFactory.create() — синглтоны, иначе краш
4. **Serialization plugin**: `kotlin.serialization` обязателен, без него runtime crash
5. **KeyPair.privateKey — private**: доступ через `getPrivateKey()`
6. **AGP 9.0+**: не нужен `kotlin.android` плагин, нет `kotlinOptions` блока

## Архитектура отправки сообщений
1. ChatViewModel: шифрует сообщение публичным ключом контакта
2. Кладёт в send_queue (Room) как EncryptedEnvelope
3. Триггерит OutboxDrainWorker (WorkManager)
4. SendWorker: берёт из очереди → форматирует email (Subject: `CM/1/<chatId>/<msgUuid>`, Body: Base64) → SMTP
5. После отправки: статус сообщения SENDING → SENT

## Приём сообщений
- PeriodicSyncWorker: каждые 15 мин через WorkManager
- ImapIdleService: ForegroundService для real-time
- ReceiveWorker: IMAP poll → EmailParser → decrypt → save to Room

## Email формат
- Subject: `CM/1/<chatId>/<msgUuid>`
- Body: Base64(nonce || ciphertext)
- Content-Type: `application/x-cheburmail`
- IMAP: Yandex (imap.yandex.ru:993), Mail.ru (imap.mail.ru:993)
- SMTP: Yandex (smtp.yandex.ru:465), Mail.ru (smtp.mail.ru:465)

## Структура кода
```
app/src/main/java/ru/cheburmail/app/
├── crypto/          — CryptoProvider, KeyPairGenerator, MessageEncryptor/Decryptor, QR
├── db/              — Room database, entities, DAOs
├── transport/       — SmtpClient, ImapClient, EmailFormatter/Parser, SendWorker, ReceiveWorker
├── storage/         — SecureKeyStorage, AccountStorage, EncryptedDataStoreFactory
├── sync/            — PeriodicSyncWorker, OutboxDrainWorker, ImapIdleService, SyncManager
├── ui/              — Compose screens (onboarding, contacts, chat, settings)
├── group/           — GroupManager, ControlMessages
├── messaging/       — DeliveryReceipts, DisappearingMessages
├── account/         — MultiAccountManager, RateLimitTracker
├── backup/          — KeyBackupManager
├── di/              — TransportModule
└── notification/    — NotificationHelper
```

## Текущий статус
Работает: онбординг, генерация ключей, QR-код с реальным email, сканер QR (Google Code Scanner), создание чатов, шифрование + отправка через SMTP, локальное сохранение сообщений.
В процессе тестирования: реальная доставка между двумя устройствами.

## Документация на русском
Вся проектная документация и сопровождение разработки — на русском языке.
Планирование в `.planning/` (REQUIREMENTS.md, ROADMAP.md, STATE.md).
