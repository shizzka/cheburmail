package ru.cheburmail.app.ui.chat

import android.content.Context
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
