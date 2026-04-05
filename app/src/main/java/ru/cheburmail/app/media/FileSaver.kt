package ru.cheburmail.app.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Утилита для сохранения файлов в папку Загрузки/CheburMail/.
 * На API 29+ использует MediaStore API, на API 26-28 — прямой доступ к файловой системе.
 */
class FileSaver(private val context: Context) {

    /**
     * Сохранить байты как файл в Downloads/CheburMail/.
     * @return Uri сохранённого файла или null при ошибке
     */
    fun saveToDownloads(fileName: String, mimeType: String, bytes: ByteArray): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(fileName, mimeType, bytes)
            } else {
                saveViaFilesystem(fileName, bytes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving file '$fileName': ${e.message}")
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

    private fun saveViaMediaStore(fileName: String, mimeType: String, bytes: ByteArray): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "Downloads/CheburMail")
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
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "CheburMail"
        ).apply { mkdirs() }
        val file = File(dir, fileName)
        FileOutputStream(file).use { it.write(bytes) }
        Log.d(TAG, "Saved via filesystem: ${file.absolutePath}")
        return Uri.fromFile(file)
    }

    companion object {
        private const val TAG = "FileSaver"
    }
}
