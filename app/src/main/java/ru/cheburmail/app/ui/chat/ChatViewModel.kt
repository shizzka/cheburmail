package ru.cheburmail.app.ui.chat

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.cheburmail.app.crypto.CryptoProvider
import ru.cheburmail.app.crypto.MessageEncryptor
import ru.cheburmail.app.crypto.NonceGenerator
import ru.cheburmail.app.db.ChatType
import ru.cheburmail.app.db.MediaDownloadStatus
import ru.cheburmail.app.db.MediaType
import ru.cheburmail.app.db.MessageStatus
import ru.cheburmail.app.db.QueueStatus
import ru.cheburmail.app.db.dao.ChatDao
import ru.cheburmail.app.db.dao.ContactDao
import ru.cheburmail.app.db.dao.MessageDao
import ru.cheburmail.app.db.dao.SendQueueDao
import ru.cheburmail.app.db.entity.ChatEntity
import ru.cheburmail.app.db.entity.MessageEntity
import ru.cheburmail.app.db.entity.SendQueueEntity
import ru.cheburmail.app.crypto.MessageDecryptor
import ru.cheburmail.app.media.FileSaver
import ru.cheburmail.app.media.ImageCompressor
import ru.cheburmail.app.media.MediaEncryptor
import ru.cheburmail.app.media.MediaFileManager
import ru.cheburmail.app.media.MediaMetadata
import ru.cheburmail.app.media.VoicePlayer
import ru.cheburmail.app.media.VoiceRecorder
import ru.cheburmail.app.notification.NotificationHelper
import ru.cheburmail.app.repository.AccountRepository
import ru.cheburmail.app.storage.SecureKeyStorage
import ru.cheburmail.app.sync.OutboxDrainWorker
import ru.cheburmail.app.transport.EmailFormatter
import ru.cheburmail.app.transport.EmailParser
import ru.cheburmail.app.transport.ImapClient
import ru.cheburmail.app.transport.ReceiveWorker
import ru.cheburmail.app.transport.RetryStrategy
import ru.cheburmail.app.transport.SmtpClient
import ru.cheburmail.app.transport.TransportService
import java.util.UUID

/**
 * ViewModel экрана переписки.
 * Наблюдает за сообщениями конкретного чата и обеспечивает отправку новых.
 */
