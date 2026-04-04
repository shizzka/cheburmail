# Phase 8: Media Messages — Research Document

**Дата:** 2026-04-04  
**Стек:** Kotlin 2.3.20, Jetpack Compose, lazysodium-android 5.2.0, JavaMail 1.6.2, Room 2.8.4, minSdk 26

---

## 1. JavaMail MIME Multipart с зашифрованными вложениями

### Текущее состояние

`SmtpClient.kt` уже использует `MimeMultipart` — одна `MimeBodyPart` с `ByteArrayDataSource` и `application/x-cheburmail`. Код структурно готов к расширению.

`ImapClient.extractBody()` обрабатывает `Multipart`, но берёт только `getBodyPart(0)` — нужно будет перебирать все части.

### Рекомендуемая MIME-структура для медиа

```
Content-Type: multipart/mixed; boundary="..."

-- Part 0 (обязательный текстовый конверт) --
Content-Type: application/x-cheburmail
Content-Transfer-Encoding: base64

<Base64(nonce || encrypted_json_metadata)>

-- Part 1 (зашифрованное вложение) --
Content-Type: application/octet-stream
Content-Disposition: attachment; filename="enc_<uuid>.bin"
Content-Transfer-Encoding: base64

<Base64(nonce || encrypted_file_bytes)>
```

**Ключевые решения:**
- Part 0 содержит зашифрованный JSON с метаданными: `{type: "image", mimeType: "image/jpeg", fileName: "photo.jpg", fileSize: 1234567, hasAttachment: true}`. Это позволяет декодировать имя файла и тип до скачивания вложения.
- Part 1 содержит зашифрованные байты файла. `filename` в `Content-Disposition` не несёт информации — это просто технический идентификатор.
- `Content-Transfer-Encoding: base64` обязателен для бинарных данных в JavaMail.

### Код отправки (SmtpClient расширение)

```kotlin
fun sendWithAttachment(config: EmailConfig, message: EmailMessage, attachment: EncryptedAttachment) {
    val mimeMessage = MimeMessage(session).apply {
        setFrom(InternetAddress(message.from))
        setRecipient(Message.RecipientType.TO, InternetAddress(message.to))
        subject = message.subject

        val multipart = MimeMultipart("mixed")

        // Part 0: метаданные
        val metaPart = MimeBodyPart().apply {
            dataHandler = DataHandler(ByteArrayDataSource(message.body, message.contentType))
        }
        multipart.addBodyPart(metaPart)

        // Part 1: вложение
        val filePart = MimeBodyPart().apply {
            dataHandler = DataHandler(ByteArrayDataSource(attachment.encryptedBytes, "application/octet-stream"))
            fileName = "enc_${attachment.uuid}.bin"
            disposition = Part.ATTACHMENT
        }
        multipart.addBodyPart(filePart)

        setContent(multipart)
    }
    Transport.send(mimeMessage)
}
```

### Обработка больших файлов без OOM — Base64 через поток

Стандартный `ByteArrayDataSource` загружает весь файл в память. Для файлов > 5 МБ нужен потоковый подход:

```kotlin
// Вместо ByteArrayDataSource — InputStream-based DataSource
class FileDataSource(private val file: File, private val mimeType: String) : DataSource {
    override fun getInputStream(): InputStream = file.inputStream().buffered(64 * 1024)
    override fun getOutputStream(): OutputStream = throw UnsupportedOperationException()
    override fun getContentType(): String = mimeType
    override fun getName(): String = file.name
}
```

Зашифрованный файл пишется во временный файл в `cacheDir`, затем `FileDataSource` передаётся в `MimeBodyPart`. JavaMail сам обработает Base64-кодирование через `Content-Transfer-Encoding: base64` потоково при вызове `Transport.send()`.

**Важно:** Временный файл нужно удалять после успешной отправки в `finally`-блоке.

---

## 2. Лимиты размера email

### Yandex Mail
- Максимальный размер письма (включая вложения): **25 МБ** для входящих, **25 МБ** для исходящих через SMTP.
- Фактически из-за Base64-overhead (+33%) реальный лимит бинарных данных — ~**18.5 МБ**.

### Mail.ru
- Максимальный размер письма: **25 МБ** суммарно.
- Аналогичный Base64 overhead — реальный лимит ~18.5 МБ.

### Практические рекомендации

| Тип контента | Рекомендация |
|---|---|
| Изображения | Сжимать до 1920px максимум, quality=85 → обычно 300–800 КБ |
| Файлы | Предупреждать пользователя при > 15 МБ, блокировать при > 18 МБ |
| Голосовые | OGG/Opus ~1 МБ/мин → при 5 мин = ~5 МБ (комфортно) |

