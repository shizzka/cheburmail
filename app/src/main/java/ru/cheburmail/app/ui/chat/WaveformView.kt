package ru.cheburmail.app.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Canvas-based виджет формы волны для голосового сообщения.
 * @param waveform строка значений 0-9, разделённых запятой (50 баров)
 * @param progress значение 0..1 — какая доля баров считается "проигранной"
 * @param playedColor цвет проигранных баров
 * @param pendingColor цвет ещё не проигранных баров
 */
@Composable
fun WaveformView(
    waveform: String,
    progress: Float = 0f,
    playedColor: Color = Color(0xFF4CAF50),
    pendingColor: Color = Color(0xFFBDBDBD),
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .height(32.dp)
) {
    val bars = parseWaveform(waveform)
    Canvas(modifier = modifier) {
        val count = bars.size.coerceAtLeast(1)
        val totalPadding = (count - 1) * 2f
        val barWidth = ((size.width - totalPadding) / count).coerceAtLeast(2f)
        val playedBars = (progress * count).toInt()

        bars.forEachIndexed { index, amplitude ->
            val barHeightFraction = (amplitude / 9f).coerceIn(0.05f, 1f)
            val barHeight = size.height * barHeightFraction
            val x = index * (barWidth + 2f)
            val top = (size.height - barHeight) / 2f
            val color = if (index < playedBars) playedColor else pendingColor
            drawRoundRect(
                color = color,
                topLeft = Offset(x, top),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

private fun parseWaveform(waveform: String): List<Int> {
    if (waveform.isBlank()) return List(50) { 3 }
    return waveform.split(",").mapNotNull { it.trim().toIntOrNull()?.coerceIn(0, 9) }
        .ifEmpty { List(50) { 3 } }
}
