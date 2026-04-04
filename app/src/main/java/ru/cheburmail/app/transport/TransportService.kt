package ru.cheburmail.app.transport

import ru.cheburmail.app.crypto.MessageDecryptor
import ru.cheburmail.app.crypto.MessageEncryptor

/**
 * Orchestrates the full send/receive pipeline:
 *   Send:    plaintext -> encrypt -> format -> smtp
 *   Receive: imap -> parse -> list of ParsedMessage (decrypt is caller's responsibility)
 */
class TransportService(
    private val smtpClient: SmtpClient,
    private val imapClient: ImapClient,
    private val emailFormatter: EmailFormatter,
    private val emailParser: EmailParser,
    private val encryptor: MessageEncryptor,
    private val decryptor: MessageDecryptor
) {

    /**
     * Send pipeline: plaintext -> encrypt -> format -> smtp.
     *
     * @param plaintext message text (UTF-8 bytes)
     * @param recipientPublicKey recipient's 32-byte X25519 public key
     * @param senderPrivateKey sender's 32-byte X25519 private key
     * @param chatId chat identifier
     * @param msgUuid message UUID (for deduplication)
     * @param fromEmail sender email address
     * @param toEmail recipient email address
     * @param config SMTP configuration
     * @throws TransportException on send errors
     * @throws ru.cheburmail.app.crypto.CryptoException on encryption errors
     */
    fun sendMessage(
        plaintext: ByteArray,
        recipientPublicKey: ByteArray,
        senderPrivateKey: ByteArray,
        chatId: String,
        msgUuid: String,
        fromEmail: String,
        toEmail: String,
        config: EmailConfig
    ) {
        // 1. Encrypt
        val envelope = encryptor.encrypt(plaintext, recipientPublicKey, senderPrivateKey)

        // 2. Format as email
        val email = emailFormatter.format(envelope, chatId, msgUuid, fromEmail, toEmail)

        // 3. Send via SMTP
        smtpClient.send(config, email)
    }

    /**
     * Receive pipeline: imap -> filter -> parse -> list of ParsedMessage.
     * Decryption and persistence are the caller's responsibility (ReceiveWorker)
     * because the private key comes from SecureKeyStorage.
     *
     * @param config IMAP configuration
     * @return list of ParsedMessage
     */
    fun receiveMessages(config: EmailConfig): List<EmailParser.ParsedMessage> {
        val emails = imapClient.fetchMessages(config)

        return emails
            .filter { emailParser.isCheburMail(it) }
            .mapNotNull { email ->
                try {
                    emailParser.parse(email)
                } catch (e: TransportException.FormatException) {
                    android.util.Log.w("TransportService", "Skipping malformed message: ${e.message}")
                    null
                }
            }
    }
}
