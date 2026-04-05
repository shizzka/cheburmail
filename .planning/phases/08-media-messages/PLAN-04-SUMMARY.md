# PLAN-04-SUMMARY: File + Voice Messages + Progress Indicator

## Статус
Завершён. Сборка: BUILD SUCCESSFUL.

## Что сделано

### Task 1 — RECORD_AUDIO permission
- Добавлено `<uses-permission android:name="android.permission.RECORD_AUDIO" />` в `AndroidManifest.xml`

### Task 2 — VoiceRecorder (`media/VoiceRecorder.kt`)
- Обёртка над `MediaRecorder` для записи M4A/AAC (44100 Hz, 128 kbps)
- API: `start(messageId)`, `getCurrentAmplitude()`, `stop() → RecordingResult`, `cancel()`
- Потоковый сбор амплитуд каждые 100 мс, нормализация в 50 баров (0-9)

### Task 3 — VoicePlayer (`media/VoicePlayer.kt`)
- Синглтон-обёртка над `MediaPlayer`
- API: `play(messageId, uri)`, `pause()`, `togglePlayPause()`, `stop()`
- `StateFlow<PlaybackState>` (Idle / Playing / Paused) с полями messageId, progressMs, durationMs
- Поток прогресса обновляется каждые 200 мс

### Task 4 — FileSaver (`media/FileSaver.kt`)
- `saveToDownloads(fileName, mimeType, bytes/sourceUri) → Uri?`
- API 29+: MediaStore Downloads API
- API 26-28: прямая запись в `Environment.DIRECTORY_DOWNLOADS/CheburMail/`

### Task 5 — WaveformView (`ui/chat/WaveformView.kt`)
- Canvas-based bar chart из comma-separated амплитуд
- Параметр `progress (0..1)` выделяет проигранные бары цветом
- Закруглённые rect-бары, автоматический расчёт ширины

### Task 6 — VoiceMessageBubble (`ui/chat/VoiceMessageBubble.kt`)
- Кнопка Play/Pause, WaveformView, длительность (мм:сс)
- Подписывается на `VoicePlayer.state` для отображения прогресса
- Строка времени + статус отправки

### Task 7 — FileMessageBubble (`ui/chat/FileMessageBubble.kt`)
- Иконка файла, имя, размер (форматирование: Б/КБ/МБ/ГБ)
- Кнопка "Скачать" для входящих сообщений
- `formatFileSize()` — публичный helper

### Task 8 — SendProgressIndicator (`ui/chat/SendProgressIndicator.kt`)
- `AnimatedVisibility` + `LinearProgressIndicator` + текстовая метка
- Анимация fadeIn/fadeOut

### Task 9 — MessageBubble (`ui/chat/MessageBubble.kt`)
- Добавлены параметры: `onSaveFile: (String) -> Unit`, `voicePlayer: VoicePlayer?`
- Диспетчер: FILE → FileMessageBubble, VOICE → VoiceMessageBubble
- IMAGE диспетчер от Plan 03 сохранён (делегирует в ImageMessageBubble)
- NONE → текстовое сообщение

### Task 10 — ChatViewModel (`ui/chat/ChatViewModel.kt`)
- Свойства: `voiceRecorder`, `voicePlayer`, `fileSaver`, `mediaFileManager`
- StateFlow: `isRecordingVoice`, `isSendingMedia`, `sendingMediaLabel`
- Методы: `onFilePicked(uri)`, `startVoiceRecording()`, `stopVoiceRecordingAndSend()`, `cancelVoiceRecording()`, `saveFileToDownloads(messageId)`
- Приватные: `ensureChatExists()`, `encryptAndQueueMedia()` (suspend)
- `onCleared()` — освобождает recorder и player

### Task 11 — ChatScreen (`ui/chat/ChatScreen.kt`)
- File picker launcher (OpenDocument, `*/*`)
- RECORD_AUDIO permission launcher с запросом перед началом записи
- Collect: `isRecordingVoice`, `isSendingMedia`, `sendingMediaLabel`
- `SendProgressIndicator` над полем ввода
- Attach-меню: "Файл" (+ Gallery/Camera от Plan 03)
- Условный Send/Mic/Stop button: text.isNotBlank() → Send, !recording → Mic, recording → Stop
- `DisposableEffect` для остановки voicePlayer при уходе с экрана
- Интеграция с изменениями Plan 03 (gallery, camera, full-screen viewer)

## Файлы
- `app/src/main/AndroidManifest.xml` — RECORD_AUDIO permission
- `app/src/main/java/ru/cheburmail/app/media/VoiceRecorder.kt` — новый
- `app/src/main/java/ru/cheburmail/app/media/VoicePlayer.kt` — новый
- `app/src/main/java/ru/cheburmail/app/media/FileSaver.kt` — новый
- `app/src/main/java/ru/cheburmail/app/ui/chat/WaveformView.kt` — новый
- `app/src/main/java/ru/cheburmail/app/ui/chat/VoiceMessageBubble.kt` — новый
- `app/src/main/java/ru/cheburmail/app/ui/chat/FileMessageBubble.kt` — новый
- `app/src/main/java/ru/cheburmail/app/ui/chat/SendProgressIndicator.kt` — новый
- `app/src/main/java/ru/cheburmail/app/ui/chat/MessageBubble.kt` — обновлён
- `app/src/main/java/ru/cheburmail/app/ui/chat/ChatViewModel.kt` — обновлён
- `app/src/main/java/ru/cheburmail/app/ui/chat/ChatScreen.kt` — обновлён

## Зависимости с Plan 03
- Plan 03 добавил `ImageCompressor`, `MediaFileManager`, `cameraUri`, `onImagePicked` в ChatViewModel — сохранено
- Plan 03 добавил `onImageClick`, `FullScreenImageViewer`, gallery/camera launchers в ChatScreen — сохранено
- Plan 03 изменил `MessageBubble` с IMAGE диспетчером — интегрировано (FILE/VOICE добавлены рядом)

## Результат сборки
```
BUILD SUCCESSFUL in 54s
37 actionable tasks: 5 executed, 32 up-to-date
```
