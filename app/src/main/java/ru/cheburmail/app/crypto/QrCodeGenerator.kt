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
 * - v=1 (legacy): {"pk":"<base64_pubkey>","email":"user@yandex.ru","v":1}
 * - v=2: {"pk":"...","email":"...","aliases":["a@x","b@y"],"v":2}
 *
 * Всегда сериализуем v=2 при наличии алиасов; парсер v=1 продолжает работать
 * (aliases отсутствуют → пустой список).
 */
object QrCodeGenerator {

    private const val QR_SIZE = 512
    private const val PROTOCOL_VERSION_V1 = 1
    private const val PROTOCOL_VERSION_V2 = 2

    /**
     * Генерирует Bitmap QR-кода с данными для обмена ключами.
     *
     * @param publicKey публичный ключ (32 байта X25519)
     * @param email primary email-адрес владельца ключа
     * @param aliases дополнительные email-алиасы той же identity (другие
     *   аккаунты юзера). Пустой список → формат v1.
     * @param size размер QR-кода в пикселях
     */
    fun generate(
        publicKey: ByteArray,
        email: String,
        aliases: List<String> = emptyList(),
        size: Int = QR_SIZE
    ): Bitmap {
        val payload = createPayload(publicKey, email, aliases)
        return generateBitmap(payload, size)
    }

    /**
     * Создаёт JSON-payload для QR-кода.
     */
    fun createPayload(
        publicKey: ByteArray,
        email: String,
        aliases: List<String> = emptyList()
    ): String {
        val pkBase64 = android.util.Base64.encodeToString(
            publicKey,
            android.util.Base64.NO_WRAP
        )
        val cleanAliases = aliases
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals(email, ignoreCase = true) }
            .distinctBy { it.lowercase() }
        val data = if (cleanAliases.isEmpty()) {
            QrPayload(pk = pkBase64, email = email, v = PROTOCOL_VERSION_V1)
        } else {
            QrPayload(pk = pkBase64, email = email, v = PROTOCOL_VERSION_V2, aliases = cleanAliases)
        }
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
        val v: Int,
        val aliases: List<String>? = null
    )
}
