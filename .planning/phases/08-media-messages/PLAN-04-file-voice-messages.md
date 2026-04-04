---
phase: 8
plan: 04
title: "File + Voice messages + Progress indicator"
wave: 2
depends_on: [01, 02]
files_modified:
  - app/src/main/AndroidManifest.xml
  - app/src/main/java/ru/cheburmail/app/media/VoiceRecorder.kt
  - app/src/main/java/ru/cheburmail/app/media/VoicePlayer.kt
  - app/src/main/java/ru/cheburmail/app/media/FileSaver.kt
  - app/src/main/java/ru/cheburmail/app/ui/chat/FileMessageBubble.kt
  - app/src/main/java/ru/cheburmail/app/ui/chat/VoiceMessageBubble.kt
  - app/src/main/java/ru/cheburmail/app/ui/chat/WaveformView.kt
  - app/src/main/java/ru/cheburmail/app/ui/chat/MessageBubble.kt
  - app/src/main/java/ru/cheburmail/app/ui/chat/ChatScreen.kt
  - app/src/main/java/ru/cheburmail/app/ui/chat/ChatViewModel.kt
  - app/src/main/java/ru/cheburmail/app/ui/chat/SendProgressIndicator.kt
autonomous: true
---

# Plan 04: File + Voice Messages + Progress Indicator

## Objective
Enable sending/receiving encrypted arbitrary files (up to 18MB) and voice messages (M4A/AAC). Users pick files via SAF, record voice with long-press on mic button. Recipients can save files to Downloads and play voice messages with a waveform visualization. Add a progress indicator for send/receive operations.

## Tasks

<task id="1" title="Add RECORD_AUDIO permission to AndroidManifest" file="app/src/main/AndroidManifest.xml">
Add the RECORD_AUDIO permission before the `<application>` tag:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

No `<uses-feature>` needed since voice recording is optional functionality.
</task>

<task id="2" title="Create VoiceRecorder wrapper" file="app/src/main/java/ru/cheburmail/app/media/VoiceRecorder.kt">
Create a `MediaRecorder` wrapper for recording AAC/M4A voice messages with amplitude sampling:

```kotlin
package ru.cheburmail.app.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Records voice messages in M4A/AAC format.
 * Samples amplitude during recording to build a waveform visualization.
 *
 * Usage:
 *   val recorder = VoiceRecorder(context)
 *   recorder.start(messageId)
 *   // periodically call recorder.getCurrentAmplitude()
 *   val result = recorder.stop()
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startTimeMs: Long = 0
    private val amplitudes = mutableListOf<Int>()
    private var isRecording = false

    data class RecordingResult(
        val file: File,
        val durationMs: Long,
        /** Waveform amplitudes normalized to 0-100, comma-separated */
        val waveform: String
    )

    companion object {
        private const val TAG = "VoiceRecorder"
        private const val SAMPLE_RATE = 44100
        private const val BIT_RATE = 128000
        /** Target number of waveform bars */
        private const val TARGET_WAVEFORM_BARS = 50
        /** Max amplitude from MediaRecorder.getMaxAmplitude() */
        private const val MAX_AMPLITUDE = 32767
    }

    /**
     * Start recording a voice message.
     *
     * @param messageId used as filename prefix
     * @throws IllegalStateException if already recording
     */
    fun start(messageId: String) {
        check(!isRecording) { "Already recording" }

        val voiceDir = File(context.cacheDir, "media/voice").also { it.mkdirs() }
        outputFile = File(voiceDir, "${messageId}.m4a")

        recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        recorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(SAMPLE_RATE)
            setAudioEncodingBitRate(BIT_RATE)
            setOutputFile(outputFile!!.absolutePath)

            try {
                prepare()
                start()
                isRecording = true
                startTimeMs = System.currentTimeMillis()
                amplitudes.clear()
                Log.d(TAG, "Recording started: ${outputFile!!.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording: ${e.message}", e)
                release()
                recorder = null
                throw e
            }
        }
    }

    /**
     * Sample the current amplitude. Call this periodically (e.g., every 100ms)
     * from a coroutine to build the waveform.
     *
     * @return normalized amplitude 0-100, or 0 if not recording
     */
    fun getCurrentAmplitude(): Int {
        if (!isRecording) return 0
        return try {
            val amp = recorder?.maxAmplitude ?: 0
            val normalized = (amp * 100 / MAX_AMPLITUDE).coerceIn(0, 100)
            amplitudes.add(normalized)
            normalized
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Stop recording and return the result.
     *
     * @return RecordingResult with file, duration, and waveform data
     * @throws IllegalStateException if not recording
     */
    fun stop(): RecordingResult {
        check(isRecording) { "Not recording" }

        val durationMs = System.currentTimeMillis() - startTimeMs

        try {
            recorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recorder: ${e.message}")
        }
        recorder?.release()
        recorder = null
        isRecording = false

        // Downsample waveform to TARGET_WAVEFORM_BARS
        val waveform = downsampleWaveform(amplitudes, TARGET_WAVEFORM_BARS)

        Log.d(TAG, "Recording stopped: ${durationMs}ms, ${outputFile!!.length()} bytes")

        return RecordingResult(
            file = outputFile!!,
            durationMs = durationMs,
            waveform = waveform
        )
    }

    /**
     * Cancel recording without saving.
     */
    fun cancel() {
        if (!isRecording) return
        try {
            recorder?.stop()
        } catch (_: Exception) {}
        recorder?.release()
        recorder = null
        isRecording = false
        outputFile?.delete()
        amplitudes.clear()
    }

    fun isRecording(): Boolean = isRecording

    /**
     * Downsample amplitude list to target number of bars.
     * Returns comma-separated string of integers 0-100.
     */
    private fun downsampleWaveform(samples: List<Int>, targetBars: Int): String {
        if (samples.isEmpty()) return "0"
        if (samples.size <= targetBars) return samples.joinToString(",")

        val barSize = samples.size.toFloat() / targetBars
        val result = mutableListOf<Int>()
        for (i in 0 until targetBars) {
            val start = (i * barSize).toInt()
            val end = ((i + 1) * barSize).toInt().coerceAtMost(samples.size)
            val avg = samples.subList(start, end).average().toInt()
            result.add(avg)
        }
        return result.joinToString(",")
    }
}
```
</task>

