package ru.cheburmail.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.io.File

/**
 * Полноэкранный просмотр изображения.
 * Чёрный фон, изображение по центру (fit), кнопка закрытия в правом верхнем углу.
 *
 * @param imagePath абсолютный путь к изображению
 * @param onClose   колбэк закрытия просмотра
 */
@Composable
fun FullScreenImageViewer(
    imagePath: String,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // Полноразмерное изображение
        AsyncImage(
            model = File(imagePath),
            contentDescription = "Полноразмерное изображение",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        // Кнопка закрытия в правом верхнем углу
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Закрыть",
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}
