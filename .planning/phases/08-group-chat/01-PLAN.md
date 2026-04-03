# Фаза 8: Групповые чаты

## Требования
- MSG-06: Создание группы до 10 участников
- MSG-07: Fan-out шифрование (каждому участнику своя копия)
- MSG-08: Управляющие сообщения (приглашение, добавление, удаление)

## Архитектура

### Компоненты

1. **GroupManager** — CRUD операции с группами
   - createGroup(name, memberContactIds): ChatEntity(type=GROUP) + ChatMemberEntity
   - sendGroupInvite(chatId): управляющее сообщение с публичными ключами
   - addMember / removeMember: обновление состава + уведомление

2. **GroupMessageSender** — fan-out отправка
   - sendToGroup(chatId, plaintext): шифрование для каждого участника отдельно
   - Создание SendQueueEntity для каждого получателя
   - Контроль глубины очереди (предупреждение при > 50)

3. **ControlMessage** — типизированные управляющие сообщения
   - Типы: GROUP_INVITE, MEMBER_ADDED, MEMBER_REMOVED
   - Формат subject: CM/1/<chatId>/ctrl-<uuid>
   - JSON-payload с информацией о группе и участниках

4. **ControlMessageHandler** — обработка входящих управляющих сообщений
   - Создание/обновление чата при получении GROUP_INVITE
   - Добавление/удаление участников

5. **UI: CreateGroupScreen** — выбор контактов и имя группы
6. **UI: GroupInfoScreen** — просмотр и управление участниками

## Зависимости
- Фаза 2: ChatEntity, ChatMemberEntity, ChatDao, ContactDao, SendQueueDao
- Фаза 3: EmailFormatter, SendWorker, ReceiveWorker
- Фаза 5: ContactsViewModel (список контактов для выбора)
