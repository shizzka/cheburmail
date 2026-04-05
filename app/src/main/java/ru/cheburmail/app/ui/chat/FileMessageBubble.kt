package ru.cheburmail.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ru.cheburmail.app.db.entity.MessageEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bubble сообщения с прикреплённым файлом.
 * Для входящих отображает кнопку «Сохранить».
 */
@Composable
fun FileMessageBubble(
    message: MessageEntity,
    textColor: Color,
    onSaveFile: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.AttachFile,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message.fileName ?: "Файл",
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                    maxLines = 2
                )
                if (message.fileSize != null && message.fileSize > 0) {
                    Text(
                        text = formatFileSize(message.fileSize),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.6f)
                    )
                }
            }

            // Кнопка сохранения для входящих
            if (!message.isOutgoing && onSaveFile != null) {
                IconButton(
                    onClick = { onSaveFile(message.id) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = "Сохранить",
                        tint = textColor
                    )
                }
            }
        }

        // Подпись + время + статус
        Row(
            modifier = Modifier
                .align(Alignment.End)
                .padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatTimestamp(message.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.6f)
            )
            if (message.isOutgoing) {
                Spacer(modifier = Modifier.width(4.dp))
                MessageStatusIcon(status = message.status)
            }
        }
    }
}

/** Форматирует размер файла в человекочитаемый вид: байты / KB / MB / GB */
fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes Б"
        bytes < 1024 * 1024 -> "%.1f КБ".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f МБ".format(bytes / (1024.0 * 1024))
        else -> "%.1f ГБ".format(bytes / (1024.0 * 1024 * 1024))
    }
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private fun formatTimestamp(timestamp: Long) = timeFormat.format(Date(timestamp))
