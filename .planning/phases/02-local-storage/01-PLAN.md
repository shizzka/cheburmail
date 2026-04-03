# Фаза 02 — Локальное хранилище (Room)

## Волна 1: Полная схема БД + DAO + тесты

### Цель
Создать Room-базу данных со всеми таблицами, необходимыми для мессенджера: контакты, чаты, сообщения, очередь отправки.

### Требования
- **STOR-01**: Room-база данных для истории сообщений, контактов, метаданных чатов
- **STOR-02**: Таблица очереди отправки (статусы: QUEUED/SENDING/SENT/FAILED)
- **STOR-03**: Таблица контактов с публичными ключами, отпечатками, статусом доверия

### Схема таблиц

#### contacts
| Колонка | Тип | Описание |
|---------|-----|----------|
| id | Long (PK, auto) | Внутренний ID |
| email | String (unique, indexed) | Email контакта |
| displayName | String | Отображаемое имя |
| publicKey | ByteArray (32 bytes) | Публичный ключ X25519 |
| fingerprint | String | Safety number (отпечаток) |
| trustStatus | TrustStatus | UNVERIFIED / VERIFIED / BLOCKED |
| createdAt | Long | Время создания (millis) |
| updatedAt | Long | Время обновления (millis) |

#### chats
| Колонка | Тип | Описание |
|---------|-----|----------|
| id | String (PK, UUID) | ID чата из subject CM/1/<chat-id>/<msg-uuid> |
| type | ChatType | DIRECT / GROUP |
| title | String? | null для прямых чатов, название для групп |
| createdAt | Long | Время создания |
| updatedAt | Long | Время обновления |

#### chat_members
| Колонка | Тип | Описание |
|---------|-----|----------|
| chatId | String (PK, FK→chats) | ID чата |
| contactId | Long (PK, FK→contacts) | ID контакта |
| joinedAt | Long | Время вступления |

#### messages
| Колонка | Тип | Описание |
|---------|-----|----------|
| id | String (PK, UUID) | msg-uuid из subject email |
| chatId | String (FK→chats, indexed) | ID чата |
| senderContactId | Long? | null если отправлено мной |
| isOutgoing | Boolean | Исходящее сообщение |
| plaintext | String | Расшифрованный текст |
| status | MessageStatus | SENDING/SENT/DELIVERED/FAILED/RECEIVED |
| timestamp | Long (indexed) | Время сообщения |
| expiresAt | Long? | Таймер исчезновения (null = без срока) |

#### send_queue
| Колонка | Тип | Описание |
|---------|-----|----------|
| id | Long (PK, auto) | Внутренний ID |
| messageId | String (FK→messages) | Ссылка на сообщение |
| recipientEmail | String | Email получателя |
| encryptedPayload | ByteArray | nonce + ciphertext |
| status | QueueStatus | QUEUED/SENDING/SENT/FAILED |
| retryCount | Int | Счётчик попыток (default 0) |
| nextRetryAt | Long? | Следующая попытка (экспоненциальный backoff) |
| createdAt | Long | Время создания |
| updatedAt | Long | Время обновления |

### Файлы

#### Entities (app/src/main/java/ru/cheburmail/app/db/entity/)
- `ContactEntity.kt`
- `ChatEntity.kt`
- `ChatMemberEntity.kt`
- `MessageEntity.kt`
- `SendQueueEntity.kt`

#### Вспомогательные (app/src/main/java/ru/cheburmail/app/db/)
- `Enums.kt` — TrustStatus, ChatType, MessageStatus, QueueStatus
- `Converters.kt` — TypeConverter для ByteArray↔Base64, enum↔String
- `CheburMailDatabase.kt` — @Database singleton
- `ChatWithLastMessage.kt` — data class для JOIN-запроса

#### DAOs (app/src/main/java/ru/cheburmail/app/db/dao/)
- `ContactDao.kt`
- `ChatDao.kt`
- `MessageDao.kt`
- `SendQueueDao.kt`

#### Тесты (app/src/androidTest/java/ru/cheburmail/app/db/)
- `ContactDaoTest.kt` — вставка, getByEmail, обновление, удаление
- `MessageDaoTest.kt` — вставка, порядок в чате, updateStatus, existsById (дедупликация), deleteExpired
- `SendQueueDaoTest.kt` — вставка QUEUED, getQueued, переходы статусов, getRetryable, countPending

### Критерии приёмки
1. Все entity компилируются, KSP генерирует Room-код без ошибок
2. Инструментальные тесты проходят на эмуляторе/устройстве
3. Запрос контакта по email возвращает корректные данные включая publicKey, fingerprint, trustStatus
4. Очередь отправки корректно фильтрует по статусам и nextRetryAt
5. Дедупликация сообщений работает через existsById

### Зависимости
- Добавить `room-testing` в libs.versions.toml и build.gradle.kts
