package ru.cheburmail.app.crypto

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Генератор QR-кода из публичного ключа и email.
 *
 * QR-payload формат:
 * {"pk":"<base64_pubkey>","email":"user@yandex.ru","v":1}
 */
object QrCodeGenerator {

    private const val QR_SIZE = 512
    private const val PROTOCOL_VERSION = 1

    /**
     * Генерирует Bitmap QR-кода с данными для обмена ключами.
     *
     * @param publicKey публичный ключ (32 байта X25519)
     * @param email email-адрес владельца ключа
     * @param size размер QR-кода в пикселях
     * @return Bitmap с QR-кодом
     */
    fun generate(publicKey: ByteArray, email: String, size: Int = QR_SIZE): Bitmap {
        val payload = createPayload(publicKey, email)
        return generateBitmap(payload, size)
    }

    /**
     * Создаёт JSON-payload для QR-кода.
     */
    fun createPayload(publicKey: ByteArray, email: String): String {
        val pkBase64 = android.util.Base64.encodeToString(
            publicKey,
            android.util.Base64.NO_WRAP
        )
        val data = QrPayload(pk = pkBase64, email = email, v = PROTOCOL_VERSION)
        return Json.encodeToString(data)
    }

    private fun generateBitmap(content: String, size: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )

        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }

        return bitmap
    }

    @kotlinx.serialization.Serializable
    internal data class QrPayload(
        val pk: String,
        val email: String,
        val v: Int
    )
}
