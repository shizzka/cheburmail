package ru.cheburmail.app.db

/**
 * Статус доверия контакту.
 * UNVERIFIED — ключ получен, но не верифицирован лично.
 * VERIFIED — отпечаток проверен при личной встрече.
 * BLOCKED — контакт заблокирован, сообщения игнорируются.
 */
enum class TrustStatus {
    UNVERIFIED,
    VERIFIED,
    BLOCKED
}

/**
 * Тип чата.
 * DIRECT — переписка 1-на-1.
 * GROUP — групповой чат (до 10 участников).
 */
enum class ChatType {
    DIRECT,
    GROUP
}

/**
 * Статус сообщения в жизненном цикле доставки.
 */
enum class MessageStatus {
    SENDING,
    SENT,
    DELIVERED,
    FAILED,
    RECEIVED,
    READ
}

/**
 * Статус элемента очереди отправки.
 */
enum class QueueStatus {
    QUEUED,
    SENDING,
    SENT,
    FAILED
}