<task id="3" title="Create VoicePlayer wrapper" file="app/src/main/java/ru/cheburmail/app/media/VoicePlayer.kt">
Create a `MediaPlayer` wrapper for playing voice messages with progress tracking:

```kotlin
package ru.cheburmail.app.media

import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plays voice messages with progress tracking.
 * Only one voice message can play at a time (singleton pattern).
 */
class VoicePlayer {

    private var mediaPlayer: MediaPlayer? = null
    private var currentMessageId: String? = null

    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    sealed class PlaybackState {
        data object Idle : PlaybackState()
        data class Playing(val messageId: String, val progressMs: Int, val durationMs: Int) : PlaybackState()
        data class Paused(val messageId: String, val progressMs: Int, val durationMs: Int) : PlaybackState()
    }

    companion object {
        private const val TAG = "VoicePlayer"

        @Volatile
        private var INSTANCE: VoicePlayer? = null

        fun getInstance(): VoicePlayer =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: VoicePlayer().also { INSTANCE = it }
            }
    }

    /**
     * Play or resume a voice message.
     *
     * @param messageId unique message identifier (for tracking which message is playing)
     * @param uri local file URI of the M4A voice file
     */
    fun play(messageId: String, uri: String) {
        // If already playing this message, resume
        if (currentMessageId == messageId && mediaPlayer != null) {
            val state = _playbackState.value
            if (state is PlaybackState.Paused) {
                mediaPlayer?.start()
                _playbackState.value = PlaybackState.Playing(
                    messageId, state.progressMs, state.durationMs
                )
                return
            }
        }

        // Stop any current playback
        stop()

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(Uri.parse(uri).path)
                prepare()
                start()
            }
            currentMessageId = messageId

            val duration = mediaPlayer?.duration ?: 0
            _playbackState.value = PlaybackState.Playing(messageId, 0, duration)

            mediaPlayer?.setOnCompletionListener {
                _playbackState.value = PlaybackState.Idle
                currentMessageId = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing voice: ${e.message}", e)
            _playbackState.value = PlaybackState.Idle
        }
    }

    /**
     * Pause the current playback.
     */
    fun pause() {
        val state = _playbackState.value
        if (state is PlaybackState.Playing) {
            mediaPlayer?.pause()
            val progress = mediaPlayer?.currentPosition ?: 0
            _playbackState.value = PlaybackState.Paused(
                state.messageId, progress, state.durationMs
            )
        }
    }

    /**
     * Toggle play/pause.
     */
    fun togglePlayPause(messageId: String, uri: String) {
        when (val state = _playbackState.value) {
            is PlaybackState.Playing -> {
                if (state.messageId == messageId) pause()
                else play(messageId, uri)
            }
            is PlaybackState.Paused -> {
                if (state.messageId == messageId) play(messageId, uri)
                else play(messageId, uri)
            }
            is PlaybackState.Idle -> play(messageId, uri)
        }
    }

    /**
     * Get the current playback position in ms.
     */
    fun getCurrentPosition(): Int = mediaPlayer?.currentPosition ?: 0

    /**
     * Stop and release the player.
     */
    fun stop() {
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {}
        mediaPlayer?.release()
        mediaPlayer = null
        currentMessageId = null
        _playbackState.value = PlaybackState.Idle
    }
}
```
</task>

