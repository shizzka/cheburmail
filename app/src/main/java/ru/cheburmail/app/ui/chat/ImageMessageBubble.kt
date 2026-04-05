package ru.cheburmail.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import ru.cheburmail.app.db.MediaDownloadStatus
import ru.cheburmail.app.db.entity.MessageEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bubble для сообщений с изображением.
 * Отображает миниатюру (thumbnailUri или localMediaUri), заголовок загрузки, подпись и метку времени.
 * При нажатии вызывает onImageClick с путём к полноразмерному изображению.
 */
@Composable
fun ImageMessageBubble(
    message: MessageEntity,
    onImageClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isOutgoing = message.isOutgoing
    val alignment = if (isOutgoing) Arrangement.End else Arrangement.Start

    val bubbleShape = if (isOutgoing) {
        RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    }

    val isNew = !isOutgoing && message.status == ru.cheburmail.app.db.MessageStatus.RECEIVED

    val bubbleColor = if (isOutgoing) {
        MaterialTheme.colorScheme.primaryContainer
    } else if (isNew) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    val textColor = if (isOutgoing) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else if (isNew) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val isLoading = message.mediaDownloadStatus == MediaDownloadStatus.PENDING ||
        message.mediaDownloadStatus == MediaDownloadStatus.DOWNLOADING

    // Источник изображения для миниатюры
    val thumbnailSource = message.thumbnailUri ?: message.localMediaUri

    // Полноразмерный путь для просмотра
    val fullImagePath = message.localMediaUri

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = alignment
    ) {
        Surface(
            shape = bubbleShape,
            color = bubbleColor,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(
                modifier = Modifier.padding(8.dp)
            ) {
                // Изображение или индикатор загрузки
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = fullImagePath != null && !isLoading) {
                            fullImagePath?.let { onImageClick(it) }
                        }
                ) {
                    if (isLoading || thumbnailSource == null) {
                        // Показываем placeholder во время загрузки
                        Box(
                            modifier = Modifier
                                .size(200.dp, 150.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(32.dp),
                                    color = textColor.copy(alpha = 0.7f),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    } else {
                        val painter = rememberAsyncImagePainter(
                            model = if (thumbnailSource.startsWith("/")) {
                                File(thumbnailSource)
                            } else {
                                thumbnailSource
                            }
                        )

                        val painterState = painter.state

                        AsyncImage(
                            model = if (thumbnailSource.startsWith("/")) {
                                File(thumbnailSource)
                            } else {
                                thumbnailSource
                            },
                            contentDescription = "Изображение",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .widthIn(min = 120.dp, max = 280.dp)
                                .height(200.dp)
                        )

                        // Показываем спиннер пока изображение загружается Coil
                        if (painterState is AsyncImagePainter.State.Loading) {
                            Box(
                                modifier = Modifier
                                    .size(280.dp, 200.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(32.dp),
                                    color = textColor.copy(alpha = 0.7f),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    }
                }

                // Подпись к изображению (если есть)
                val caption = message.plaintext.ifBlank { null }
                if (caption != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }

                // Время и статус
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.6f)
                    )

                    if (isOutgoing) {
                        Spacer(modifier = Modifier.width(4.dp))
                        MessageStatusIcon(status = message.status)
                    }
                }
            }
        }
    }
}

private val imageTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun formatTime(timestamp: Long): String {
    return imageTimeFormat.format(Date(timestamp))
}
