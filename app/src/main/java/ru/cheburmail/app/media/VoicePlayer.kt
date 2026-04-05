package ru.cheburmail.app.media

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Состояние воспроизведения голосового сообщения.
 */
sealed class PlaybackState {
    object Idle : PlaybackState()
    data class Playing(
        val messageId: String,
        val progressMs: Int,
        val durationMs: Int
    ) : PlaybackState()
    data class Paused(
        val messageId: String,
        val progressMs: Int,
        val durationMs: Int
    ) : PlaybackState()
}

/**
 * Синглтон-обёртка над MediaPlayer для воспроизведения голосовых сообщений.
 * Только одно сообщение может играть одновременно.
 */
class VoicePlayer(private val context: Context) {

    private var player: MediaPlayer? = null
    private var currentMessageId: String? = null
    private var progressThread: Thread? = null

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /**
     * Начать воспроизведение. Если уже играет другое сообщение — остановить его.
     * Если то же самое — пауза/возобновление.
     */
    fun play(messageId: String, uri: Uri) {
        val current = currentMessageId
        if (current == messageId) {
            togglePlayPause()
            return
        }
        // Stop previous
        stopInternal()

        try {
            val mp = MediaPlayer().apply {
                setDataSource(context, uri)
                prepare()
            }
            player = mp
            currentMessageId = messageId

            val duration = mp.duration.coerceAtLeast(0)
            mp.setOnCompletionListener {
                _state.value = PlaybackState.Idle
                stopInternal()
            }
            mp.start()
            _state.value = PlaybackState.Playing(
                messageId = messageId,
                progressMs = 0,
                durationMs = duration
            )
            startProgressThread(messageId, duration)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing voice: ${e.message}")
            stopInternal()
        }
    }

    /** Поставить на паузу. */
    fun pause() {
        val mp = player ?: return
        val msgId = currentMessageId ?: return
        try {
            if (mp.isPlaying) {
                mp.pause()
                progressThread?.interrupt()
                progressThread = null
                _state.value = PlaybackState.Paused(
                    messageId = msgId,
                    progressMs = mp.currentPosition,
                    durationMs = mp.duration
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing: ${e.message}")
        }
    }

    /** Переключить между воспроизведением и паузой. */
    fun togglePlayPause() {
        val mp = player ?: return
        val msgId = currentMessageId ?: return
        try {
            if (mp.isPlaying) {
                pause()
            } else {
                mp.start()
                val duration = mp.duration
                _state.value = PlaybackState.Playing(
                    messageId = msgId,
                    progressMs = mp.currentPosition,
                    durationMs = duration
                )
                startProgressThread(msgId, duration)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling playback: ${e.message}")
        }
    }

    /** Полностью остановить воспроизведение. */
    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        progressThread?.interrupt()
        progressThread = null
        try {
            player?.stop()
        } catch (_: Exception) {}
        try {
            player?.release()
        } catch (_: Exception) {}
        player = null
        currentMessageId = null
        _state.value = PlaybackState.Idle
    }

    private fun startProgressThread(messageId: String, durationMs: Int) {
        progressThread?.interrupt()
        progressThread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(200L)
                    val mp = player ?: break
                    if (!mp.isPlaying) break
                    val pos = mp.currentPosition
                    _state.value = PlaybackState.Playing(
                        messageId = messageId,
                        progressMs = pos,
                        durationMs = durationMs
                    )
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Progress thread error: ${e.message}")
                    break
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    companion object {
        private const val TAG = "VoicePlayer"
    }
}