<task id="4" title="Create FileSaver for saving files to Downloads" file="app/src/main/java/ru/cheburmail/app/media/FileSaver.kt">
Create a utility to save received files to the device Downloads folder:

```kotlin
package ru.cheburmail.app.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Saves files to the device Downloads directory.
 * Uses MediaStore API on Android 10+ (API 29+), direct file access on API 26-28.
 */
class FileSaver(private val context: Context) {

    companion object {
        private const val TAG = "FileSaver"
    }

    /**
     * Save bytes to the Downloads directory.
     *
     * @param fileName the target file name (e.g., "document.pdf")
     * @param mimeType MIME type of the file
     * @param bytes file content
     * @return URI of the saved file, or null on failure
     */
    fun saveToDownloads(fileName: String, mimeType: String, bytes: ByteArray): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(fileName, mimeType, bytes)
        } else {
            saveDirectly(fileName, bytes)
        }
    }

    /**
     * Save a file from an internal URI to Downloads.
     */
    fun saveToDownloads(fileName: String, mimeType: String, sourceUri: String): Uri? {
        val bytes = try {
            val uri = Uri.parse(sourceUri)
            if (uri.scheme == "file") {
                File(uri.path!!).readBytes()
            } else {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading source: ${e.message}")
            return null
        }
        return saveToDownloads(fileName, mimeType, bytes)
    }

    private fun saveViaMediaStore(fileName: String, mimeType: String, bytes: ByteArray): Uri? {
        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CheburMail")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: return null

        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }

            contentValues.clear()
            contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)

            Log.i(TAG, "Saved to Downloads via MediaStore: $fileName")
            return uri
        } catch (e: Exception) {
            Log.e(TAG, "Error saving via MediaStore: ${e.message}", e)
            resolver.delete(uri, null, null)
            return null
        }
    }

    @Suppress("DEPRECATION")
    private fun saveDirectly(fileName: String, bytes: ByteArray): Uri? {
        return try {
            val downloadsDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "CheburMail"
            )
            downloadsDir.mkdirs()

            val file = File(downloadsDir, fileName)
            // Avoid overwriting: append suffix if exists
            val targetFile = if (file.exists()) {
                val name = file.nameWithoutExtension
                val ext = file.extension
                var counter = 1
                var candidate = File(downloadsDir, "${name}_$counter.$ext")
                while (candidate.exists()) {
                    counter++
                    candidate = File(downloadsDir, "${name}_$counter.$ext")
                }
                candidate
            } else file

            targetFile.writeBytes(bytes)
            Log.i(TAG, "Saved to Downloads directly: ${targetFile.absolutePath}")
            Uri.fromFile(targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving directly: ${e.message}", e)
            null
        }
    }
}
```
</task>

<task id="5" title="Create WaveformView composable" file="app/src/main/java/ru/cheburmail/app/ui/chat/WaveformView.kt">
Create a Canvas-based composable for rendering voice message waveforms:

