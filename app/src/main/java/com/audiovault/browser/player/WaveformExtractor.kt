package com.audiovault.browser.player

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.audiovault.browser.library.WavInfo
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import kotlin.math.abs

/** Calcula picos normalizados (0..1) para dibujar la forma de onda. */
object WaveformExtractor {

    fun extract(file: File, bars: Int = 160): FloatArray? {
        WavInfo.read(file)?.let { return fromWav(file, it, bars) }
        return runCatching { fromCodec(file, bars) }.getOrNull()
    }

    private fun fromWav(file: File, info: WavInfo, bars: Int): FloatArray? {
        if (info.bitsPerSample != 16 || info.dataSize <= 0) return null
        val frameBytes = 2 * info.channels
        val totalFrames = info.dataSize / frameBytes
        val framesPerBar = maxOf(1L, totalFrames / bars)
        val out = FloatArray(bars)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(info.dataOffset)
            val buf = ByteArray((framesPerBar * frameBytes).coerceAtMost(1 shl 20).toInt())
            for (b in 0 until bars) {
                val start = info.dataOffset + b * framesPerBar * frameBytes
                if (start >= info.dataOffset + info.dataSize) break
                raf.seek(start)
                val n = raf.read(buf)
                if (n <= 0) break
                var peak = 0
                var i = 0
                // Muestreo con paso para no leer todo en archivos grandes.
                val step = maxOf(2, (n / 4096) and 0x7FFFFFFE)
                while (i + 1 < n) {
                    val s = abs(((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt())
                    if (s > peak) peak = s
                    i += step
                }
                out[b] = peak / 32768f
            }
        }
        return normalize(out)
    }

    private fun fromCodec(file: File, bars: Int): FloatArray? {
        val ex = MediaExtractor()
        ex.setDataSource(file.absolutePath)
        var track = -1
        var format: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; format = f; break }
        }
        if (track < 0 || format == null) { ex.release(); return null }
        ex.selectTrack(track)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
        val peaks = ArrayList<Float>()
        var curPeak = 0f
        var curCount = 0
        val samplesPerPoint = 2048
        val info = MediaCodec.BufferInfo()
        var inDone = false
        var isFloat = false
        val deadline = System.currentTimeMillis() + 20_000
        try {
            while (System.currentTimeMillis() < deadline) {
                if (!inDone) {
                    val idx = codec.dequeueInputBuffer(5000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(idx, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 5000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (o >= 0) {
                    val ob = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    if (isFloat) {
                        val fb = ob.asFloatBuffer()
                        while (fb.hasRemaining()) {
                            val v = abs(fb.get()); if (v > curPeak) curPeak = v
                            if (++curCount >= samplesPerPoint) { peaks += curPeak; curPeak = 0f; curCount = 0 }
                        }
                    } else {
                        val sb = ob.asShortBuffer()
                        while (sb.hasRemaining()) {
                            val v = abs(sb.get().toInt()) / 32768f; if (v > curPeak) curPeak = v
                            if (++curCount >= samplesPerPoint) { peaks += curPeak; curPeak = 0f; curCount = 0 }
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { codec.stop() }; runCatching { codec.release() }; ex.release()
        }
        if (curCount > 0) peaks += curPeak
        if (peaks.isEmpty()) return null
        val out = FloatArray(bars)
        for (b in 0 until bars) {
            val from = b * peaks.size / bars
            val to = maxOf(from + 1, (b + 1) * peaks.size / bars)
            var m = 0f
            for (k in from until minOf(to, peaks.size)) if (peaks[k] > m) m = peaks[k]
            out[b] = m
        }
        return normalize(out)
    }

    private fun normalize(a: FloatArray): FloatArray {
        val max = a.maxOrNull() ?: 0f
        if (max <= 0f) return a
        return FloatArray(a.size) { (a[it] / max).coerceIn(0f, 1f) }
    }
}

object WaveformCache {
    fun fileFor(ctx: Context, id: Long) = File(File(ctx.filesDir, "waveforms").apply { mkdirs() }, "$id.bin")

    fun load(ctx: Context, id: Long): FloatArray? = runCatching {
        DataInputStream(fileFor(ctx, id).inputStream().buffered()).use { inp ->
            val n = inp.readInt(); FloatArray(n) { inp.readFloat() }
        }
    }.getOrNull()

    fun save(ctx: Context, id: Long, data: FloatArray) {
        runCatching {
            DataOutputStream(fileFor(ctx, id).outputStream().buffered()).use { out ->
                out.writeInt(data.size); data.forEach { out.writeFloat(it) }
            }
        }
    }
}
