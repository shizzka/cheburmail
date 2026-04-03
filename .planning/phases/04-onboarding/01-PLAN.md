# Фаза 4: Онбординг и настройка аккаунтов

## Требования
- ACCT-01: Мастер настройки email-аккаунта (Yandex/Mail.ru)
- ACCT-02: Валидация IMAP/SMTP подключения
- ACCT-03: Инструкция по созданию пароля приложения
- ACCT-05: Шифрованное хранение учётных данных
- UI-04: Экран онбординга

## Архитектура

### Экраны (Jetpack Compose)
1. **OnboardingScreen** — корневой экран, управляет навигацией между шагами
2. **ProviderSelectScreen** — выбор провайдера (Yandex/Mail.ru) с логотипами
3. **CredentialsScreen** — ввод email + пароль приложения с пресетами IMAP/SMTP
4. **ConnectionTestScreen** — тест IMAP+SMTP, прогресс/успех/ошибка
5. **AppPasswordGuideScreen** — пошаговая инструкция создания пароля приложения

### Логика
- **OnboardingViewModel** — управляет состоянием мастера, вызывает SmtpClient/ImapClient
- **AccountRepository** — CRUD email-конфигураций через зашифрованный DataStore
- **AccountStorage** — DataStore-сериализатор для списка EmailConfig

### Хранение
Учётные данные шифруются через Tink AEAD (тот же механизм, что и для ключей).

## Зависимости
- Фаза 1: Tink/DataStore инфраструктура (EncryptedDataStoreFactory)
- Фаза 3: SmtpClient, ImapClient, EmailConfig, EmailProvider
