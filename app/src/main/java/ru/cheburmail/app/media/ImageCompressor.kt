package ru.cheburmail.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream

/**
 * Сжимает изображение для отправки как медиа-сообщение.
 *
 * Full-size: максимум 1920px по длинной стороне, JPEG q85.
 * Thumbnail: максимум 256px по длинной стороне, JPEG q60.
 * Учитывает EXIF-ориентацию для корректного отображения.
 */
class ImageCompressor(private val context: Context) {

    /**
     * Результат сжатия изображения.
     *
     * @param fullBytes      байты полноразмерного JPEG-изображения (для отправки)
     * @param thumbnailBytes байты миниатюры JPEG (для отображения в чате)
     * @param width          ширина полноразмерного изображения в пикселях
     * @param height         высота полноразмерного изображения в пикселях
     * @param thumbWidth     ширина миниатюры в пикселях
     * @param thumbHeight    высота миниатюры в пикселях
     */
    data class CompressedImage(
        val fullBytes: ByteArray,
        val thumbnailBytes: ByteArray,
        val width: Int,
        val height: Int,
        val thumbWidth: Int,
        val thumbHeight: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CompressedImage) return false
            return width == other.width &&
                height == other.height &&
                thumbWidth == other.thumbWidth &&
                thumbHeight == other.thumbHeight &&
                fullBytes.contentEquals(other.fullBytes) &&
                thumbnailBytes.contentEquals(other.thumbnailBytes)
        }

        override fun hashCode(): Int {
            var result = fullBytes.contentHashCode()
            result = 31 * result + thumbnailBytes.contentHashCode()
            result = 31 * result + width
            result = 31 * result + height
            result = 31 * result + thumbWidth
            result = 31 * result + thumbHeight
            return result
        }
    }

    /**
     * Сжать изображение из URI.
     *
     * @param uri URI изображения (content:// или file://)
     * @return [CompressedImage] с полноразмерным изображением и миниатюрой
     * @throws IllegalArgumentException если изображение не удалось декодировать
     */
    fun compress(uri: Uri): CompressedImage {
        // Читаем EXIF-ориентацию до декодирования изображения
        val exifRotation = readExifRotation(uri)

        // Определяем размеры изображения без полного декодирования
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream, null, options)
        }

        val sourceWidth = options.outWidth
        val sourceHeight = options.outHeight

        require(sourceWidth > 0 && sourceHeight > 0) {
            "Failed to decode image dimensions from $uri"
        }

        // Вычисляем inSampleSize для экономии памяти при декодировании
        val inSampleSize = calculateInSampleSize(sourceWidth, sourceHeight, MAX_FULL_PX)

        // Декодируем изображение с масштабированием
        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
        }
        val decodedBitmap = context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream, null, decodeOptions)
        } ?: throw IllegalArgumentException("Cannot open input stream for $uri")

        // Применяем EXIF-ротацию
        val rotatedBitmap = applyExifRotation(decodedBitmap, exifRotation)

        // Масштабируем до целевого размера полноразмерного изображения
        val fullBitmap = scaleBitmap(rotatedBitmap, MAX_FULL_PX)
        if (fullBitmap !== rotatedBitmap && rotatedBitmap !== decodedBitmap) {
            rotatedBitmap.recycle()
        }
        if (decodedBitmap !== rotatedBitmap) {
            decodedBitmap.recycle()
        }

        // Создаём миниатюру
        val thumbBitmap = scaleBitmap(fullBitmap, MAX_THUMB_PX)

        // Сжимаем в JPEG
        val fullBytes = compressBitmapToJpeg(fullBitmap, FULL_QUALITY)
        val thumbBytes = compressBitmapToJpeg(thumbBitmap, THUMB_QUALITY)

        val resultWidth = fullBitmap.width
        val resultHeight = fullBitmap.height
        val resultThumbWidth = thumbBitmap.width
        val resultThumbHeight = thumbBitmap.height

        if (thumbBitmap !== fullBitmap) {
            thumbBitmap.recycle()
        }
        fullBitmap.recycle()

        return CompressedImage(
            fullBytes = fullBytes,
            thumbnailBytes = thumbBytes,
            width = resultWidth,
            height = resultHeight,
            thumbWidth = resultThumbWidth,
            thumbHeight = resultThumbHeight
        )
    }

    private fun readExifRotation(uri: Uri): Int {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val exif = ExifInterface(inputStream)
                when (exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (e: Exception) {
            0 // Если не удалось прочитать EXIF, не применяем ротацию
        }
    }

    private fun applyExifRotation(bitmap: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return bitmap
        val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxPx: Int): Int {
        val maxDim = maxOf(width, height)
        if (maxDim <= maxPx) return 1
        var inSampleSize = 1
        while ((maxDim / (inSampleSize * 2)) >= maxPx) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    private fun scaleBitmap(bitmap: Bitmap, maxPx: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val maxDim = maxOf(width, height)
        if (maxDim <= maxPx) return bitmap

        val scale = maxPx.toFloat() / maxDim
        val newWidth = (width * scale).toInt().coerceAtLeast(1)
        val newHeight = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun compressBitmapToJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    companion object {
        /** Максимальный размер полноразмерного изображения по длинной стороне. */
        const val MAX_FULL_PX = 1920

        /** Максимальный размер миниатюры по длинной стороне. */
        const val MAX_THUMB_PX = 256

        /** Качество JPEG для полноразмерного изображения. */
        const val FULL_QUALITY = 85

        /** Качество JPEG для миниатюры. */
        const val THUMB_QUALITY = 60
    }
}