**Безопасный лимит для реализации:** 18 МБ на бинарные данные (с учётом Base64 вписывается в 25 МБ почтового лимита).

### Сжатие изображений перед шифрованием

```kotlin
fun compressImage(uri: Uri, context: Context): ByteArray {
    val bitmap = if (Build.VERSION.SDK_INT >= 28) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val maxDim = 1920
            val (w, h) = info.size.width to info.size.height
            if (w > maxDim || h > maxDim) {
                val scale = maxDim.toFloat() / maxOf(w, h)
                decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
            }
        }
    } else {
        @Suppress("DEPRECATION")
        BitmapFactory.decodeStream(context.contentResolver.openInputStream(uri))
    }
    val bos = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, bos)
    bitmap.recycle()
    return bos.toByteArray()
}
```

---

## 3. Шифрование медиафайлов — crypto_box для больших данных

### Проблема

`crypto_box_easy` (используемый в `MessageEncryptor`) загружает весь plaintext в память одновременно: `ByteArray(message.size + MAC_BYTES)`. Для 18 МБ файла это создаёт три буфера в памяти одновременно (входной, выходной + overhead JavaMail) — итого ~60 МБ. На Android с 256 МБ heap это рискованно.

### Решение: шифрование одним вызовом crypto_box с записью в файл

Для Phase 8 (лимит 18 МБ) допустимо шифровать всё сразу, но результат сразу писать в файл:

```kotlin
fun encryptToFile(
    plaintext: ByteArray,          // уже в памяти после чтения из ContentResolver
    recipientPublicKey: ByteArray,
    senderPrivateKey: ByteArray,
    outFile: File
) {
    val nonce = nonceGenerator.generate()
    val ciphertext = ByteArray(plaintext.size + CryptoConstants.MAC_BYTES)
    val ok = box.cryptoBoxEasy(ciphertext, plaintext, plaintext.size.toLong(), nonce, recipientPublicKey, senderPrivateKey)
    if (!ok) throw CryptoException("crypto_box_easy failed")
    // Записываем nonce || ciphertext в файл
    outFile.outputStream().use { out ->
        out.write(nonce)
        out.write(ciphertext)
    }
    // plaintext.fill(0)  // очистить из памяти после шифрования
}
```

### Альтернатива: чанковое шифрование (для будущего)

libsodium поддерживает `crypto_secretstream_xchacha20poly1305` — потоковое шифрование с аутентификацией. lazysodium-android 5.2.0 включает `SecretStream` интерфейс. Это оптимально для файлов > 50 МБ, но для Phase 8 с лимитом 18 МБ избыточно.

### Расширение MessageEncryptor

Добавить `MediaEncryptor` как отдельный класс, не трогая существующий `MessageEncryptor`:

```kotlin
class MediaEncryptor(private val box: Box.Native, private val nonceGenerator: NonceGenerator) {
    
    fun encryptBytes(
        plaintext: ByteArray,
        recipientPublicKey: ByteArray,
        senderPrivateKey: ByteArray
    ): ByteArray {
        val nonce = nonceGenerator.generate()
        val ciphertext = ByteArray(plaintext.size + CryptoConstants.MAC_BYTES)
        val ok = box.cryptoBoxEasy(ciphertext, plaintext, plaintext.size.toLong(), nonce, recipientPublicKey, senderPrivateKey)
        if (!ok) throw CryptoException("crypto_box_easy failed for media")
        return nonce + ciphertext
    }
    
    fun decryptBytes(
        encryptedData: ByteArray,  // nonce || ciphertext
        senderPublicKey: ByteArray,
        recipientPrivateKey: ByteArray
    ): ByteArray {
        val nonce = encryptedData.copyOfRange(0, CryptoConstants.NONCE_BYTES)
        val ciphertext = encryptedData.copyOfRange(CryptoConstants.NONCE_BYTES, encryptedData.size)
        val plaintext = ByteArray(ciphertext.size - CryptoConstants.MAC_BYTES)
        val ok = box.cryptoBoxOpenEasy(plaintext, ciphertext, ciphertext.size.toLong(), nonce, senderPublicKey, recipientPrivateKey)
        if (!ok) throw CryptoException("crypto_box_open_easy failed: authentication error")
        return plaintext
    }
}
```

### Управление памятью

- Читать файл через `ContentResolver.openInputStream()` → `readBytes()` только непосредственно перед шифрованием
- После записи в temp-файл — `plaintext.fill(0)` для очистки
- Использовать `Dispatchers.IO` для всех файловых операций
- Temp-файлы хранить в `context.cacheDir` (автоматически чистятся при нехватке места)

---

## 4. Работа с изображениями на Android

### Выбор из галереи и съёмка камерой (ActivityResultContracts)

