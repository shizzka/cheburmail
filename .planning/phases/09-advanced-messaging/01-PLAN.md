# Фаза 9: Расширенные функции сообщений

## Требования
- MSG-10: Подтверждение доставки (delivery receipts)
- MSG-11: Исчезающие сообщения (disappearing messages)
- UI-05: Экран настроек приложения

## Архитектура

### Delivery Receipts (подтверждение доставки)
1. **DeliveryReceiptSender** — при получении сообщения отправляет ACK обратно отправителю
   - Формат subject: `CM/1/<chatId>/ack-<originalMsgUuid>`
   - Тело: зашифрованный JSON `{"type":"ack","msgId":"<uuid>","timestamp":<millis>}`
2. **DeliveryReceiptHandler** — обрабатывает входящие ACK
   - Детектирует ACK по префиксу `ack-` в subject
   - Обновляет статус MessageEntity -> DELIVERED

### Исчезающие сообщения
- **DisappearingMessageManager** — управляет таймерами автоудаления
  - Per-chat таймер в ChatEntity (поле `disappear_timer_ms`)
  - Устанавливает `expiresAt` на новые сообщения
  - Периодическая очистка через WorkManager (1 час)

### Настройки
- **SettingsScreen** — Jetpack Compose экран:
  - Управление аккаунтами
  - Уведомления
  - Таймер исчезающих сообщений по умолчанию
  - О приложении (версия, криптография)
- **SettingsViewModel** — управление состоянием настроек

### Интеграция
- ReceiveWorker обновлён: после сохранения сообщения запускает DeliveryReceiptSender
- ReceiveWorker обновлён: детектирует ACK и передаёт в DeliveryReceiptHandler

## Зависимости
- Фаза 2: MessageEntity, ChatEntity, MessageDao, ChatDao
- Фаза 3: SmtpClient, EmailFormatter, EmailParser, ReceiveWorker
- Фаза 6: Навигация (AppNavigation)

## Тесты
- DeliveryReceiptTest — формат ACK, отправка/получение round-trip
- DisappearingMessageTest — установка таймера, очистка просроченных
