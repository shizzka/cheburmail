package ru.cheburmail.app.transport

sealed class TransportException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    class SmtpException(message: String, cause: Throwable? = null) :
        TransportException(message, cause)

    class ImapException(message: String, cause: Throwable? = null) :
        TransportException(message, cause)

    class FormatException(message: String, cause: Throwable? = null) :
        TransportException(message, cause)
}