```kotlin
// В ChatViewModel или ChatScreen

// Галерея
val pickImageLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickVisualMedia()
) { uri: Uri? ->
    uri?.let { viewModel.onImagePicked(it) }
}

// Камера — требует создания Uri заранее
val cameraImageUri = remember { mutableStateOf<Uri?>(null) }
val takePictureLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.TakePicture()
) { success: Boolean ->
    if (success) cameraImageUri.value?.let { viewModel.onImagePicked(it) }
}

// Для создания Uri камеры:
fun createTempImageUri(context: Context): Uri {
    val file = File.createTempFile("cam_", ".jpg", context.cacheDir)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
```

**Требует в AndroidManifest:**
```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_provider_paths" />
</provider>
```

`res/xml/file_provider_paths.xml`:
```xml
<paths>
    <cache-path name="cache" path="." />
    <external-cache-path name="external_cache" path="." />
</paths>
```

**`ActivityResultContracts.PickVisualMedia()`** — рекомендованный современный API (Android Photo Picker), работает на minSdk 26 через backport от Google Play Services. Не требует разрешений READ_EXTERNAL_STORAGE.

### Загрузка и отображение изображений — Coil

**Рекомендация: добавить Coil 3.x**

Coil — де-факто стандарт для Compose. Coil 3.x (последняя стабильная на момент написания — 3.1.0) нативно поддерживает Compose Multiplatform.

```toml
# libs.versions.toml
coil = "3.1.0"

[libraries]
coil-compose = { group = "io.coil-kt.coil3", name = "coil-compose", version.ref = "coil" }
coil-okhttp = { group = "io.coil-kt.coil3", name = "coil-network-okhttp", version.ref = "coil" }
```

```kotlin
// build.gradle.kts
implementation(libs.coil.compose)
```

**Отображение локального файла:**
```kotlin
AsyncImage(
    model = ImageRequest.Builder(LocalContext.current)
        .data(localFilePath)  // File или Uri
        .crossfade(true)
        .build(),
    contentDescription = "Image",
    contentScale = ContentScale.Crop,
    modifier = Modifier.size(200.dp)
)
```

**Генерация миниатюры** для пузырька чата:

```kotlin
fun generateThumbnail(imageBytes: ByteArray, maxSize: Int = 256): ByteArray {
    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    val scale = maxSize.toFloat() / maxOf(bitmap.width, bitmap.height)
    val thumb = if (scale < 1f) {
        Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
    } else bitmap
    val bos = ByteArrayOutputStream()
    thumb.compress(Bitmap.CompressFormat.JPEG, 70, bos)
    if (thumb != bitmap) thumb.recycle()
    bitmap.recycle()
    return bos.toByteArray()
}
```

Миниатюры хранить в `context.filesDir/thumbnails/<msgId>.jpg` — постоянное хранилище, не очищается системой.

### Полноэкранный просмотр

```kotlin
@Composable
fun FullScreenImageViewer(imageFile: File, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { onDismiss() }
    ) {
        AsyncImage(
            model = imageFile,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
    }
}
```

---

## 5. Голосовые сообщения

### MediaRecorder vs AudioRecord

| | MediaRecorder | AudioRecord |
|---|---|---|
| Простота | Высокая | Низкая |
| OGG/Opus нативно | Нет | Нет |
| AAC нативно | Да (API 16+) | Нет |
| M4A/AAC | Да | Нет |
| Контроль над PCM | Нет | Да |
| Waveform в реальном времени | Сложно | Легко |

### Рекомендация: AAC/M4A через MediaRecorder

OGG/Opus на Android не поддерживается нативно через MediaRecorder. Варианты:

1. **AAC в M4A контейнере** (OutputFormat.MPEG_4 + AudioEncoder.AAC) — нативная поддержка, качественный кодек, маленький размер, воспроизводится на любом Android. **Рекомендуется для Phase 8.**

2. **OGG через нативную библиотеку** — например, `opus-android` или `libvorbis`. Требует NDK или готового AAR. Избыточная сложность.

3. **OPUS через WebRTC** — нативно в Android 10+ (`MediaFormat.MIMETYPE_AUDIO_OPUS`). Для minSdk 26 можно использовать через `MediaCodec` напрямую, но это сложно.

**Итог: использовать AAC/M4A, переименовывать как `.m4a`.**

### Запись голоса с MediaRecorder