```kotlin
package ru.cheburmail.app.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Waveform visualization for voice messages.
 * Renders a bar chart from comma-separated amplitude values (0-100).
 *
 * @param waveformData comma-separated amplitude values (e.g., "10,25,40,80,55,30")
 * @param progress playback progress 0.0..1.0 (bars before progress are highlighted)
 * @param barColor color for unplayed bars
 * @param playedColor color for played bars (before progress)
 */
@Composable
fun WaveformView(
    waveformData: String,
    progress: Float = 0f,
    barColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
    playedColor: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier
) {
    val amplitudes = remember(waveformData) {
        waveformData.split(",").mapNotNull { it.trim().toIntOrNull() }
    }

    if (amplitudes.isEmpty()) return

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
    ) {
        val barCount = amplitudes.size
        val barWidthPx = size.width / (barCount * 2f - 1f) // bar + gap
        val maxHeight = size.height
        val progressIndex = (progress * barCount).toInt()

        for (i in amplitudes.indices) {
            val amplitude = amplitudes[i].coerceIn(2, 100) // min bar height of 2%
            val barHeight = maxHeight * amplitude / 100f
            val x = i * barWidthPx * 2f
            val y = (maxHeight - barHeight) / 2f

            val color = if (i < progressIndex) playedColor else barColor

            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(barWidthPx, barHeight),
                cornerRadius = CornerRadius(barWidthPx / 2f)
            )
        }
    }
}
```

Add the required import: `import androidx.compose.runtime.remember`.
</task>

<task id="6" title="Create VoiceMessageBubble composable" file="app/src/main/java/ru/cheburmail/app/ui/chat/VoiceMessageBubble.kt" depends_on="3,5">
Create a composable for voice message bubbles with play/pause button and waveform:

```kotlin
package ru.cheburmail.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.cheburmail.app.db.entity.MessageEntity
import ru.cheburmail.app.media.VoicePlayer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bubble for voice messages with play/pause button, waveform, and duration.
 */
@Composable
fun VoiceMessageBubble(
    message: MessageEntity,
    voicePlayer: VoicePlayer,
    modifier: Modifier = Modifier
) {
    val isOutgoing = message.isOutgoing
    val alignment = if (isOutgoing) Arrangement.End else Arrangement.Start
    val bubbleShape = if (isOutgoing) {
        RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    }
    val bubbleColor = if (isOutgoing) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isOutgoing) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val playbackState by voicePlayer.playbackState.collectAsState()
    val isPlaying = playbackState is VoicePlayer.PlaybackState.Playing &&
        (playbackState as VoicePlayer.PlaybackState.Playing).messageId == message.id
    val isPaused = playbackState is VoicePlayer.PlaybackState.Paused &&
        (playbackState as VoicePlayer.PlaybackState.Paused).messageId == message.id

    val progress = when {
        isPlaying -> {
            val state = playbackState as VoicePlayer.PlaybackState.Playing
            if (state.durationMs > 0) state.progressMs.toFloat() / state.durationMs else 0f
        }
        isPaused -> {
            val state = playbackState as VoicePlayer.PlaybackState.Paused
            if (state.durationMs > 0) state.progressMs.toFloat() / state.durationMs else 0f
        }
        else -> 0f
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = alignment
    ) {
        Surface(
            shape = bubbleShape,
            color = bubbleColor,
            modifier = Modifier.widthIn(min = 200.dp, max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            message.localMediaUri?.let { uri ->
                                voicePlayer.togglePlayPause(message.id, uri)
                            }
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        WaveformView(
                            waveformData = message.waveformData ?: "10,10,10",
                            progress = progress,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Duration
                        Text(
                            text = formatDuration(message.voiceDurationMs ?: 0),
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }

                // Timestamp + status
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

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private fun formatTime(timestamp: Long): String = timeFormat.format(Date(timestamp))
```
</task>

<task id="7" title="Create FileMessageBubble composable" file="app/src/main/java/ru/cheburmail/app/ui/chat/FileMessageBubble.kt" depends_on="4">
Create a composable for file message bubbles with filename, size, and save button:

```kotlin
package ru.cheburmail.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.cheburmail.app.db.entity.MessageEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bubble for file messages showing filename, size, and a save-to-Downloads button.
 */
@Composable
fun FileMessageBubble(
    message: MessageEntity,
    onSaveToDownloads: (messageId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isOutgoing = message.isOutgoing
    val alignment = if (isOutgoing) Arrangement.End else Arrangement.Start
    val bubbleShape = if (isOutgoing) {
        RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    }
    val bubbleColor = if (isOutgoing) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isOutgoing) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = alignment
    ) {
        Surface(
            shape = bubbleShape,
            color = bubbleColor,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.InsertDriveFile,
                        contentDescription = "File",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = message.fileName ?: "Unknown file",
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = formatFileSize(message.fileSize ?: 0),
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.6f)
                        )
                    }

                    // Save button (for incoming messages)
                    if (!isOutgoing && message.localMediaUri != null) {
                        IconButton(onClick = { onSaveToDownloads(message.id) }) {
                            Icon(
                                imageVector = Icons.Filled.Download,
                                contentDescription = "Save to Downloads",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // Timestamp + status
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

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private fun formatTime(timestamp: Long): String = timeFormat.format(Date(timestamp))
```
</task>

