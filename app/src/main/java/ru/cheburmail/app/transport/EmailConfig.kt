package ru.cheburmail.app.transport

data class EmailConfig(
    val email: String,
    val password: String,
    val provider: EmailProvider
) {
    val smtpHost: String get() = provider.smtpHost
    val smtpPort: Int get() = provider.smtpPort
    val imapHost: String get() = provider.imapHost
    val imapPort: Int get() = provider.imapPort
}
