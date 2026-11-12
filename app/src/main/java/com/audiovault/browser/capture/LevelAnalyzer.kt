package com.audiovault.browser.capture

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

data class AudioLevel(
    /** RMS en dBFS (-120 = silencio digital). */
    val rmsDb: Float,
    val peak: Int,
    /** Frecuencia estimada por cruces por cero del canal 0 (Hz). */
    val zeroCrossHz: Float,
    val durationMs: Long,
) {
    val isDigitalSilence: Boolean get() = peak == 0
}

object LevelAnalyzer {
    /** Analiza PCM 16-bit little-endian intercalado. */
    fun analyze(buf: ByteArray, len: Int, sampleRate: Int, channels: Int): AudioLevel {
        val samples = len / 2
        if (samples == 0) return AudioLevel(-120f, 0, 0f, 0)
        var sumSq = 0.0
        var peak = 0
        var crossings = 0
        var prev = 0
        var i = 0
        var idx = 0
        while (i + 1 < len) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
            sumSq += s.toDouble() * s
            val a = abs(s)
            if (a > peak) peak = a
            if (idx % channels == 0) {
                if ((prev < 0 && s >= 0) || (prev >= 0 && s < 0)) crossings++
                prev = s
            }
            i += 2
            idx++
        }
        val rms = sqrt(sumSq / samples)
        val db = if (rms < 1e-9) -120f else (20 * log10(rms / 32768.0)).toFloat()
        val frames = samples / channels
        val durMs = frames * 1000L / sampleRate
        val hz = if (frames > 0) crossings / 2f * sampleRate / frames else 0f
        return AudioLevel(db.coerceAtLeast(-120f), peak, hz, durMs)
    }
}

/** Búfer circular de PCM para conservar el audio justo anterior al inicio detectado. */
class PreRollBuffer(capacityBytes: Int) {
    private var buf = ByteArray(capacityBytes.coerceAtLeast(2))
    private var start = 0
    private var size = 0

    @Synchronized
    fun resize(capacityBytes: Int) {
        buf = ByteArray(capacityBytes.coerceAtLeast(2)); start = 0; size = 0
    }

    @Synchronized
    fun push(data: ByteArray, len: Int) {
        val cap = buf.size
        if (len >= cap) {
            System.arraycopy(data, len - cap, buf, 0, cap)
            start = 0; size = cap
            return
        }
        for (k in 0 until len) {
            val pos = (start + size) % cap
            buf[pos] = data[k]
            if (size < cap) size++ else start = (start + 1) % cap
        }
    }

    /** Devuelve el contenido y vacía el búfer. */
    @Synchronized
    fun drain(frameAlign: Int): ByteArray {
        var n = size - size % frameAlign
        val out = ByteArray(n)
        val skip = size - n
        for (k in 0 until n) out[k] = buf[(start + skip + k) % buf.size]
        start = 0; size = 0
        return out
    }
}
