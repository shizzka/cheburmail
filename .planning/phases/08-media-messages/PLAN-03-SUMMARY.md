# PLAN-03-SUMMARY: Image Messages

## Выполнено

Все 13 задач реализованы и сборка прошла успешно.

## Изменённые файлы

### Новые файлы
- `gradle/libs.versions.toml` — добавлена версия coil = "3.1.0" и библиотека coil-compose
- `app/build.gradle.kts` — добавлены зависимости coil-compose и exifinterface:1.4.1
- `app/src/main/res/xml/file_paths.xml` — FileProvider пути для камеры, изображений, миниатюр, голоса, файлов
- `app/src/main/java/ru/cheburmail/app/media/ImageCompressor.kt` — сжатие до 1920px (JPEG q85), миниатюры 256px (JPEG q60), EXIF-ротация
- `app/src/main/java/ru/cheburmail/app/media/MediaFileManager.kt` — сохранение/загрузка файлов в кэше, createCameraUri() через FileProvider
- `app/src/main/java/ru/cheburmail/app/ui/chat/ImageMessageBubble.kt` — bubble с Coil AsyncImage, спиннер загрузки, подпись, статус
- `app/src/main/java/ru/cheburmail/app/ui/chat/FullScreenImageViewer.kt` — полноэкранный просмотр с кнопкой закрытия

### Изменённые файлы
- `app/src/main/AndroidManifest.xml` — добавлен FileProvider
- `app/src/main/java/ru/cheburmail/app/ui/chat/MessageBubble.kt` — диспетчеризация IMAGE в ImageMessageBubble
- `app/src/main/java/ru/cheburmail/app/db/dao/MessageDao.kt` — добавлен updateMedia()
- `app/src/main/java/ru/cheburmail/app/ui/chat/ChatViewModel.kt` — ImageCompressor, cameraUri, prepareCameraUri(), onImagePicked(), mediaDecryptor в refresh()
- `app/src/main/java/ru/cheburmail/app/transport/SendWorker.kt` — детекция медиа по 4-байтовому заголовку, sendWithAttachment()
- `app/src/main/java/ru/cheburmail/app/transport/ReceiveWorker.kt` — обработка медиа-сообщений, mediaDecryptor + mediaFileManager параметры
- `app/src/main/java/ru/cheburmail/app/transport/TransportService.kt` — поле mediaMessages в ReceivedMessages, фильтрация isCheburMailMedia()
- `app/src/main/java/ru/cheburmail/app/ui/chat/ChatScreen.kt` — gallery/camera лончеры, Галерея/Камера в меню прикрепления, onImageClick → FullScreenImageViewer overlay

## Ключевые технические решения

1. **Формат медиа в очереди отправки**: 4-байтовый big-endian заголовок длины metadataEnvelope + метаданные + payload. SendWorker детектирует это по `message.mediaType != NONE`.

2. **MediaFileManager уже существовал** в Plan 04 — не дублировался, добавлены только недостающие: `ImageCompressor`, `cameraUri`, `prepareCameraUri()`, `onImagePicked()`.

3. **ReceiveWorker** расширен двумя необязательными параметрами (`mediaDecryptor`, `mediaFileManager`) — обратная совместимость сохранена.

4. **TransportService**: фильтрует `isCheburMailMedia()` в отдельный список `mediaMessages`, не пересекающийся с обычными сообщениями.

5. **FullScreenImageViewer** получил параметр `modifier` для передачи `zIndex` из ChatScreen.

## Статус сборки
BUILD SUCCESSFUL — предупреждения только в существующем коде (не от этого плана).
