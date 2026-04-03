# CheburMail

## What This Is

Мессенджер, использующий email (Yandex Mail / Mail.ru) как транспортный слой для обмена зашифрованными сообщениями. Создан для условий российского "белого списка" интернета, где доступны только отечественные сервисы. Email-провайдеры видят только зашифрованный blob — содержимое сообщений им недоступно.

## Core Value

Безопасная текстовая коммуникация через инфраструктуру, которую невозможно заблокировать в РФ, с гарантией E2E шифрования.

## Problem

В условиях изоляции (чебурнет) заблокированы Telegram, Signal, WhatsApp и другие мессенджеры. Работают только Yandex и Mail.ru. Нужен способ общаться приватно, используя доступную инфраструктуру как транспорт.

## How It Works

1. Каждый участник регистрирует свой ящик на Yandex или Mail.ru
2. При первой встрече обмениваются публичными ключами через QR-код
3. Сообщения шифруются X25519 + XSalsa20-Poly1305 (libsodium) на устройстве отправителя
4. Зашифрованный payload отправляется как email (SMTP) на ящик получателя
5. Получатель забирает email (IMAP), расшифровывает локально
6. Email-провайдер видит только From/To и encrypted blob

## Stack

- **Платформа:** Android (Kotlin + Jetpack Compose)
- **Шифрование:** Lazysodium-android (обёртка libsodium) — X25519 DH, XSalsa20-Poly1305 AEAD
- **Транспорт:** JavaMail / Android-native IMAP/SMTP напрямую к Yandex/Mail.ru
- **Хранение:** Room (SQLite) для истории сообщений, EncryptedSharedPreferences для ключей
- **Обмен ключами:** QR-код при личной встрече (сканирование камерой)

## Architecture Overview

```
┌─────────────┐       SMTP (encrypted payload)       ┌──────────────┐
│  Sender App │ ──────────────────────────────────── → │ Yandex/Mail  │
│  (Android)  │                                       │   IMAP/SMTP  │
│             │ ← ──────────────────────────────────── │   Servers    │
└─────────────┘       IMAP (poll encrypted emails)    └──────────────┘
                                                             │
                                                             ▼
                                                      ┌──────────────┐
                                                      │ Receiver App │
                                                      │  (Android)   │
                                                      └──────────────┘

On-device:
┌────────────────────────────────────────────────┐
│ UI Layer (Jetpack Compose)                     │
│  ├── Chat list                                 │
│  ├── Chat screen                               │
│  └── Contact/key management                    │
├────────────────────────────────────────────────┤
│ Crypto Layer (Lazysodium)                      │
│  ├── Key generation (X25519 keypair)           │
│  ├── Encrypt (sealed box / crypto_box)         │
│  └── Decrypt                                   │
├────────────────────────────────────────────────┤
│ Transport Layer (JavaMail)                     │
│  ├── SMTP sender                               │
│  ├── IMAP receiver (polling)                   │
│  └── Email parsing/formatting                  │
├────────────────────────────────────────────────┤
│ Storage Layer (Room + EncryptedSharedPrefs)    │
│  ├── Messages DB                               │
│  ├── Contacts + public keys                    │
│  └── Private key (encrypted)                   │
└────────────────────────────────────────────────┘
```

## Users

- Люди в РФ с ограниченным доступом в интернет
- Технически грамотные (готовы обменяться QR-кодами при встрече)
- 1-на-1 чаты и групповые чаты

## Constraints

- **Rate limits:** Yandex/Mail.ru ~50-100 писем/час — ок для MVP, нужна оптимизация позже
- **Латентность:** IMAP polling, не push — задержка сообщений секунды-минуты
- **Email metadata:** From/To видны провайдеру — он знает КТО общается, но не ЧТО
- **QR-обмен:** Требует физической встречи для первого контакта
- **Размер:** Email ограничивает размер вложений (~25MB) — для MVP только текст

## Risks

- Yandex/Mail.ru могут блокировать "подозрительный" трафик (частые emails с encrypted blob)
- IMAP polling разряжает батарею на Android
- Групповые чаты при N участниках = N emails на каждое сообщение (квадратичный рост)

## Requirements

### Validated

(None yet — ship to validate)

### Active

- [ ] Настройка email-аккаунта (IMAP/SMTP credentials) в приложении
- [ ] Генерация X25519 ключевой пары при первом запуске
- [ ] Обмен публичными ключами через QR-код
- [ ] Шифрование сообщений (crypto_box) перед отправкой
- [ ] Отправка зашифрованного сообщения через SMTP
- [ ] Получение и расшифровка сообщений через IMAP
- [ ] Список чатов с контактами
- [ ] Экран чата с историей сообщений
- [ ] Хранение истории в зашифрованной локальной БД
- [ ] Групповой чат (sender-keys: шифруем для каждого участника)

### Out of Scope

- Пересылка файлов/картинок — v2
- Push-уведомления (нет серверной инфраструктуры) — v2
- Кроссплатформа (iOS, Desktop) — v2
- Обход rate limits (batching, multiple accounts) — v2
- Скрытие метаданных (анонимизация From/To) — v2

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Email как транспорт | Не блокируется в условиях белого списка РФ | Принято |
| Kotlin + Jetpack Compose | Нативная разработка, надёжность, современный UI | Принято |
| X25519 + XSalsa20 (libsodium) | Проверенная криптография, простая реализация через Lazysodium | Принято |
| QR-код для обмена ключами | Максимальная безопасность, не зависит от интернета | Принято |
| IMAP polling (не push) | Без серверной инфраструктуры, полная P2P через email | Принято |
| Room + EncryptedSharedPreferences | Стандарт Android, шифрование ключей at-rest | Принято |

---
*Last updated: 2026-04-03 after initialization*
