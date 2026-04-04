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
import ru.cheburmail.app.crypto.KeyPairGenerator
import ru.cheburmail.app.crypto.MessageDecryptor
import ru.cheburmail.app.crypto.NonceGenerator
import ru.cheburmail.app.db.ChatWithLastMessage
import ru.cheburmail.app.db.CheburMailDatabase
import ru.cheburmail.app.db.dao.ChatDao
import ru.cheburmail.app.db.dao.MessageDao
import ru.cheburmail.app.messaging.KeyExchangeManager
import ru.cheburmail.app.notification.NotificationHelper
import ru.cheburmail.app.repository.AccountRepository
import ru.cheburmail.app.storage.SecureKeyStorage
import ru.cheburmail.app.transport.EmailFormatter
import ru.cheburmail.app.transport.EmailParser
import ru.cheburmail.app.transport.ImapClient
import ru.cheburmail.app.transport.ReceiveWorker
import ru.cheburmail.app.transport.RetryStrategy
import ru.cheburmail.app.transport.SmtpClient
import ru.cheburmail.app.transport.TransportService

/**
 * ViewModel списка чатов.
 * Наблюдает за ChatDao.getAllWithLastMessage() и предоставляет
 * реактивный список чатов с превью последнего сообщения.
 */
class ChatListViewModel(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao? = null,
    private val appContext: Context? = null
) : ViewModel() {

    val chats: StateFlow<List<ChatWithLastMessage>> = chatDao.getAllWithLastMessage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /**
     * Удалить чат и все его сообщения.
     */
    fun deleteChat(chatId: String) {
        viewModelScope.launch {
            messageDao?.deleteByChatId(chatId)
            chatDao.deleteById(chatId)
        }
    }

    /**
     * Pull-to-refresh: синхронизация входящих (включая key exchange).
     */
    fun refresh() {
        val ctx = appContext ?: return
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                withContext(Dispatchers.IO) {
                    val accountRepo = AccountRepository.create(ctx)
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

                    val kpg = KeyPairGenerator(ls)
                    val keyStorage = SecureKeyStorage.create(ctx, kpg)
                    val keyPair = keyStorage.getOrCreateKeyPair()
                    val db = CheburMailDatabase.getInstance(ctx)

                    val keyExchangeManager = KeyExchangeManager(
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
                        notificationHelper = NotificationHelper(ctx),
                        recipientPrivateKey = keyPair.getPrivateKey(),
                        keyExchangeManager = keyExchangeManager,
                        emailConfig = config
                    )

                    val received = receiveWorker.pollAndProcess(config)
                    Log.d(TAG, "Refresh: получено $received новых сообщений")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка refresh: ${e.message}")
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    class Factory(
        private val chatDao: ChatDao,
        private val messageDao: MessageDao? = null,
        private val appContext: Context? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatListViewModel(chatDao, messageDao, appContext) as T
        }
    }

    companion object {
        private const val TAG = "ChatListViewModel"
    }
}
