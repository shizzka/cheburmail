package ru.cheburmail.app.transport

data class EmailMessage(
    val from: String,
    val to: String,
    val subject: String,
    val body: ByteArray,
    val contentType: String = CHEBURMAIL_CONTENT_TYPE,
    val attachment: ByteArray? = null
) {
    companion object {
        const val CHEBURMAIL_CONTENT_TYPE = "application/x-cheburmail"
        const val CHEBURMAIL_MEDIA_CONTENT_TYPE = "application/x-cheburmail-media"
        const val SUBJECT_PREFIX = "CM/1/"
        const val MEDIA_SUBJECT_SUFFIX = "/M"
    }

    fun isMediaMessage(): Boolean = subject.endsWith(MEDIA_SUBJECT_SUFFIX)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmailMessage) return false
        return from == other.from && to == other.to &&
            subject == other.subject &&
            body.contentEquals(other.body) &&
            contentType == other.contentType &&
            (attachment == null && other.attachment == null ||
                attachment != null && other.attachment != null && attachment.contentEquals(other.attachment))
    }

    override fun hashCode(): Int {
        var result = from.hashCode()
        result = 31 * result + to.hashCode()
        result = 31 * result + subject.hashCode()
        result = 31 * result + body.contentHashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + (attachment?.contentHashCode() ?: 0)
        return result
    }
}
