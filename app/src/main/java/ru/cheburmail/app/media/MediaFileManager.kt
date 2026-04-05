package ru.cheburmail.app.media

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * Управляет хранением и загрузкой медиафайлов в кэше приложения.
 *
 * Структура директорий:
 *   cacheDir/images/        — полноразмерные изображения
 *   cacheDir/thumbnails/    — миниатюры изображений
 *   cacheDir/voice/         — голосовые сообщения
 *   cacheDir/files/         — произвольные файлы
 *   externalCacheDir/images/camera/ — снимки камеры
 */
class MediaFileManager(private val context: Context) {

    private val imagesDir: File get() = ensureDir(File(context.cacheDir, "images"))
    private val thumbnailsDir: File get() = ensureDir(File(context.cacheDir, "thumbnails"))
    private val voiceDir: File get() = ensureDir(File(context.cacheDir, "voice"))
    private val filesDir: File get() = ensureDir(File(context.cacheDir, "files"))
    private val cameraDir: File
        get() = ensureDir(
            File(context.externalCacheDir ?: context.cacheDir, "images/camera")
        )

    private fun ensureDir(dir: File): File {
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ── Сохранение файлов ──────────────────────────────────────────────────

    /**
     * Сохранить полноразмерное изображение в кэш.
     *
     * @param messageId ID сообщения (используется как имя файла)
     * @param bytes байты изображения (JPEG)
     * @return абсолютный путь к файлу
     */
    fun saveImage(messageId: String, bytes: ByteArray): String {
        val file = File(imagesDir, "$messageId.jpg")
        file.writeBytes(bytes)
        return file.absolutePath
    }

    /**
     * Сохранить миниатюру изображения в кэш.
     *
     * @param messageId ID сообщения
     * @param bytes байты миниатюры (JPEG)
     * @return абсолютный путь к файлу
     */
    fun saveThumbnail(messageId: String, bytes: ByteArray): String {
        val file = File(thumbnailsDir, "$messageId.jpg")
        file.writeBytes(bytes)
        return file.absolutePath
    }

    /**
     * Сохранить голосовое сообщение в кэш.
     *
     * @param messageId ID сообщения
     * @param bytes байты аудиофайла
     * @param extension расширение файла (по умолчанию "ogg")
     * @return абсолютный путь к файлу
     */
    fun saveVoice(messageId: String, bytes: ByteArray, extension: String = "ogg"): String {
        val file = File(voiceDir, "$messageId.$extension")
        file.writeBytes(bytes)
        return file.absolutePath
    }

    /**
     * Сохранить произвольный файл в кэш.
     *
     * @param messageId ID сообщения
     * @param bytes байты файла
     * @param fileName имя файла с расширением
     * @return абсолютный путь к файлу
     */
    fun saveFile(messageId: String, bytes: ByteArray, fileName: String): String {
        val file = File(filesDir, "${messageId}_$fileName")
        file.writeBytes(bytes)
        return file.absolutePath
    }

    // ── Загрузка файлов ────────────────────────────────────────────────────

    /**
     * Прочитать байты файла по абсолютному пути.
     *
     * @param absolutePath абсолютный путь к файлу
     * @return байты файла или null если файл не существует
     */
    fun loadBytes(absolutePath: String): ByteArray? {
        val file = File(absolutePath)
        return if (file.exists()) file.readBytes() else null
    }

    // ── Камера ─────────────────────────────────────────────────────────────

    /**
     * Создать URI для снимка камеры через FileProvider.
     *
     * @param fileName имя файла (по умолчанию генерируется по timestamp)
     * @return URI для передачи в Intent камеры
     */
    fun createCameraUri(fileName: String = "camera_${System.currentTimeMillis()}.jpg"): Uri {
        val file = File(cameraDir, fileName)
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    // ── Метаданные контентных URI ──────────────────────────────────────────

    /**
     * Получить имя файла из content URI.
     *
     * @param uri content URI
     * @return имя файла или null
     */
    fun getFileName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) cursor.getString(nameIndex) else null
                } else null
            }
        } catch (e: Exception) {
            uri.lastPathSegment
        }
    }

    /**
     * Получить размер файла из content URI.
     *
     * @param uri content URI
     * @return размер в байтах или -1 если неизвестен
     */
    fun getFileSize(uri: Uri): Long {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIndex >= 0) cursor.getLong(sizeIndex) else -1L
                } else -1L
            } ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * Получить MIME-тип файла из content URI.
     *
     * @param uri content URI
     * @return MIME-тип или "application/octet-stream" если неизвестен
     */
    fun getMimeType(uri: Uri): String {
        return try {
            context.contentResolver.getType(uri)
                ?: guessFromExtension(uri)
                ?: "application/octet-stream"
        } catch (e: Exception) {
            "application/octet-stream"
        }
    }

    private fun guessFromExtension(uri: Uri): String? {
        val extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
        return if (extension.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
        } else null
    }

    /**
     * Прочитать байты файла из content URI.
     *
     * @param uri content URI (gallery, camera, etc.)
     * @return байты файла
     * @throws IllegalArgumentException если не удалось прочитать файл
     */
    fun readFromUri(uri: Uri): ByteArray {
        return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Cannot read from URI: $uri")
    }
}