<task id="8" title="Create SendProgressIndicator composable" file="app/src/main/java/ru/cheburmail/app/ui/chat/SendProgressIndicator.kt">
Create a simple progress indicator shown during media send/receive:

```kotlin
package ru.cheburmail.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Progress indicator bar shown above the message input when sending/receiving media.
 *
 * @param isVisible whether to show the indicator
 * @param label text label (e.g., "Sending image..." or "Receiving file...")
 */
@Composable
fun SendProgressIndicator(
    isVisible: Boolean,
    label: String = "Sending...",
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(visible = isVisible) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LinearProgressIndicator(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
```
</task>

<task id="9" title="Update MessageBubble to dispatch FILE and VOICE types" file="app/src/main/java/ru/cheburmail/app/ui/chat/MessageBubble.kt" depends_on="6,7">
Extend the `MessageBubble` composable to dispatch to `FileMessageBubble` and `VoiceMessageBubble` based on `mediaType`. Note: Plan 03 already adds `onImageClick` and IMAGE dispatch. This task adds FILE and VOICE dispatch.

1. Update the signature to add new callbacks:
```kotlin
@Composable
fun MessageBubble(
    message: MessageEntity,
    onImageClick: (messageId: String) -> Unit = {},
    onSaveFile: (messageId: String) -> Unit = {},
    voicePlayer: VoicePlayer? = null,
    modifier: Modifier = Modifier
)
```

2. Add dispatches after the IMAGE check (added by Plan 03):
```kotlin
if (message.mediaType == MediaType.FILE) {
    FileMessageBubble(
        message = message,
        onSaveToDownloads = onSaveFile,
        modifier = modifier
    )
    return
}

if (message.mediaType == MediaType.VOICE && voicePlayer != null) {
    VoiceMessageBubble(
        message = message,
        voicePlayer = voicePlayer,
        modifier = modifier
    )
    return
}
```

3. Add import: `import ru.cheburmail.app.media.VoicePlayer`
</task>

<task id="10" title="Add file picking and voice recording to ChatViewModel" file="app/src/main/java/ru/cheburmail/app/ui/chat/ChatViewModel.kt" depends_on="2,4">
Add file sending and voice recording capabilities to `ChatViewModel`:

1. Add imports:
```kotlin
import ru.cheburmail.app.media.VoiceRecorder
import ru.cheburmail.app.media.VoicePlayer
import ru.cheburmail.app.media.FileSaver
```

2. Add properties:
```kotlin
private val voiceRecorder by lazy { VoiceRecorder(appContext) }
val voicePlayer: VoicePlayer = VoicePlayer.getInstance()
private val fileSaver by lazy { FileSaver(appContext) }

private val _isRecordingVoice = MutableStateFlow(false)
val isRecordingVoice: StateFlow<Boolean> = _isRecordingVoice.asStateFlow()

private val _isSendingMedia = MutableStateFlow(false)
val isSendingMedia: StateFlow<Boolean> = _isSendingMedia.asStateFlow()

private val _sendingMediaLabel = MutableStateFlow("")
val sendingMediaLabel: StateFlow<String> = _sendingMediaLabel.asStateFlow()
```

