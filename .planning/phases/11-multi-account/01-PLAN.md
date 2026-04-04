# Фаза 10: Мульти-аккаунт и оптимизация транспорта

## Требования
- ACCT-04: Несколько email-аккаунтов
- TRNS-04: Ротация аккаунтов при отправке
- TRNS-06: Автоочистка обработанных email
- CRYPT-08: Экспорт ключей (зашифрованный бэкап)
- CRYPT-09: Импорт ключей (восстановление из бэкапа)

## Архитектура

### Мульти-аккаунт
1. **MultiAccountManager** — управление списком email-аккаунтов
   - Round-robin выбор аккаунта с учётом rate limit
   - Интеграция с AccountRepository (DataStore)
2. **RateLimitTracker** — отслеживание количества отправок per-account
   - In-memory счётчик, сброс ежечасно
   - Лимит по умолчанию: 400 писем/день (консервативный)

### Оптимизация транспорта
3. **SendWorker** — обновлён для использования MultiAccountManager.getNextSendAccount()
4. **EmailCleanupWorker** — WorkManager periodic (ежедневно)
   - Удаляет email старше 7 дней из папки CheburMail на IMAP

### Бэкап ключей
5. **KeyBackupManager** — экспорт/импорт зашифрованного бэкапа приватного ключа
   - Формат: version(1) + salt(16) + nonce(24) + encrypted_key
   - Шифрование: password -> PBKDF2 -> crypto_secretbox
6. **KeyBackupScreen** — UI экспорта/импорта (ACTION_CREATE_DOCUMENT / ACTION_OPEN_DOCUMENT)
7. **AccountManagementScreen** — список аккаунтов с статистикой использования

## Зависимости
- Фаза 2: AccountRepository, SecureKeyStorage
- Фаза 3: SendWorker, ImapClient, EmailConfig
- Фаза 9: SettingsScreen (навигация)

## Тесты
- RateLimitTrackerTest — increment, canSend, reset
- KeyBackupManagerTest — export/import round-trip, неверный пароль
- EmailCleanupWorkerTest — удаляет старые, сохраняет свежие