```kotlin
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    
    fun startRecording(): File {
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        outputFile = file
        
        recorder = if (Build.VERSION.SDK_INT >= 31) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        
        recorder!!.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(64_000)   // 64 kbps — хорошее качество речи
            setAudioSamplingRate(16_000)       // 16 kHz достаточно для речи
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        return file
    }
    
    fun stopRecording(): File? {
        try {
            recorder?.apply { stop(); release() }
        } catch (e: RuntimeException) {
            // Запись была слишком короткой
            outputFile?.delete()
            return null
        } finally {
            recorder = null
        }
        return outputFile
    }
    
    // Амплитуда для waveform (вызывать каждые 100 мс)
    fun getMaxAmplitude(): Int = recorder?.maxAmplitude ?: 0
    
    fun cancel() {
        try { recorder?.apply { stop(); release() } } catch (_: Exception) {}
        recorder = null
        outputFile?.delete()
        outputFile = null
    }
}
```

**Разрешение:** `<uses-permission android:name="android.permission.RECORD_AUDIO" />`

Запрашивать через `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())`.

### Waveform визуализация

Подходов два:

**A. Запись амплитуд во время записи (для отображения при отправке):**
```kotlin
// Собираем амплитуды каждые 100 мс
val waveformSamples = mutableListOf<Float>()
val job = scope.launch {
    while (isActive) {
        val amp = recorder.getMaxAmplitude().toFloat() / 32767f
        waveformSamples.add(amp)
        delay(100)
    }
}
```

**B. Custom Compose Canvas для waveform:**
```kotlin
@Composable
fun WaveformView(
    samples: List<Float>,  // 0..1
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Canvas(modifier = modifier) {
        if (samples.isEmpty()) return@Canvas
        val barWidth = size.width / samples.size
        val centerY = size.height / 2f
        samples.forEachIndexed { i, amp ->
            val barHeight = amp * size.height * 0.9f
            drawLine(
                color = color,
                start = Offset(i * barWidth + barWidth / 2, centerY - barHeight / 2),
                end = Offset(i * barWidth + barWidth / 2, centerY + barHeight / 2),
                strokeWidth = (barWidth * 0.7f).coerceAtLeast(2f)
            )
        }
    }
}
```

### Воспроизведение через MediaPlayer

```kotlin
class VoicePlayer {
    private var player: MediaPlayer? = null
    
    fun play(file: File, onCompletion: () -> Unit) {
        stop()
        player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener { onCompletion() }
            prepare()
            start()
        }
    }
    
    fun pause() { player?.pause() }
    fun resume() { player?.start() }
    fun stop() { player?.apply { stop(); release() }; player = null }
    
    val currentPosition: Int get() = player?.currentPosition ?: 0
    val duration: Int get() = player?.duration ?: 0
    val isPlaying: Boolean get() = player?.isPlaying ?: false
}
```

---

## 6. Работа с файлами (SAF)

### Выбор произвольного файла

```kotlin
val pickFileLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
) { uri: Uri? ->
    uri?.let { viewModel.onFilePicked(it) }
}

// Вызов:
pickFileLauncher.launch(arrayOf("*/*"))  // любой тип
```

### Чтение байт файла через ContentResolver

```kotlin
suspend fun readFileBytes(context: Context, uri: Uri): Pair<ByteArray, FileMetadata> = withContext(Dispatchers.IO) {
    val contentResolver = context.contentResolver
    
    // Метаданные
    val cursor = contentResolver.query(uri, null, null, null, null)
    val (fileName, fileSize) = cursor?.use {
        it.moveToFirst()
        val nameCol = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeCol = it.getColumnIndex(OpenableColumns.SIZE)
        val name = it.getString(nameCol) ?: "file"
        val size = if (sizeCol != -1) it.getLong(sizeCol) else -1L
        name to size
    } ?: ("file" to -1L)
    
    val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
    
    // Чтение байт
    val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    
    bytes to FileMetadata(fileName, fileSize.takeIf { it >= 0 } ?: bytes.size.toLong(), mimeType)
}

data class FileMetadata(val fileName: String, val fileSize: Long, val mimeType: String)
```

### Сохранение полученного файла в Downloads

```kotlin
suspend fun saveToDownloads(context: Context, bytes: ByteArray, fileName: String, mimeType: String): Uri? = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // API 29+: MediaStore без разрешений
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        uri
    } else {
        // API 26-28: прямая запись в Downloads
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(dir, fileName)
        file.writeBytes(bytes)
        Uri.fromFile(file)
    }
}
```

Для API 26-28 нужно разрешение `WRITE_EXTERNAL_STORAGE`.

---

## 7. Изменения схемы Room

### Текущее состояние

`MessageEntity` (version 1):
- `id`, `chat_id`, `sender_contact_id`, `is_outgoing`, `plaintext`, `status`, `timestamp`, `expires_at`

### Новые столбцы для Phase 8

