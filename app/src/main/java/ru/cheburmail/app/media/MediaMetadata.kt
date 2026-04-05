package ru.cheburmail.app.media

import kotlinx.serialization.Serializable

/**
 * Метаданные медиавложения, передаваемые в зашифрованном виде вместе с файлом.
 * Сериализуется в JSON и шифруется отдельным EncryptedEnvelope перед отправкой.
 */
@Serializable
data class MediaMetadata(
    /** Тип медиа: image, file, voice */
    val type: String,

    /** Имя файла, отображаемое получателю */
    val fileName: String = "",

    /** Размер файла в байтах */
    val fileSize: Long = 0L,

    /** MIME-тип файла (например, "image/jpeg", "audio/ogg") */
    val mimeType: String = "",

    /** Ширина изображения в пикселях (только для IMAGE) */
    val width: Int = 0,

    /** Высота изображения в пикселях (только для IMAGE) */
    val height: Int = 0,

    /** Длительность в миллисекундах (только для VOICE) */
    val durationMs: Long = 0L,

    /**
     * Данные осциллограммы голосового сообщения в виде строки из цифр 0–9,
     * где каждая цифра — нормализованная амплитуда сэмпла (только для VOICE).
     */
    val waveform: String = "",

    /** Текстовая подпись к медиавложению (опционально) */
    val caption: String = ""
) {
    companion object {
        const val TYPE_IMAGE = "image"
        const val TYPE_FILE = "file"
        const val TYPE_VOICE = "voice"
    }
}
