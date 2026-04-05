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

/**
 * Тип медиавложения в сообщении.
 * NONE — текстовое сообщение без вложений.
 * IMAGE — изображение (JPEG, PNG и др.).
 * FILE — произвольный файл.
 * VOICE — голосовое сообщение.
 */
enum class MediaType {
    NONE,
    IMAGE,
    FILE,
    VOICE
}

/**
 * Статус загрузки медиавложения на устройство.
 * NONE — нет вложения или вложение уже доступно локально (исходящее).
 * PENDING — ожидает загрузки.
 * DOWNLOADING — загружается в данный момент.
 * COMPLETED — загрузка завершена, файл доступен по local_media_uri.
 * FAILED — загрузка не удалась.
 */
enum class MediaDownloadStatus {
    NONE,
    PENDING,
    DOWNLOADING,
    COMPLETED,
    FAILED
}