```kotlin
@Entity(tableName = "messages", ...)
data class MessageEntity(
    // ... существующие поля ...
    
    @ColumnInfo(name = "media_type")
    val mediaType: MediaType = MediaType.TEXT,      // TEXT, IMAGE, FILE, VOICE
    
    @ColumnInfo(name = "local_media_uri")
    val localMediaUri: String? = null,              // file:// path к сохранённому файлу
    
    @ColumnInfo(name = "file_name")
    val fileName: String? = null,                   // оригинальное имя файла
    
    @ColumnInfo(name = "file_size")
    val fileSize: Long? = null,                     // байт
    
    @ColumnInfo(name = "mime_type")
    val mimeType: String? = null,                   // "image/jpeg", "audio/mp4", etc.
    
    @ColumnInfo(name = "thumbnail_uri")
    val thumbnailUri: String? = null,               // path к миниатюре (только для image)
    
    @ColumnInfo(name = "voice_duration_ms")
    val voiceDurationMs: Long? = null,              // длительность голосового в мс
    
    @ColumnInfo(name = "waveform_data")
    val waveformData: String? = null,               // JSON-массив Float (амплитуды)
    
    @ColumnInfo(name = "media_download_status")
    val mediaDownloadStatus: MediaDownloadStatus = MediaDownloadStatus.NONE
)

enum class MediaType { TEXT, IMAGE, FILE, VOICE }
enum class MediaDownloadStatus { NONE, PENDING, DOWNLOADING, READY, FAILED }
```

### Миграция Room

```kotlin
// CheburMailDatabase.kt
@Database(
    entities = [...],
    version = 2,    // было 1
    exportSchema = true
)
abstract class CheburMailDatabase : RoomDatabase() {
    // ...
    
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN media_type TEXT NOT NULL DEFAULT 'TEXT'")
                db.execSQL("ALTER TABLE messages ADD COLUMN local_media_uri TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN file_name TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN file_size INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN mime_type TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN thumbnail_uri TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN voice_duration_ms INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN waveform_data TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN media_download_status TEXT NOT NULL DEFAULT 'NONE'")
            }
        }
        
        fun getInstance(context: Context): CheburMailDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(context.applicationContext, CheburMailDatabase::class.java, DB_NAME)
                    .addMigrations(MIGRATION_1_2)
                    .build().also { INSTANCE = it }
            }
    }
}
```

### SendQueueEntity расширение

Для медиа нужно хранить путь к temp-файлу с зашифрованным вложением:

```kotlin
// Добавить в SendQueueEntity:
@ColumnInfo(name = "attachment_file_path")
val attachmentFilePath: String? = null,     // путь к зашифрованному temp-файлу

@ColumnInfo(name = "attachment_metadata_json")
val attachmentMetadataJson: String? = null  // JSON с metadata для части 0
```

---

## 8. UI-компоненты Compose

### Пузырёк с изображением

```kotlin
@Composable
fun ImageMessageBubble(
    message: MessageUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val alignment = if (message.isOutgoing) Alignment.CenterEnd else Alignment.CenterStart
    
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = alignment) {
        Card(
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 240.dp)
        ) {
            Column {
                AsyncImage(
                    model = message.thumbnailUri ?: message.localMediaUri,
                    contentDescription = "Image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                        .clickable { onClick() }
                )
                Text(
                    text = message.timeFormatted,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(4.dp).align(Alignment.End)
                )
            }
        }
    }
}
```

### Пузырёк файла

```kotlin
@Composable
fun FileMessageBubble(
    message: MessageUiModel,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isOutgoing = message.isOutgoing
    val alignment = if (isOutgoing) Alignment.CenterEnd else Alignment.CenterStart
    
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = alignment) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isOutgoing) 
                    MaterialTheme.colorScheme.primaryContainer 
                else 
                    MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.AttachFile, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = message.fileName ?: "Файл",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = formatFileSize(message.fileSize ?: 0),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                when (message.mediaDownloadStatus) {
                    MediaDownloadStatus.PENDING, MediaDownloadStatus.DOWNLOADING ->
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    MediaDownloadStatus.READY ->
                        IconButton(onClick = onSave) {
                            Icon(Icons.Default.Download, contentDescription = "Сохранить")
                        }
                    MediaDownloadStatus.FAILED ->
                        Icon(Icons.Default.Error, contentDescription = "Ошибка", tint = MaterialTheme.colorScheme.error)
                    else -> {}
                }
            }
        }
    }
}
```

### Пузырёк голосового сообщения

```kotlin
@Composable
fun VoiceMessageBubble(
    message: MessageUiModel,
    isPlaying: Boolean,
    playProgress: Float,   // 0..1
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier
) {
    val alignment = if (message.isOutgoing) Alignment.CenterEnd else Alignment.CenterStart
    val waveformSamples = remember(message.waveformData) {
        message.waveformData?.let { parseWaveform(it) } ?: emptyList()
    }
    
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = alignment) {
        Card(shape = RoundedCornerShape(20.dp)) {
            Row(
                modifier = Modifier.padding(8.dp).width(220.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPlayPause) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Пауза" else "Играть"
                    )
                }
                
                Box(modifier = Modifier.weight(1f).height(36.dp)) {
                    WaveformView(
                        samples = waveformSamples,
                        progressFraction = if (isPlaying) playProgress else 0f,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                
                Text(
                    text = formatDuration(message.voiceDurationMs ?: 0),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}
```

