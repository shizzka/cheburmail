# Фаза 5: Обмен ключами и контакты

## Требования
- CRYPT-03: QR-код обмен публичными ключами
- CRYPT-04: Управление контактами (CRUD)
- CRYPT-07: Safety numbers (отпечатки ключей)
- UI-03: Экраны контактов

## Архитектура

### Экраны (Jetpack Compose)
1. **ContactListScreen** — список контактов с именем, email, статусом доверия
2. **ContactDetailScreen** — детали контакта, safety number, удаление
3. **AddContactScreen** — сканер QR-кода (CameraX + ML Kit)
4. **QrCodeScreen** — отображение собственного QR-кода

### Криптография
- **FingerprintGenerator** — генерация safety number из двух публичных ключей (SHA-256 хэш отсортированных ключей)
- **QrCodeGenerator** — генерация QR-bitmap из JSON: `{"pk":"<base64>","email":"...","v":1}`
- **QrCodeParser** — парсинг QR-контента обратно в публичный ключ + email

### QR-формат
```json
{"pk":"<base64_pubkey>","email":"user@yandex.ru","v":1}
```

## Зависимости
- Фаза 1: KeyPair, CryptoConstants
- Фаза 2: ContactDao, ContactEntity, TrustStatus
- Фаза 4: SecureKeyStorage (для получения собственного публичного ключа)