3. Add `onFilePicked(uri: Uri)` method:
```kotlin
/**
 * Handle file picked via SAF OpenDocument.
 * Reads bytes, encrypts, queues for send.
 */
fun onFilePicked(uri: Uri) {
    viewModelScope.launch {
        try {
            _isSendingMedia.value = true
            _sendingMediaLabel.value = "Sending file..."

            val email = recipientEmail ?: run {
                Log.e(TAG, "Recipient email not resolved")
                return@launch
            }

            val msgId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val fileName = mediaFileManager.getFileName(uri)
            val fileSize = mediaFileManager.getFileSize(uri)
            val mimeType = mediaFileManager.getMimeType(uri)

            // Check size limit
            if (fileSize > MediaEncryptor.MAX_PAYLOAD_BYTES) {
                Log.e(TAG, "File too large: $fileSize > ${MediaEncryptor.MAX_PAYLOAD_BYTES}")
                // TODO: show user-facing error
                return@launch
            }

            val placeholder = MessageEntity(
                id = msgId, chatId = chatId, isOutgoing = true,
                plaintext = "", status = MessageStatus.SENDING, timestamp = now,
                mediaType = MediaType.FILE, fileName = fileName, fileSize = fileSize,
                mimeType = mimeType, mediaDownloadStatus = MediaDownloadStatus.PENDING
            )
            messageDao.insert(placeholder)

            val existingChat = chatDao.getById(chatId)
            if (existingChat == null) {
                chatDao.insert(ChatEntity(
                    id = chatId, type = ChatType.DIRECT,
                    title = _chatTitle.value, createdAt = now, updatedAt = now
                ))
            }

            withContext(Dispatchers.IO) {
                val bytes = mediaFileManager.readContentUri(uri)
                val localUri = mediaFileManager.saveFile(msgId, fileName, bytes)

                messageDao.updateMedia(
                    id = msgId, localMediaUri = localUri, thumbnailUri = null,
                    fileName = fileName, fileSize = bytes.size.toLong(),
                    mimeType = mimeType, mediaDownloadStatus = MediaDownloadStatus.COMPLETED
                )

                val contact = contactDao.getByEmail(email) ?: run {
                    messageDao.updateStatus(msgId, MessageStatus.FAILED)
                    return@withContext
                }
                val keyPair = keyStorage.getOrCreateKeyPair()
                val ls = CryptoProvider.lazySodium
                val msgEncryptor = MessageEncryptor(ls, NonceGenerator(ls))
                val mediaEncryptor = MediaEncryptor(msgEncryptor)

                val metadata = MediaMetadata(
                    type = MediaMetadata.TYPE_FILE,
                    fileName = fileName,
                    fileSize = bytes.size.toLong(),
                    mimeType = mimeType
                )

                val encrypted = mediaEncryptor.encrypt(
                    metadata, bytes, contact.publicKey, keyPair.getPrivateKey()
                )

                val metaBytes = encrypted.metadataEnvelope.toBytes()
                val payloadBytes = encrypted.payloadEnvelope.toBytes()
                val combined = ByteArray(4 + metaBytes.size + payloadBytes.size)
                combined[0] = (metaBytes.size shr 24).toByte()
                combined[1] = (metaBytes.size shr 16).toByte()
                combined[2] = (metaBytes.size shr 8).toByte()
                combined[3] = metaBytes.size.toByte()
                metaBytes.copyInto(combined, 4)
                payloadBytes.copyInto(combined, 4 + metaBytes.size)

                sendQueueDao.insert(SendQueueEntity(
                    messageId = msgId, recipientEmail = email,
                    encryptedPayload = combined, status = QueueStatus.QUEUED,
                    createdAt = now, updatedAt = now
                ))

                OutboxDrainWorker.enqueue(appContext)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending file: ${e.message}", e)
        } finally {
            _isSendingMedia.value = false
        }
    }
}
```

