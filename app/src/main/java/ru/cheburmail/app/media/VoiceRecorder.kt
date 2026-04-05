package ru.cheburmail.app.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Результат записи голосового сообщения.
 */
data class RecordingResult(
    val file: File,
    val durationMs: Long,
    /** Амплитуды нормализованные 0-9, 50 значений, разделённые запятой */
    val waveform: String
)

/**
 * Обёртка над MediaRecorder для записи голосовых сообщений в формате M4A/AAC.
 * Использование: start() → getCurrentAmplitude() (опционально) → stop() или cancel().
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startTimeMs: Long = 0L
    private val amplitudeSamples = mutableListOf<Int>()
    private var samplingJob: Thread? = null

    /**
     * Начать запись. Создаёт временный файл в cacheDir.
     * @param messageId идентификатор сообщения для именования файла
     */
    fun start(messageId: String) {
        cancel()
        val dir = File(context.cacheDir, "voice").apply { mkdirs() }
        val file = File(dir, "voice_$messageId.m4a")
        outputFile = file
        amplitudeSamples.clear()

        @Suppress("DEPRECATION")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
        rec.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(128_000)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        recorder = rec
        startTimeMs = System.currentTimeMillis()

        // Sampling thread — collect amplitude every 100ms
        samplingJob = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(100L)
                    val amp = recorder?.maxAmplitude ?: break
                    amplitudeSamples.add(amp)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Amplitude sampling error: ${e.message}")
                    break
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    /**
     * Текущая амплитуда (0..32767).
     */
    fun getCurrentAmplitude(): Int = recorder?.maxAmplitude ?: 0

    /**
     * Остановить запись и вернуть результат.
     */
    fun stop(): RecordingResult? {
        samplingJob?.interrupt()
        samplingJob = null
        val rec = recorder ?: return null
        val file = outputFile ?: return null
        val durationMs = System.currentTimeMillis() - startTimeMs
        return try {
            rec.stop()
            rec.release()
            recorder = null
            val waveform = downsampleToWaveform(amplitudeSamples, bars = 50)
            RecordingResult(file = file, durationMs = durationMs, waveform = waveform)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recorder: ${e.message}")
            rec.release()
            recorder = null
            null
        }
    }

    /**
     * Отменить запись и удалить временный файл.
     */
    fun cancel() {
        samplingJob?.interrupt()
        samplingJob = null
        try {
            recorder?.stop()
        } catch (_: Exception) {}
        try {
            recorder?.release()
        } catch (_: Exception) {}
        recorder = null
        outputFile?.delete()
        outputFile = null
        amplitudeSamples.clear()
    }

    private fun downsampleToWaveform(samples: List<Int>, bars: Int): String {
        if (samples.isEmpty()) return "0".repeat(bars)
        val maxAmp = samples.max().coerceAtLeast(1)
        val result = mutableListOf<Int>()
        val bucketSize = (samples.size.toFloat() / bars).coerceAtLeast(1f)
        for (i in 0 until bars) {
            val from = (i * bucketSize).toInt()
            val to = ((i + 1) * bucketSize).toInt().coerceAtMost(samples.size)
            val bucket = if (from < to) samples.subList(from, to) else listOf(0)
            val avg = bucket.average()
            val normalized = ((avg / maxAmp) * 9).toInt().coerceIn(0, 9)
            result.add(normalized)
        }
        return result.joinToString(",")
    }

    companion object {
        private const val TAG = "VoiceRecorder"
    }
}
