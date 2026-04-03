package ru.cheburmail.app.transport

enum class EmailProvider(
    val smtpHost: String,
    val smtpPort: Int,
    val imapHost: String,
    val imapPort: Int
) {
    YANDEX(
        smtpHost = "smtp.yandex.ru",
        smtpPort = 465,
        imapHost = "imap.yandex.ru",
        imapPort = 993
    ),
    MAILRU(
        smtpHost = "smtp.mail.ru",
        smtpPort = 465,
        imapHost = "imap.mail.ru",
        imapPort = 993
    )
}
