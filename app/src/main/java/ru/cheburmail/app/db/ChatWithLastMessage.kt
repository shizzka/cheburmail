package ru.cheburmail.app.db

/**
 * Результат JOIN-запроса: чат + превью последнего сообщения.
 * Используется для отображения списка чатов (UI-01).
 */
data class ChatWithLastMessage(
    val chatId: String,
    val chatType: ChatType,
    val title: String?,
    val lastMessageText: String?,
    val lastMessageTime: Long?,
    val unreadCount: Int
)
