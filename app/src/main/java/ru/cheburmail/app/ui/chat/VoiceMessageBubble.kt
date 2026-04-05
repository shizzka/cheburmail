package ru.cheburmail.app.ui.chat

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ru.cheburmail.app.db.MessageStatus
import ru.cheburmail.app.db.entity.MessageEntity
import ru.cheburmail.app.media.PlaybackState
import ru.cheburmail.app.media.VoicePlayer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bubble голосового сообщения с кнопкой воспроизведения, waveform и длительностью.
 */
@Composable
fun VoiceMessageBubble(
    message: MessageEntity,
    voicePlayer: VoicePlayer?,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    val playbackState by (voicePlayer?.state?.collectAsState() ?: return)

    val isThisPlaying = playbackState is PlaybackState.Playing &&
        (playbackState as PlaybackState.Playing).messageId == message.id
    val isThisPaused = playbackState is PlaybackState.Paused &&
        (playbackState as PlaybackState.Paused).messageId == message.id

    val progress: Float = when {
        isThisPlaying -> {
            val s = playbackState as PlaybackState.Playing
            if (s.durationMs > 0) s.progressMs.toFloat() / s.durationMs else 0f
        }
        isThisPaused -> {
            val s = playbackState as PlaybackState.Paused
            if (s.durationMs > 0) s.progressMs.toFloat() / s.durationMs else 0f
        }
        else -> 0f
    }

    val displayDurationMs: Long = when {
        isThisPlaying -> (playbackState as PlaybackState.Playing).durationMs.toLong()
        isThisPaused -> (playbackState as PlaybackState.Paused).durationMs.toLong()
        else -> message.voiceDurationMs ?: 0L
    }

    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(
                onClick = {
                    val uri = message.localMediaUri?.let { Uri.parse(it) }
                    if (uri != null) {
                        if (isThisPlaying) {
                            voicePlayer?.pause()
                        } else {
                            voicePlayer?.play(message.id, uri)
                        }
                    }
                },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = if (isThisPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isThisPlaying) "Пауза" else "Воспроизвести",
                    tint = textColor
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            WaveformView(
                waveform = message.waveformData ?: "",
                progress = progress,
                playedColor = MaterialTheme.colorScheme.primary,
                pendingColor = textColor.copy(alpha = 0.4f),
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp)
            )
        }

        Row(
            modifier = Modifier.align(Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatDuration(displayDurationMs),
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.width(6.dp))
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

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private fun formatTimestamp(timestamp: Long) = timeFormat.format(Date(timestamp))