### Индикатор загрузки/отправки

```kotlin
@Composable
fun MediaUploadProgress(progress: Float) {
    if (progress > 0f && progress < 1f) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(2.dp)
        )
    }
}
```

### Расширение MessageInput (кнопка прикрепления)

```kotlin
@Composable
private fun MessageInput(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttachImage: () -> Unit,
    onAttachFile: () -> Unit,
    onVoiceRecord: () -> Unit
) {
    var showAttachMenu by remember { mutableStateOf(false) }
    
    Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
        
        Box {
            IconButton(onClick = { showAttachMenu = true }) {
                Icon(Icons.Default.AttachFile, contentDescription = "Прикрепить")
            }
            DropdownMenu(expanded = showAttachMenu, onDismissRequest = { showAttachMenu = false }) {
                DropdownMenuItem(text = { Text("Фото") }, leadingIcon = { Icon(Icons.Default.Image, null) }, onClick = { showAttachMenu = false; onAttachImage() })
                DropdownMenuItem(text = { Text("Файл") }, leadingIcon = { Icon(Icons.Default.Description, null) }, onClick = { showAttachMenu = false; onAttachFile() })
            }
        }
        
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Сообщение...") },
            maxLines = 4,
            shape = MaterialTheme.shapes.large
        )
        
        Spacer(Modifier.width(4.dp))
        
        // Голосовая кнопка: если текст пустой — микрофон, иначе отправить
        IconButton(
            onClick = if (text.isBlank()) onVoiceRecord else onSend
        ) {
            Icon(
                imageVector = if (text.isBlank()) Icons.Default.Mic else Icons.AutoMirrored.Filled.Send,
                contentDescription = if (text.isBlank()) "Голосовое" else "Отправить"
            )
        }
    }
}
```

---

## 9. Расширение существующих компонентов

### EmailFormatter — поддержка вложений

Текущий `EmailFormatter.format()` создаёт `EmailMessage` с одним телом. Нужна перегрузка:

```kotlin
data class MediaAttachment(
    val uuid: String,
    val encryptedBytes: ByteArray,  // или File для потоковой передачи
    val mimeType: String = "application/octet-stream"
)

fun formatWithMedia(
    envelope: EncryptedEnvelope,
    chatId: String,
    msgUuid: String,
    fromEmail: String,
    toEmail: String,
    attachment: MediaAttachment
): EmailMessage {
    // Возвращает EmailMessage с флагом hasAttachment = true
    // Зашифрованный attachment передаётся отдельно в SmtpClient
    val subject = "${EmailMessage.SUBJECT_PREFIX}$chatId/$msgUuid"
    val wireBytes = envelope.toBytes()
    val base64Body = base64Encode(wireBytes)
    return EmailMessage(
        from = fromEmail,
        to = toEmail,
        subject = subject,
        body = base64Body,
        contentType = EmailMessage.CHEBURMAIL_CONTENT_TYPE,
        attachment = attachment   // нужно добавить поле в EmailMessage
    )
}
```

Альтернатива (чище): оставить `EmailMessage` неизменным, передавать `MediaAttachment` отдельным параметром в `SmtpClient.send()`.

### EmailParser — извлечение вложений

Нужно обновить `ImapClient.extractBody()` для обработки multipart/mixed:

```kotlin
data class ParsedEmailBody(
    val metadataBytes: ByteArray,       // тело Part 0 (зашифрованный JSON метаданных)
    val attachmentBytes: ByteArray?,    // байты Part 1 (зашифрованное вложение)
    val hasAttachment: Boolean
)

private fun extractBody(message: javax.mail.Message): ParsedEmailBody {
    val content = message.content
    return when (content) {
        is javax.mail.Multipart -> {
            if (content.count >= 2) {
                val part0 = content.getBodyPart(0)
                val meta = part0.inputStream.readBytes()
                val part1 = content.getBodyPart(1)
                val attachment = part1.inputStream.readBytes()
                ParsedEmailBody(meta, attachment, true)
            } else if (content.count == 1) {
                val part = content.getBodyPart(0)
                ParsedEmailBody(part.inputStream.readBytes(), null, false)
            } else {
                ParsedEmailBody(ByteArray(0), null, false)
            }
        }
        is InputStream -> ParsedEmailBody(content.readBytes(), null, false)
        else -> ParsedEmailBody(ByteArray(0), null, false)
    }
}
```

