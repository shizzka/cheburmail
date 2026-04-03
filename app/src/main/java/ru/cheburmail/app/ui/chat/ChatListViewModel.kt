package ru.cheburmail.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import ru.cheburmail.app.db.ChatWithLastMessage
import ru.cheburmail.app.db.dao.ChatDao

/**
 * ViewModel списка чатов.
 * Наблюдает за ChatDao.getAllWithLastMessage() и предоставляет
 * реактивный список чатов с превью последнего сообщения.
 */
class ChatListViewModel(
    chatDao: ChatDao
) : ViewModel() {

    val chats: StateFlow<List<ChatWithLastMessage>> = chatDao.getAllWithLastMessage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    class Factory(
        private val chatDao: ChatDao
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatListViewModel(chatDao) as T
        }
    }
}