4. Add voice recording methods:
```kotlin
/**
 * Start recording a voice message.
 */
fun startVoiceRecording() {
    val msgId = UUID.randomUUID().toString()
    try {
        voiceRecorder.start(msgId)
        _isRecordingVoice.value = true

        // Sample amplitude periodically
        viewModelScope.launch {
            while (voiceRecorder.isRecording()) {
                voiceRecorder.getCurrentAmplitude()
                kotlinx.coroutines.delay(100)
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to start voice recording: ${e.message}", e)
    }
}

/**
 * Stop recording and send the voice message.
 */
fun stopVoiceRecordingAndSend() {
    if (!voiceRecorder.isRecording()) return

    viewModelScope.launch {
        try {
            _isSendingMedia.value = true
            _sendingMediaLabel.value = "Sending voice..."
            _isRecordingVoice.value = false

            val result = voiceRecorder.stop()

            val email = recipientEmail ?: run {
                Log.e(TAG, "Recipient email not resolved")
                return@launch
            }

            val msgId = result.file.nameWithoutExtension
            val now = System.currentTimeMillis()
            val voiceBytes = result.file.readBytes()

            val placeholder = MessageEntity(
                id = msgId, chatId = chatId, isOutgoing = true,
                plaintext = "", status = MessageStatus.SENDING, timestamp = now,
                mediaType = MediaType.VOICE,
                localMediaUri = android.net.Uri.fromFile(result.file).toString(),
                fileName = result.file.name,
                fileSize = voiceBytes.size.toLong(),
                mimeType = "audio/mp4",
                voiceDurationMs = result.durationMs,
                waveformData = result.waveform,
                mediaDownloadStatus = MediaDownloadStatus.COMPLETED
            )
            messageDao.insert(placeholder)

            val existingChat = chatDao.getById(chatId)
            if (existingChat == null) {
                chatDao.insert(ChatEntity(
                    id = chatId, type = ChatType.DIRECT,
                    title = _chatTitle.value, createdAt = now, updatedAt = now
                ))
            }

            withContext(Dispatchers.IO) {
                val contact = contactDao.getByEmail(email) ?: run {
                    messageDao.updateStatus(msgId, MessageStatus.FAILED)
                    return@withContext
                }
                val keyPair = keyStorage.getOrCreateKeyPair()
                val ls = CryptoProvider.lazySodium
                val msgEncryptor = MessageEncryptor(ls, NonceGenerator(ls))
                val mediaEncryptor = MediaEncryptor(msgEncryptor)

                val metadata = MediaMetadata(
                    type = MediaMetadata.TYPE_VOICE,
                    fileName = result.file.name,
                    fileSize = voiceBytes.size.toLong(),
                    mimeType = "audio/mp4",
                    durationMs = result.durationMs,
                    waveform = result.waveform
                )

                val encrypted = mediaEncryptor.encrypt(
                    metadata, voiceBytes, contact.publicKey, keyPair.getPrivateKey()
                )

                val metaBytes = encrypted.metadataEnvelope.toBytes()
                val payloadBytes = encrypted.payloadEnvelope.toBytes()
                val combined = ByteArray(4 + metaBytes.size + payloadBytes.size)
                combined[0] = (metaBytes.size shr 24).toByte()
                combined[1] = (metaBytes.size shr 16).toByte()
                combined[2] = (metaBytes.size shr 8).toByte()
                combined[3] = metaBytes.size.toByte()
                metaBytes.copyInto(combined, 4)
                payloadBytes.copyInto(combined, 4 + metaBytes.size)

                sendQueueDao.insert(SendQueueEntity(
                    messageId = msgId, recipientEmail = email,
                    encryptedPayload = combined, status = QueueStatus.QUEUED,
                    createdAt = now, updatedAt = now
                ))

                OutboxDrainWorker.enqueue(appContext)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending voice: ${e.message}", e)
        } finally {
            _isSendingMedia.value = false
        }
    }
}

/**
 * Cancel voice recording without sending.
 */
fun cancelVoiceRecording() {
    voiceRecorder.cancel()
    _isRecordingVoice.value = false
}

/**
 * Save a received file to the Downloads directory.
 */
fun saveFileToDownloads(messageId: String) {
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val message = messageDao.getByIdOnce(messageId) ?: return@launch
            val uri = message.localMediaUri ?: return@launch
            val fileName = message.fileName ?: "file"
            val mimeType = message.mimeType ?: "application/octet-stream"

            val savedUri = fileSaver.saveToDownloads(fileName, mimeType, uri)
            if (savedUri != null) {
                Log.i(TAG, "File saved to Downloads: $fileName")
            } else {
                Log.e(TAG, "Failed to save file to Downloads")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving file: ${e.message}", e)
        }
    }
}
```
</task>

<task id="11" title="Add file picker, mic button, and progress to ChatScreen" file="app/src/main/java/ru/cheburmail/app/ui/chat/ChatScreen.kt" depends_on="8,9,10">
Update `ChatScreen` and `MessageInput` to add file picker, voice recording, and progress indicator:

1. Add imports:
```kotlin
import android.Manifest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Description
import androidx.compose.runtime.DisposableEffect
import ru.cheburmail.app.media.VoicePlayer
```

2. In `ChatScreen`, add the file picker launcher:
```kotlin
val fileLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocument()
) { uri: Uri? ->
    uri?.let { viewModel.onFilePicked(it) }
}
```

3. Add RECORD_AUDIO permission launcher:
```kotlin
val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
) { granted ->
    if (granted) {
        viewModel.startVoiceRecording()
    }
}
```