### TransportService — sendMedia

```kotlin
suspend fun sendMedia(
    metadataPlaintext: ByteArray,   // JSON: {type, mimeType, fileName, fileSize}
    mediaPlaintext: ByteArray,      // сырые байты медиафайла
    recipientPublicKey: ByteArray,
    senderPrivateKey: ByteArray,
    chatId: String,
    msgUuid: String,
    fromEmail: String,
    toEmail: String,
    config: EmailConfig,
    onProgress: (Float) -> Unit
) {
    // 1. Шифруем метаданные (маленькие — обычный encrypt)
    val metaEnvelope = encryptor.encrypt(metadataPlaintext, recipientPublicKey, senderPrivateKey)
    
    // 2. Шифруем медиа-байты
    val mediaEncryptor = MediaEncryptor(box, nonceGenerator)
    val encryptedMedia = mediaEncryptor.encryptBytes(mediaPlaintext, recipientPublicKey, senderPrivateKey)
    
    // 3. Форматируем основной email
    val email = emailFormatter.format(metaEnvelope, chatId, msgUuid, fromEmail, toEmail)
    
    // 4. Отправляем через SMTP с вложением
    smtpClient.sendWithAttachment(config, email, encryptedMedia, onProgress)
}
```

### ReceiveWorker — обработка медиа

```kotlin
// В ReceiveWorker после парсинга:
if (parsedBody.hasAttachment) {
    // Сохраняем зашифрованное вложение во временный файл
    val tempFile = File(context.cacheDir, "recv_${msgUuid}.bin")
    tempFile.writeBytes(parsedBody.attachmentBytes!!)
    
    // Расшифровываем метаданные
    val metaJson = decryptor.decrypt(parsedMessage.envelope, senderPublicKey, myPrivateKey)
    val metadata = Json.decodeFromString<MediaMetadata>(String(metaJson))
    
    // Расшифровываем вложение
    val mediaDecryptor = MediaDecryptor(box)
    val plainMedia = mediaDecryptor.decryptBytes(parsedBody.attachmentBytes, senderPublicKey, myPrivateKey)
    
    // Сохраняем в filesDir
    val mediaFile = File(context.filesDir, "media/${msgUuid}.${metadata.extension}")
    mediaFile.parentFile?.mkdirs()
    mediaFile.writeBytes(plainMedia)
    
    // Если изображение — генерируем миниатюру
    if (metadata.type == "image") {
        val thumb = generateThumbnail(plainMedia)
        val thumbFile = File(context.filesDir, "thumbnails/${msgUuid}.jpg")
        thumbFile.parentFile?.mkdirs()
        thumbFile.writeBytes(thumb)
    }
    
    // Сохраняем в Room с mediaType и путями
    messageDao.insert(message.copy(
        mediaType = MediaType.valueOf(metadata.type.uppercase()),
        localMediaUri = mediaFile.absolutePath,
        thumbnailUri = thumbFile?.absolutePath,
        fileName = metadata.fileName,
        fileSize = metadata.fileSize,
        mimeType = metadata.mimeType,
        mediaDownloadStatus = MediaDownloadStatus.READY
    ))
}
```

---

## 10. Зависимости для добавления

```toml
# libs.versions.toml
coil = "3.1.0"

[libraries]
coil-compose = { group = "io.coil-kt.coil3", name = "coil-compose", version.ref = "coil" }
```

```kotlin
// app/build.gradle.kts
implementation(libs.coil.compose)
```

**Больше никаких новых зависимостей не требуется:**
- Gallery picker: `ActivityResultContracts.PickVisualMedia()` — из `activity-compose` (уже есть)
- Camera: `ActivityResultContracts.TakePicture()` + `FileProvider` из `core-ktx` (уже есть)
- File picker: `ActivityResultContracts.OpenDocument()` (уже есть)
- Voice: `MediaRecorder` + `MediaPlayer` — Android SDK (без доп. зависимостей)
- Waveform: Custom Compose Canvas (без зависимостей)
- Image compression: `Bitmap.compress()` — Android SDK
- Thumbnail: `BitmapFactory` — Android SDK
- MediaStore Downloads: Android SDK
- Multipart email: JavaMail `MimeMultipart` (уже есть)

---

## 11. Структура новых файлов

