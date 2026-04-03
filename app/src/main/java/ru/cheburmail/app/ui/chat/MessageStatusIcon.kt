package ru.cheburmail.app.ui.chat

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.cheburmail.app.db.MessageStatus

/**
 * Иконка статуса сообщения.
 * - SENDING: часы (ожидание отправки)
 * - SENT: одна галочка (отправлено на сервер)
 * - DELIVERED: двойная галочка (доставлено получателю)
 * - FAILED: красный крестик (ошибка отправки)
 * - RECEIVED: не отображается (входящее сообщение)
 */
@Composable
fun MessageStatusIcon(
    status: MessageStatus,
    modifier: Modifier = Modifier
) {
    val iconSize = modifier.then(Modifier.size(14.dp))

    when (status) {
        MessageStatus.SENDING -> {
            Icon(
                imageVector = Icons.Default.AccessTime,
                contentDescription = "Отправляется",
                modifier = iconSize,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        MessageStatus.SENT -> {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Отправлено",
                modifier = iconSize,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        MessageStatus.DELIVERED -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = "Доставлено",
                modifier = iconSize,
                tint = MaterialTheme.colorScheme.primary
            )
        }

        MessageStatus.FAILED -> {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Ошибка отправки",
                modifier = iconSize,
                tint = MaterialTheme.colorScheme.error
            )
        }

        MessageStatus.RECEIVED -> {
            // Не показываем иконку для входящих сообщений
        }
    }
}
