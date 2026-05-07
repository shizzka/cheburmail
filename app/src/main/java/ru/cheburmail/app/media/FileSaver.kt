package ru.cheburmail.app.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream

/**
 * Утилита для сохранения файлов в папку Загрузки/CheburMail/.
 * На API 29+ использует MediaStore API, на API 26-28 — прямой доступ к файловой системе.
 *
 * Безопасность: имя файла приходит из расшифрованной metadata контакта, поэтому
 * перед записью обязательно прогоняем через [sanitizeFileName] — иначе
 * `../../etc/x` мог бы выйти за пределы Downloads/CheburMail.
 */
class FileSaver(private val context: Context) {

    /**
     * Сохранить байты как файл в Downloads/CheburMail/.
     * @return Uri сохранённого файла или null при ошибке
     */
    fun saveToDownloads(fileName: String, mimeType: String, bytes: ByteArray): Uri? {
        val safe = sanitizeFileName(fileName)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(safe, mimeType, bytes)
            } else {
                saveViaFilesystem(safe, bytes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving file '$safe': ${e.message}")
            null
        }
    }

    /**
     * Сохранить файл из sourceUri в Downloads/CheburMail/.
     * @return Uri сохранённого файла или null при ошибке
     */
    fun saveToDownloads(fileName: String, mimeType: String, sourceUri: Uri): Uri? {
        return try {
            val bytes = context.contentResolver.openInputStream(sourceUri)?.readBytes()
                ?: return null
            saveToDownloads(fileName, mimeType, bytes)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading source URI for '$fileName': ${e.message}")
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(fileName: String, mimeType: String, bytes: ByteArray): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/CheburMail")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            out.write(bytes)
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        Log.d(TAG, "Saved via MediaStore: $uri")
        return uri
    }

    private fun saveViaFilesystem(fileName: String, bytes: ByteArray): Uri? {
        @Suppress("DEPRECATION")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "CheburMail"
        ).apply { mkdirs() }
        val file = File(dir, fileName)
        // Двойная защита от path-traversal: если canonical путь не внутри dir — отказ.
        val dirCanonical = dir.canonicalPath
        val fileCanonical = file.canonicalPath
        if (!fileCanonical.startsWith("$dirCanonical/") && fileCanonical != dirCanonical) {
            Log.e(TAG, "Path traversal attempt: $fileCanonical outside $dirCanonical")
            return null
        }
        FileOutputStream(file).use { it.write(bytes) }
        Log.d(TAG, "Saved via filesystem: ${file.absolutePath}")
        return Uri.fromFile(file)
    }

    companion object {
        private const val TAG = "FileSaver"
        private const val MAX_NAME_LEN = 200
        private val UNSAFE_CHARS = Regex("""[\p{Cntrl}/\\:*?"<>|]""")

        /**
         * Очистка имени файла: убираем path separators, control chars, '..',
         * обрезаем до 200 символов. Если после очистки имя пустое — fallback
         * на "file".
         */
        fun sanitizeFileName(raw: String): String {
            val noPath = raw.substringAfterLast('/').substringAfterLast('\\')
            val cleaned = noPath
                .replace(UNSAFE_CHARS, "_")
                .replace(Regex("""\.{2,}"""), "_")  // .. или ... → _
                .trim('.', ' ', '\t')
            val truncated = if (cleaned.length > MAX_NAME_LEN) cleaned.take(MAX_NAME_LEN) else cleaned
            return truncated.ifBlank { "file" }
        }
    }
}
