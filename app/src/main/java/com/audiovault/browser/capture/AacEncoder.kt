package com.audiovault.browser.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.audiovault.browser.library.WavInfo
import java.io.File
import java.io.RandomAccessFile

/** Conversión opcional WAV → M4A (AAC-LC) con MediaCodec + MediaMuxer, sin servicios externos. */
object AacEncoder {

    fun encode(wav: File, out: File, bitrate: Int): Boolean {
        val info = WavInfo.read(wav) ?: return false
        if (info.bitsPerSample != 16) return false
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, info.sampleRate, info.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var muxer: MediaMuxer? = null
        var ok = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            RandomAccessFile(wav, "r").use { raf ->
                raf.seek(info.dataOffset)
                var remaining = info.dataSize
                val frameBytes = 2 * info.channels
                var frames = 0L
                var inputDone = false
                var track = -1
                var started = false
                val bi = MediaCodec.BufferInfo()
                val tmp = ByteArray(64 * 1024)
                while (true) {
                    if (!inputDone) {
                        val idx = codec.dequeueInputBuffer(10_000)
                        if (idx >= 0) {
                            val buf = codec.getInputBuffer(idx)!!
                            buf.clear()
                            val want = minOf(buf.capacity().toLong(), remaining, tmp.size.toLong()).toInt()
                            val aligned = want - want % frameBytes
                            val n = if (aligned > 0) raf.read(tmp, 0, aligned) else -1
                            val pts = frames * 1_000_000L / info.sampleRate
                            if (n <= 0) {
                                codec.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                buf.put(tmp, 0, n)
                                codec.queueInputBuffer(idx, 0, n, pts, 0)
                                remaining -= n
                                frames += n / frameBytes
                            }
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(bi, 10_000)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            started = true
                        }
                        outIdx >= 0 -> {
                            val ob = codec.getOutputBuffer(outIdx)!!
                            if (bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) bi.size = 0
                            if (bi.size > 0 && started) {
                                ob.position(bi.offset)
                                ob.limit(bi.offset + bi.size)
                                muxer.writeSampleData(track, ob, bi)
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
                ok = started
            }
        } catch (t: Throwable) {
            ok = false
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
        if (!ok) out.delete()
        return ok && out.length() > 0
    }
}
