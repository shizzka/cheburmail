package ru.cheburmail.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.cheburmail.app.db.ChatType
import ru.cheburmail.app.db.MessageStatus
import ru.cheburmail.app.db.dao.ChatDao
import ru.cheburmail.app.db.dao.MessageDao
import ru.cheburmail.app.db.entity.ChatEntity
import ru.cheburmail.app.db.entity.MessageEntity
import java.util.UUID

/**
 * ViewModel экрана переписки.
 * Наблюдает за сообщениями конкретного чата и обеспечивает отправку новых.
 */
class ChatViewModel(
    private val chatId: String,
    private val messageDao: MessageDao,
    private val chatDao: ChatDao
) : ViewModel() {

    val messages: StateFlow<List<MessageEntity>> = messageDao.getForChat(chatId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _chatTitle = MutableStateFlow<String?>(null)
    val chatTitle: StateFlow<String?> = _chatTitle.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    init {
        viewModelScope.launch {
            val chat = chatDao.getById(chatId)
            _chatTitle.value = chat?.title ?: "Чат"
        }
    }

    fun updateInputText(text: String) {
        _inputText.value = text
    }

    /**
     * Отправить текстовое сообщение.
     * Создаёт MessageEntity со статусом SENDING и сохраняет в БД.
     * Реальная отправка через SMTP будет в TransportService (фаза 7+).
     */
    fun sendMessage() {
        val text = _inputText.value.trim()
        if (text.isEmpty()) return

        _inputText.value = ""

        viewModelScope.launch {
            val message = MessageEntity(
                id = UUID.randomUUID().toString(),
                chatId = chatId,
                isOutgoing = true,
                plaintext = text,
                status = MessageStatus.SENDING,
                timestamp = System.currentTimeMillis()
            )
            messageDao.insert(message)

            // TODO: Поставить в очередь отправки через SendQueueDao + TransportService
        }
    }

    class Factory(
        private val chatId: String,
        private val messageDao: MessageDao,
        private val chatDao: ChatDao
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(chatId, messageDao, chatDao) as T
        }
    }
}