class ChatViewModel(
    private val chatId: String,
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    private val contactDao: ContactDao,
    private val sendQueueDao: SendQueueDao,
    private val keyStorage: SecureKeyStorage,
    private val appContext: Context
) : ViewModel() {

    val messages: StateFlow<List<MessageEntity>> = messageDao.getForChat(chatId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Помечать как прочитанные когда появляются новые сообщения
        viewModelScope.launch {
            messageDao.getForChat(chatId).collect {
                messageDao.markChatAsRead(chatId)
            }
        }
    }

    private val _chatTitle = MutableStateFlow<String?>(null)
    val chatTitle: StateFlow<String?> = _chatTitle.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // Cached recipient email from chat contact
    private var recipientEmail: String? = null

    // ── Media helpers ──────────────────────────────────────────────────────
    val voiceRecorder = VoiceRecorder(appContext)
    val voicePlayer = VoicePlayer(appContext)
    private val fileSaver = FileSaver(appContext)
    private val mediaFileManager = MediaFileManager(appContext)
    private val imageCompressor = ImageCompressor(appContext)

    /** URI для снимка с камеры. */
    private val _cameraUri = MutableStateFlow<Uri?>(null)
    val cameraUri: StateFlow<Uri?> = _cameraUri.asStateFlow()

    private val _isRecordingVoice = MutableStateFlow(false)
    val isRecordingVoice: StateFlow<Boolean> = _isRecordingVoice.asStateFlow()

    private val _isSendingMedia = MutableStateFlow(false)
    val isSendingMedia: StateFlow<Boolean> = _isSendingMedia.asStateFlow()

    private val _sendingMediaLabel = MutableStateFlow("Отправка...")
    val sendingMediaLabel: StateFlow<String> = _sendingMediaLabel.asStateFlow()

    init {
        viewModelScope.launch {
            val chat = chatDao.getById(chatId)
            _chatTitle.value = chat?.title ?: "Чат"
            // Помечаем входящие как прочитанные
            messageDao.markChatAsRead(chatId)
        }
        // Resolve recipient email from chatId
        viewModelScope.launch {
            val contacts = withContext(Dispatchers.IO) { contactDao.getAllOnce() }

            // Strategy 1: deterministic UUID from "direct:<email>"
            for (contact in contacts) {
                val expectedChatId = UUID.nameUUIDFromBytes(
                    "direct:${contact.email}".toByteArray()
                ).toString()
                if (expectedChatId == chatId) {
                    recipientEmail = contact.email
                    _chatTitle.value = contact.displayName
                    break
                }
            }

            // Strategy 2: if chat was created by ReceiveWorker (sender's chatId),
            // find the contact from incoming messages in this chat
            if (recipientEmail == null) {
                val msgs = withContext(Dispatchers.IO) { messageDao.getForChatOnce(chatId) }
                val incomingMsg = msgs.firstOrNull { !it.isOutgoing && it.senderContactId != null }
                if (incomingMsg != null) {
                    val contact = withContext(Dispatchers.IO) {
                        contactDao.getById(incomingMsg.senderContactId!!)
                    }
                    if (contact != null) {
                        recipientEmail = contact.email
                        _chatTitle.value = contact.displayName
                        Log.d(TAG, "Resolved recipient from incoming message: ${contact.email}")
                    }
                }
            }
        }
    }

    fun updateInputText(text: String) {
        _inputText.value = text
    }

    /**
     * Pull-to-refresh: запускает синхронизацию входящих сообщений.
     */
    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                withContext(Dispatchers.IO) {
                    val accountRepo = AccountRepository.create(appContext)
                    val config = accountRepo.getActive() ?: return@withContext

                    val ls = CryptoProvider.lazySodium
                    val nonceGen = NonceGenerator(ls)
                    val decryptor = MessageDecryptor(ls)
                    val transportService = TransportService(
                        smtpClient = SmtpClient(),
                        imapClient = ImapClient(),
                        emailFormatter = EmailFormatter(),
                        emailParser = EmailParser(),
                        encryptor = ru.cheburmail.app.crypto.MessageEncryptor(ls, nonceGen),
                        decryptor = decryptor
                    )

                    val keyPair = keyStorage.getOrCreateKeyPair()
                    val db = ru.cheburmail.app.db.CheburMailDatabase.getInstance(appContext)
                    val keyExchangeManager = ru.cheburmail.app.messaging.KeyExchangeManager(
                        smtpClient = SmtpClient(),
                        contactDao = db.contactDao(),
                        keyStorage = keyStorage
                    )
                    val receiveWorker = ReceiveWorker(
                        transportService = transportService,
                        decryptor = decryptor,
                        retryStrategy = RetryStrategy(),
                        messageDao = db.messageDao(),
                        contactDao = db.contactDao(),
                        chatDao = db.chatDao(),
                        notificationHelper = NotificationHelper(appContext),
                        recipientPrivateKey = keyPair.getPrivateKey(),
                        keyExchangeManager = keyExchangeManager,
                        emailConfig = config
                    )

                    val received = receiveWorker.pollAndProcess(config)
                    Log.d(TAG, "Pull-to-refresh: получено $received новых сообщений")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка refresh: ${e.message}")
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /**
     * Отправить текстовое сообщение.
     * Шифрует, кладёт в send_queue, триггерит OutboxDrainWorker.
     */
    fun sendMessage() {
        val text = _inputText.value.trim()
        if (text.isEmpty()) return

        _inputText.value = ""

        viewModelScope.launch {
            try {
                // Создаём чат если не существует
                val existingChat = chatDao.getById(chatId)
                if (existingChat == null) {
                    val now = System.currentTimeMillis()
                    chatDao.insert(
                        ChatEntity(
                            id = chatId,
                            type = ChatType.DIRECT,
                            title = _chatTitle.value,
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                }

                val msgId = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()

                // Сохраняем сообщение в БД
                val message = MessageEntity(
                    id = msgId,
                    chatId = chatId,
                    isOutgoing = true,
                    plaintext = text,
                    status = MessageStatus.SENDING,
                    timestamp = now
                )
                messageDao.insert(message)

                // Шифруем и ставим в очередь
                val email = recipientEmail
                if (email == null) {
                    Log.e(TAG, "Recipient email not resolved for chat $chatId")
                    messageDao.updateStatus(msgId, MessageStatus.FAILED)
                    return@launch
                }

                withContext(Dispatchers.IO) {
                    val contact = contactDao.getByEmail(email)
                    if (contact == null) {
                        Log.e(TAG, "Contact not found: $email")
                        messageDao.updateStatus(msgId, MessageStatus.FAILED)
                        return@withContext
                    }

                    val keyPair = keyStorage.getOrCreateKeyPair()
                    val ls = CryptoProvider.lazySodium
                    val encryptor = MessageEncryptor(ls, NonceGenerator(ls))

                    Log.d(TAG, "Encrypting for ${contact.email}, " +
                        "recipientPubKey=${java.util.Base64.getEncoder().encodeToString(contact.publicKey).take(16)}..., " +
                        "myPubKey=${java.util.Base64.getEncoder().encodeToString(keyPair.publicKey).take(16)}...")
                    val envelope = encryptor.encrypt(
                        message = text.toByteArray(Charsets.UTF_8),
                        recipientPublicKey = contact.publicKey,
                        senderPrivateKey = keyPair.getPrivateKey()
                    )

                    // Ставим в очередь отправки
                    sendQueueDao.insert(
                        SendQueueEntity(
                            messageId = msgId,
                            recipientEmail = email,
                            encryptedPayload = envelope.toBytes(),
                            status = QueueStatus.QUEUED,
                            createdAt = now,
                            updatedAt = now
                        )
                    )

                    // Триггерим немедленную отправку
                    OutboxDrainWorker.enqueue(appContext)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending message: ${e.message}", e)
            }
        }
    }

    /**
     * Создать URI для снимка камеры через FileProvider.
     */
    fun prepareCameraUri(): Uri {
        val uri = mediaFileManager.createCameraUri()
        _cameraUri.value = uri
        return uri
    }

    /**
     * Обработать выбранное изображение (из галереи или камеры):
     * сжать, сохранить локально, зашифровать и поставить в очередь отправки.
     */
    fun onImagePicked(uri: Uri) {
        viewModelScope.launch {
            val email = recipientEmail ?: run {
                Log.e(TAG, "Recipient email not resolved for image send")
                return@launch
            }
            _isSendingMedia.value = true
            _sendingMediaLabel.value = "Сжатие изображения..."
            try {
                val compressed = withContext(Dispatchers.IO) { imageCompressor.compress(uri) }

                val msgId = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()

                val localPath = withContext(Dispatchers.IO) {
                    mediaFileManager.saveImage(msgId, compressed.fullBytes)
                }
                val thumbPath = withContext(Dispatchers.IO) {
                    mediaFileManager.saveThumbnail(msgId, compressed.thumbnailBytes)
                }

                ensureChatExists()

                val message = MessageEntity(
                    id = msgId,
                    chatId = chatId,
                    isOutgoing = true,
                    plaintext = "",
                    status = MessageStatus.SENDING,
                    timestamp = now,
                    mediaType = MediaType.IMAGE,
                    localMediaUri = localPath,
                    thumbnailUri = thumbPath,
                    fileSize = compressed.fullBytes.size.toLong(),
                    mimeType = "image/jpeg",
                    mediaDownloadStatus = MediaDownloadStatus.NONE
                )
                messageDao.insert(message)

                _sendingMediaLabel.value = "Шифрование..."
                withContext(Dispatchers.IO) {
                    encryptAndQueueMedia(
                        msgId = msgId,
                        recipientEmail = email,
                        bytes = compressed.fullBytes,
                        metadata = MediaMetadata(
                            type = MediaMetadata.TYPE_IMAGE,
                            fileName = "$msgId.jpg",
                            fileSize = compressed.fullBytes.size.toLong(),
                            mimeType = "image/jpeg",
                            width = compressed.width,
                            height = compressed.height
                        ),
                        now = now
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending image: ${e.message}", e)
            } finally {
                _isSendingMedia.value = false
            }
        }
    }

    /**
     * Отправить файл, выбранный пользователем через file picker.
     */
    fun onFilePicked(uri: Uri) {
        viewModelScope.launch {
            val email = recipientEmail ?: run {
                Log.e(TAG, "Recipient email not resolved for file send")
                return@launch
            }
            _isSendingMedia.value = true
            _sendingMediaLabel.value = "Отправка файла..."
            try {
                val bytes = withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(uri)?.readBytes()
                } ?: run {
                    Log.e(TAG, "Cannot read file URI: $uri")
                    return@launch
                }
                val fileName = mediaFileManager.getFileName(uri) ?: "file"
                val mimeType = mediaFileManager.getMimeType(uri)
                val fileSize = bytes.size.toLong()

                val msgId = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()

                // Save locally
                val localPath = withContext(Dispatchers.IO) {
                    mediaFileManager.saveFile(msgId, bytes, fileName)
                }

                // Ensure chat exists
                ensureChatExists()

                val message = MessageEntity(
                    id = msgId,
                    chatId = chatId,
                    isOutgoing = true,
                    plaintext = "",
                    status = MessageStatus.SENDING,
                    timestamp = now,
                    mediaType = MediaType.FILE,
                    localMediaUri = localPath,
                    fileName = fileName,
                    fileSize = fileSize,
                    mimeType = mimeType
                )
                messageDao.insert(message)

                withContext(Dispatchers.IO) {
                    encryptAndQueueMedia(
                        msgId = msgId,
                        recipientEmail = email,
                        bytes = bytes,
                        metadata = MediaMetadata(
                            type = MediaMetadata.TYPE_FILE,
                            fileName = fileName,
                            fileSize = fileSize,
                            mimeType = mimeType
                        ),
                        now = now
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending file: ${e.message}", e)
            } finally {
                _isSendingMedia.value = false
            }
        }
    }

    /** Начать запись голосового сообщения. */
    fun startVoiceRecording() {
        val msgId = UUID.randomUUID().toString()
        voiceRecorder.start(msgId)
        _isRecordingVoice.value = true
    }

    /** Остановить запись и отправить голосовое сообщение. */
    fun stopVoiceRecordingAndSend() {
        val result = voiceRecorder.stop()
        _isRecordingVoice.value = false
        if (result == null) {
            Log.e(TAG, "Voice recording result is null")
            return
        }
        val email = recipientEmail ?: run {
            Log.e(TAG, "Recipient email not resolved for voice send")
            result.file.delete()
            return
        }

        viewModelScope.launch {
            _isSendingMedia.value = true
            _sendingMediaLabel.value = "Отправка голосового..."
            try {
                val bytes = withContext(Dispatchers.IO) { result.file.readBytes() }
                val msgId = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                val fileName = "voice_$msgId.m4a"

                val localPath = withContext(Dispatchers.IO) {
                    mediaFileManager.saveVoice(msgId, bytes, "m4a")
                }

                ensureChatExists()

                val message = MessageEntity(
                    id = msgId,
                    chatId = chatId,
                    isOutgoing = true,
                    plaintext = "",
                    status = MessageStatus.SENDING,
                    timestamp = now,
                    mediaType = MediaType.VOICE,
                    localMediaUri = localPath,
                    fileName = fileName,
                    fileSize = bytes.size.toLong(),
                    mimeType = "audio/mp4",
                    voiceDurationMs = result.durationMs,
                    waveformData = result.waveform
                )
                messageDao.insert(message)

                withContext(Dispatchers.IO) {
                    encryptAndQueueMedia(
                        msgId = msgId,
                        recipientEmail = email,
                        bytes = bytes,
                        metadata = MediaMetadata(
                            type = MediaMetadata.TYPE_VOICE,
                            fileName = fileName,
                            fileSize = bytes.size.toLong(),
                            mimeType = "audio/mp4",
                            durationMs = result.durationMs,
                            waveform = result.waveform
                        ),
                        now = now
                    )
                }
                result.file.delete()
            } catch (e: Exception) {
                Log.e(TAG, "Error sending voice: ${e.message}", e)
                result.file.delete()
            } finally {
                _isSendingMedia.value = false
            }
        }
    }

    /** Отменить запись голосового сообщения. */
    fun cancelVoiceRecording() {
        voiceRecorder.cancel()
        _isRecordingVoice.value = false
    }

    /** Сохранить входящий файл в папку Загрузки. */
    fun saveFileToDownloads(messageId: String) {
        viewModelScope.launch {
            try {
                val message = withContext(Dispatchers.IO) { messageDao.getByIdOnce(messageId) }
                    ?: return@launch
                val path = message.localMediaUri ?: return@launch
                val fileName = message.fileName ?: "file"
                val mimeType = message.mimeType ?: "application/octet-stream"
                withContext(Dispatchers.IO) {
                    val bytes = java.io.File(path).readBytes()
                    fileSaver.saveToDownloads(fileName, mimeType, bytes)
                }
                Log.d(TAG, "Saved file to downloads: $fileName")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving file to downloads: ${e.message}")
            }
        }
    }

    private suspend fun ensureChatExists() {
        val existing = chatDao.getById(chatId)
        if (existing == null) {
            val now = System.currentTimeMillis()
            chatDao.insert(
                ChatEntity(
                    id = chatId,
                    type = ChatType.DIRECT,
                    title = _chatTitle.value,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
    }

    private suspend fun encryptAndQueueMedia(
        msgId: String,
        recipientEmail: String,
        bytes: ByteArray,
        metadata: MediaMetadata,
        now: Long
    ) {
        val contact = contactDao.getByEmail(recipientEmail) ?: run {
            Log.e(TAG, "Contact not found: $recipientEmail")
            return
        }
        val keyPair = keyStorage.getOrCreateKeyPair()
        val ls = CryptoProvider.lazySodium
        val encryptor = ru.cheburmail.app.media.MediaEncryptor(
            MessageEncryptor(ls, NonceGenerator(ls))
        )
        val encrypted = encryptor.encrypt(
            metadata = metadata,
            fileBytes = bytes,
            recipientPublicKey = contact.publicKey,
            senderPrivateKey = keyPair.getPrivateKey()
        )
        // Queue metadata envelope
        sendQueueDao.insert(
            SendQueueEntity(
                messageId = "${msgId}_meta",
                recipientEmail = recipientEmail,
                encryptedPayload = encrypted.metadataEnvelope.toBytes(),
                status = QueueStatus.QUEUED,
                createdAt = now,
                updatedAt = now
            )
        )
        // Queue payload envelope
        sendQueueDao.insert(
            SendQueueEntity(
                messageId = msgId,
                recipientEmail = recipientEmail,
                encryptedPayload = encrypted.payloadEnvelope.toBytes(),
                status = QueueStatus.QUEUED,
                createdAt = now,
                updatedAt = now
            )
        )
        OutboxDrainWorker.enqueue(appContext)
    }

    override fun onCleared() {
        super.onCleared()
        voiceRecorder.cancel()
        voicePlayer.stop()
    }

    class Factory(
        private val chatId: String,
        private val messageDao: MessageDao,
        private val chatDao: ChatDao,
        private val contactDao: ContactDao,
        private val sendQueueDao: SendQueueDao,
        private val keyStorage: SecureKeyStorage,
        private val appContext: Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(chatId, messageDao, chatDao, contactDao, sendQueueDao, keyStorage, appContext) as T
        }
    }

    companion object {
        private const val TAG = "ChatViewModel"
    }
}
