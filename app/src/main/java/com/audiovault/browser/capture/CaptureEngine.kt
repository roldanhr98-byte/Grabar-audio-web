package com.audiovault.browser.capture

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import com.audiovault.browser.data.db.CaptureSource
import com.audiovault.browser.data.settings.CaptureScope

/**
 * Lee PCM 16-bit de un [AudioRecord] en un hilo dedicado.
 * - Audio interno: AudioRecord + AudioPlaybackCaptureConfiguration (MediaProjection).
 * - Micrófono: AudioRecord con MediaRecorder.AudioSource.MIC (modo independiente).
 * Nunca se mezclan las dos fuentes.
 */
class CaptureEngine private constructor(
    private val record: AudioRecord,
    val source: CaptureSource,
    val sampleRate: Int,
    val channels: Int,
    private val sink: (ByteArray, Int) -> Unit,
    private val onError: (String) -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread({ loop() }, "AudioVault-Capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val chunk = (sampleRate / 10) * channels * 2 // 100 ms
        val buf = ByteArray(chunk)
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                onError("El sistema no permitió iniciar la grabación (AudioRecord no está grabando).")
                return
            }
            while (running) {
                val n = record.read(buf, 0, buf.size)
                if (n > 0) sink(buf, n)
                else if (n < 0) {
                    onError("Error de lectura de AudioRecord: $n")
                    break
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "capture loop", t)
            onError("Error de captura: ${t.message}")
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
        }
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
    }

    companion object {
        private const val TAG = "CaptureEngine"

        private fun format(sampleRate: Int, channels: Int) = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO)
            .build()

        private fun bufferSize(sampleRate: Int, channels: Int): Int {
            val min = AudioRecord.getMinBufferSize(
                sampleRate,
                if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            return maxOf(min, sampleRate * channels * 2 / 2) * 2 // ≥ 1 s de margen
        }

        @SuppressLint("MissingPermission")
        @RequiresApi(Build.VERSION_CODES.Q)
        fun playback(
            projection: MediaProjection,
            scope: CaptureScope,
            sampleRate: Int,
            channels: Int,
            sink: (ByteArray, Int) -> Unit,
            onError: (String) -> Unit,
        ): Result<CaptureEngine> = runCatching {
            val builder = AudioPlaybackCaptureConfiguration.Builder(projection)
            when (scope) {
                CaptureScope.APP_ONLY -> builder.addMatchingUid(Process.myUid())
                CaptureScope.ALL_APPS -> {
                    builder.addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    builder.addMatchingUsage(AudioAttributes.USAGE_GAME)
                    builder.addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                }
            }
            val rec = AudioRecord.Builder()
                .setAudioFormat(format(sampleRate, channels))
                .setBufferSizeInBytes(bufferSize(sampleRate, channels))
                .setAudioPlaybackCaptureConfig(builder.build())
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                error("AudioRecord no se pudo inicializar para captura interna (formato $sampleRate Hz / $channels canales no admitido o captura bloqueada).")
            }
            CaptureEngine(rec, CaptureSource.INTERNAL, sampleRate, channels, sink, onError)
        }

        @SuppressLint("MissingPermission")
        fun microphone(
            sampleRate: Int,
            channels: Int,
            sink: (ByteArray, Int) -> Unit,
            onError: (String) -> Unit,
        ): Result<CaptureEngine> = runCatching {
            val rec = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(format(sampleRate, channels))
                .setBufferSizeInBytes(bufferSize(sampleRate, channels))
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                error("No se pudo abrir el micrófono.")
            }
            CaptureEngine(rec, CaptureSource.MICROPHONE, sampleRate, channels, sink, onError)
        }
    }
}