4. Collect new state flows:
```kotlin
val isRecordingVoice by viewModel.isRecordingVoice.collectAsState()
val isSendingMedia by viewModel.isSendingMedia.collectAsState()
val sendingMediaLabel by viewModel.sendingMediaLabel.collectAsState()
```

5. Add `SendProgressIndicator` above `MessageInput`:
```kotlin
SendProgressIndicator(
    isVisible = isSendingMedia,
    label = sendingMediaLabel
)
```

6. Add a "File" option to the attach dropdown (from Plan 03):
```kotlin
DropdownMenuItem(
    text = { Text("File") },
    onClick = {
        showAttachMenu = false
        fileLauncher.launch(arrayOf("*/*"))
    },
    leadingIcon = { Icon(Icons.Filled.Description, contentDescription = null) }
)
```

7. Update `MessageInput` signature to add voice callbacks:
```kotlin
@Composable
private fun MessageInput(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickFile: () -> Unit,
    isRecordingVoice: Boolean,
    onStartVoiceRecording: () -> Unit,
    onStopVoiceRecording: () -> Unit
)
```

8. Replace the Send button with a conditional Send/Mic button:
- When `text.isNotBlank()`: show Send button (existing behavior)
- When `text.isBlank() && !isRecordingVoice`: show Mic button (long press starts recording)
- When `isRecordingVoice`: show Stop button (tap stops and sends)

```kotlin
if (text.isNotBlank()) {
    IconButton(onClick = onSend) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Send,
            contentDescription = "Send",
            tint = MaterialTheme.colorScheme.primary
        )
    }
} else if (isRecordingVoice) {
    IconButton(onClick = onStopVoiceRecording) {
        Icon(
            imageVector = Icons.Filled.Stop,
            contentDescription = "Stop recording",
            tint = MaterialTheme.colorScheme.error
        )
    }
} else {
    IconButton(onClick = onStartVoiceRecording) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = "Record voice",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
```

9. Update `MessageBubble` call in LazyColumn to pass `onSaveFile` and `voicePlayer`:
```kotlin
MessageBubble(
    message = message,
    onImageClick = { /* full screen viewer */ },
    onSaveFile = { viewModel.saveFileToDownloads(it) },
    voicePlayer = viewModel.voicePlayer,
    modifier = Modifier.padding(vertical = 4.dp)
)
```

10. Dispose voice player on leave:
```kotlin
DisposableEffect(Unit) {
    onDispose {
        viewModel.voicePlayer.stop()
    }
}
```
</task>

## Verification
- [ ] Project compiles with `./gradlew assembleDebug --no-daemon`
- [ ] RECORD_AUDIO permission is requested before voice recording starts
- [ ] File picker opens with SAF and returns file URI
- [ ] File under 18MB is encrypted and queued for send; file over 18MB is rejected
- [ ] FileMessageBubble shows filename and size, save button for incoming
- [ ] FileSaver saves to Downloads/CheburMail/ directory
- [ ] VoiceRecorder produces a valid M4A file with amplitude data
- [ ] VoicePlayer plays M4A files with play/pause toggle
- [ ] WaveformView renders bars from comma-separated data
- [ ] VoiceMessageBubble shows play/pause, waveform, and duration
- [ ] Mic button appears when text input is empty
- [ ] Stop button appears during voice recording
- [ ] SendProgressIndicator shows indeterminate progress during media send
- [ ] Text-only messages still work (send button visible when text is non-empty)

## must_haves
- VoiceRecorder produces AAC/M4A output with waveform amplitude sampling
- VoicePlayer is singleton, supports play/pause/stop with state flow
- WaveformView renders bar chart from "10,25,40,80,55,30" format
- VoiceMessageBubble has play/pause icon, waveform, and duration display
- FileMessageBubble shows filename, file size, and save-to-Downloads button
- FileSaver uses MediaStore on API 29+ and direct file access on API 26-28
- RECORD_AUDIO permission is declared in manifest and requested at runtime
- File size limit of 18MB is enforced before encryption
- Mic/Stop button replaces Send button when text field is empty
- SendProgressIndicator (LinearProgressIndicator) shows during media send/receive
- ChatViewModel exposes voice recording start/stop/cancel methods
- ChatViewModel.onFilePicked() reads SAF URI, encrypts, and queues
- ChatViewModel.saveFileToDownloads() saves received files to Downloads
