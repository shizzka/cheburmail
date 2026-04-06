package ru.cheburmail.app.transport

import android.util.Log
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.util.ByteArrayDataSource

/**
 * Sends email messages via SMTP over SSL.
 * Blocking — call from a background thread or coroutine dispatcher.
 */
open class SmtpClient {

    /**
     * Send an email via SMTP.
     *
     * @param config connection configuration (host, port, credentials)
     * @param message email message (from, to, subject, body, contentType)
     * @throws TransportException.SmtpException on connection/auth/send errors
     */
    open fun send(config: EmailConfig, message: EmailMessage) {
        try {
            Log.d(TAG, "Отправка письма: ${message.from} -> ${message.to}, subject: ${message.subject}")

            val props = Properties().apply {
                put("mail.smtp.host", config.smtpHost)
                put("mail.smtp.port", config.smtpPort.toString())
                put("mail.smtp.auth", "true")
                put("mail.smtp.ssl.enable", "true")
                put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
                put("mail.smtp.ssl.checkserveridentity", "true")
                put("mail.smtp.connectiontimeout", "15000")
                put("mail.smtp.timeout", "15000")
                put("mail.smtp.writetimeout", "15000")
            }

            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(config.email, config.password)
                }
            })

            val mimeMessage = MimeMessage(session).apply {
                setFrom(InternetAddress(message.from))
                setRecipient(Message.RecipientType.TO, InternetAddress(message.to))
                subject = message.subject

                val bodyPart = MimeBodyPart().apply {
                    dataHandler = javax.activation.DataHandler(
                        ByteArrayDataSource(message.body, message.contentType)
                    )
                }

                val multipart = MimeMultipart().apply {
                    addBodyPart(bodyPart)
                }

                setContent(multipart)
            }

            Transport.send(mimeMessage)
            Log.i(TAG, "Письмо успешно отправлено: ${message.subject}")
        } catch (e: TransportException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка отправки письма: ${e.message}", e)
            throw TransportException.SmtpException("SMTP send failed: ${e.message}", e)
        }
    }

    /**
     * Send a media email via SMTP as multipart/mixed with two MIME body parts.
     * Part 0: encrypted metadata (JSON), Part 1: encrypted file payload ("payload.enc").
     * Uses a longer write timeout (2 minutes) for potentially large binary attachments.
     *
     * @param config connection configuration (host, port, credentials)
     * @param message email message with body = encrypted metadata and attachment = encrypted payload
     * @throws TransportException.SmtpException on connection/auth/send errors
     * @throws IllegalArgumentException if attachment is null
     */
    open fun sendWithAttachment(config: EmailConfig, message: EmailMessage) {
        requireNotNull(message.attachment) { "attachment must not be null for sendWithAttachment" }
        try {
            Log.d(TAG, "Отправка медиа-письма: ${message.from} -> ${message.to}, subject: ${message.subject}")

            val props = Properties().apply {
                put("mail.smtp.host", config.smtpHost)
                put("mail.smtp.port", config.smtpPort.toString())
                put("mail.smtp.auth", "true")
                put("mail.smtp.ssl.enable", "true")
                put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
                put("mail.smtp.ssl.checkserveridentity", "true")
                put("mail.smtp.connectiontimeout", "15000")
                put("mail.smtp.timeout", MEDIA_WRITE_TIMEOUT.toString())
                put("mail.smtp.writetimeout", MEDIA_WRITE_TIMEOUT.toString())
            }

            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(config.email, config.password)
                }
            })

            val mimeMessage = MimeMessage(session).apply {
                setFrom(InternetAddress(message.from))
                setRecipient(Message.RecipientType.TO, InternetAddress(message.to))
                subject = message.subject

                // Part 0: encrypted metadata
                val metadataPart = MimeBodyPart().apply {
                    dataHandler = javax.activation.DataHandler(
                        ByteArrayDataSource(message.body, message.contentType)
                    )
                }

                // Part 1: encrypted payload
                val payloadPart = MimeBodyPart().apply {
                    dataHandler = javax.activation.DataHandler(
                        ByteArrayDataSource(message.attachment, message.contentType)
                    )
                    fileName = "payload.enc"
                }

                val multipart = MimeMultipart("mixed").apply {
                    addBodyPart(metadataPart)
                    addBodyPart(payloadPart)
                }

                setContent(multipart)
            }

            Transport.send(mimeMessage)
            Log.i(TAG, "Медиа-письмо успешно отправлено: ${message.subject}")
        } catch (e: TransportException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка отправки медиа-письма: ${e.message}", e)
            throw TransportException.SmtpException("SMTP media send failed: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "SmtpClient"
        private const val MEDIA_WRITE_TIMEOUT = 120000
    }
}
