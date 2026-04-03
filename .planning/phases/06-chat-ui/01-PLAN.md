# Фаза 6: Основной UI чатов

## Требования
- UI-01: Список чатов с превью последнего сообщения
- UI-02: Экран переписки с историей сообщений
- UI-06: Статусы сообщений (отправка/отправлено/доставлено/ошибка)

## Архитектура

### Экраны (Jetpack Compose)
1. **ChatListScreen** — LazyColumn чатов с превью, временем, счётчиком непрочитанных
2. **ChatScreen** — история сообщений (reversed LazyColumn), поле ввода, кнопка отправки
3. **NewChatScreen** — выбор контакта для начала нового чата
4. **MessageBubble** — composable одного сообщения (входящее/исходящее)
5. **MessageStatusIcon** — иконка статуса: часы/галочка/двойная/крестик

### ViewModel
- **ChatListViewModel** — наблюдает ChatDao.getAllWithLastMessage()
- **ChatViewModel** — наблюдает сообщения чата, отправляет через MessageRepository

### Навигация
- **AppNavigation** — NavHost с маршрутами: onboarding, chatList, chat/{id}, contacts, qrCode, settings
- **MainActivity** обновлена для использования AppNavigation

## Зависимости
- Фаза 2: ChatDao, MessageDao, ChatEntity, MessageEntity, ChatWithLastMessage
- Фаза 4: AccountRepository (проверка наличия аккаунта для навигации)
- Фаза 5: ContactsViewModel, экраны контактов