```
app/src/main/java/ru/cheburmail/app/
├── crypto/
│   ├── MediaEncryptor.kt          — encryptBytes() / decryptBytes()
│   └── model/
│       └── MediaMetadata.kt       — @Serializable data class
├── transport/
│   ├── MediaAttachment.kt         — data class для передачи в SmtpClient
│   └── MediaEmailBuilder.kt       — формирует MimeMessage с вложением
├── media/
│   ├── ImagePickerHelper.kt       — launch gallery / camera
│   ├── FilePickerHelper.kt        — launch SAF, read bytes, save to Downloads
│   ├── VoiceRecorder.kt           — MediaRecorder wrapper
│   ├── VoicePlayer.kt             — MediaPlayer wrapper
│   ├── ImageCompressor.kt         — compress + thumbnail
│   └── MediaStorage.kt            — сохранение/загрузка media files из filesDir
├── db/
│   └── entity/MessageEntity.kt    — добавить media-колонки
└── ui/
    └── chat/
        ├── bubbles/
        │   ├── ImageMessageBubble.kt
        │   ├── FileMessageBubble.kt
        │   └── VoiceMessageBubble.kt
        ├── WaveformView.kt
        └── MediaInputBar.kt       — расширенный MessageInput с прикреплением
```

---

## 12. Известные подводные камни

### JavaMail и Android

1. **Timeout при отправке больших файлов**: Увеличить `mail.smtp.writetimeout` до 60000 мс для файлов > 5 МБ.

2. **Base64 overhead в JavaMail**: По умолчанию JavaMail разбивает Base64 на строки по 76 символов с CRLF. Это добавляет ~2% overhead к 33% Base64 — итого ~35%. При лимите 25 МБ: max plaintext = 18.5 МБ.

3. **ImapClient.extractBody() для больших вложений**: Текущий код вычитывает всё в `ByteArray`. Для 18 МБ вложения это создаёт 18+ МБ буфер. Для Phase 8 это допустимо, но для будущего нужен потоковый подход через `InputStream` → файл.

4. **Multipart parsing в extractBody**: Текущий `extractBody` возвращает `ByteArray` первой части. Нужно изменить сигнатуру или сделать отдельный метод `extractMultipart()`.

### Crypto

5. **EncryptedEnvelope для метаданных**: Текущий `MessageEncryptor.encrypt()` принимает `ByteArray` — JSON метаданных можно шифровать через него напрямую. Никаких изменений не нужно.

6. **Два нonce**: Метаданные и вложение шифруются с разными nonce. Это правильно и безопасно — один ключ, разные nonce.

### Android

7. **READ_EXTERNAL_STORAGE для API 26-28**: Нужно для сохранения в Downloads. На API 29+ не требуется.

8. **RECORD_AUDIO**: Запрашивать во время первого нажатия на микрофон, не при старте приложения.

9. **Bitmap.recycle()**: Обязательно вызывать после сжатия/генерации миниатюры, иначе утечка нативной памяти.

10. **MediaRecorder.stop() при короткой записи**: Если пользователь остановил запись менее чем через 500 мс — `stop()` бросает `RuntimeException`. Нужен try/catch с удалением неполного файла.

11. **cacheDir vs filesDir**: Temp-файлы при шифровании → `cacheDir` (удаляются системой). Сохранённые медиафайлы → `filesDir` (постоянные).

12. **FileProvider**: Обязателен для `TakePicture`. Без него — `FileUriExposedException` на Android 7+.

13. **Coil и файловые URI**: `AsyncImage(model = file)` работает напрямую с `File` объектом. Для URI из `filesDir` нужен `FileProvider` если передавать между процессами, но для internal Compose — прямой путь работает.

14. **Room version bump**: `CheburMailDatabase.version` должен быть увеличен с 1 до 2, иначе Room крашнется при старте на устройствах с уже установленной версией.

---

## 13. Порядок реализации (рекомендуемый)

1. **Шаг 1 — DB миграция**: Обновить `MessageEntity`, добавить enum'ы, написать `MIGRATION_1_2`, поднять версию БД. Проверить миграцию unit-тестом.

2. **Шаг 2 — Crypto**: Создать `MediaEncryptor`/`MediaDecryptor`. Unit-тесты encrypt→decrypt roundtrip.

3. **Шаг 3 — Transport**: Обновить `SmtpClient.send()` для поддержки вложений. Обновить `ImapClient.extractBody()` для multipart. Unit-тесты с моками.

4. **Шаг 4 — Изображения**: `ImagePickerHelper`, `ImageCompressor`, `ImageStorage`. UI: `ImageMessageBubble`. Интеграция в `ChatViewModel`.

5. **Шаг 5 — Файлы**: `FilePickerHelper`, `FileStorage`, `FileMessageBubble`. Сохранение в Downloads.

6. **Шаг 6 — Голос**: `VoiceRecorder`, `VoicePlayer`, `WaveformView`, `VoiceMessageBubble`.

7. **Шаг 7 — Progress**: `LinearProgressIndicator` во время отправки/получения медиа.

8. **Шаг 8 — Integration test**: Отправка изображения с одного устройства, получение на другом (Eugene ↔ Marina).
