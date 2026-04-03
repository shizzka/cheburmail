# Фаза 7: Фоновая синхронизация и офлайн

## Требования
- TRNS-01: Фоновое получение новых сообщений
- TRNS-02: Периодическая синхронизация
- MSG-09: Уведомления о новых сообщениях
- UI-07: Индикация состояния синхронизации

## Архитектура

### Компоненты

1. **ImapIdleService** — Foreground Service с IMAP IDLE
   - Sticky-сервис с уведомлением "CheburMail синхронизация"
   - Подключается к IMAP, входит в IDLE на папке CheburMail
   - При получении нового сообщения: fetch, parse, decrypt, сохранение в Room, показ уведомления
   - Переподключение при потере связи с экспоненциальным backoff
   - Жизненный цикл: старт при наличии аккаунта, стоп при логауте

2. **PeriodicSyncWorker** — WorkManager (15 мин)
   - Вызывает ReceiveWorker.pollAndProcess() + SendWorker.processQueue()
   - Ограничение: NetworkType.CONNECTED
   - Политика: KEEP (не дублировать)

3. **SyncManager** — оркестратор обеих стратегий
   - startImapIdle() / stopImapIdle()
   - schedulePeriodicSync() / cancelPeriodicSync()
   - Инициализация в Application.onCreate()

4. **OutboxDrainWorker** — OneTimeWork при появлении сети
   - Отправляет накопленные QUEUED сообщения
   - Использует NetworkCallback

5. **NotificationHelper** — управление уведомлениями
   - Канал "Новые сообщения"
   - Heads-up уведомления при получении
   - Persistent-уведомление для foreground service

## Зависимости
- Фаза 3: ReceiveWorker, SendWorker, ImapClient, EmailConfig
- Фаза 4: AccountRepository (проверка наличия аккаунта)
- Фаза 5: NotificationHelper (показ уведомлений)
