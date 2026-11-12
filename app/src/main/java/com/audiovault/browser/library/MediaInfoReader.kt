package com.audiovault.browser.library

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.RandomAccessFile

data class MediaInfo(
    val durationMs: Long,
    val sampleRate: Int?,
    val channels: Int?,
    val bitrate: Int?,
    val hasAudio: Boolean,
    val hasVideo: Boolean,
    val mime: String?,
)

object MediaInfoReader {

    fun read(file: File): MediaInfo {
        WavInfo.read(file)?.let { w ->
            return MediaInfo(w.durationMs, w.sampleRate, w.channels, w.sampleRate * w.channels * w.bitsPerSample, true, false, "audio/wav")
        }
        var duration = 0L
        var sr: Int? = null
        var ch: Int? = null
        var br: Int? = null
        var hasAudio = false
        var hasVideo = false
        var mime: String? = null
        runCatching {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(file.absolutePath)
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (m.startsWith("audio/")) {
                        hasAudio = true
                        if (mime == null) mime = m
                        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (f.containsKey(MediaFormat.KEY_BIT_RATE)) br = f.getInteger(MediaFormat.KEY_BIT_RATE)
                        if (f.containsKey(MediaFormat.KEY_DURATION)) duration = maxOf(duration, f.getLong(MediaFormat.KEY_DURATION) / 1000)
                    } else if (m.startsWith("video/")) hasVideo = true
                }
            } finally {
                ex.release()
            }
        }
        if (duration <= 0) {
            runCatching {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(file.absolutePath)
                    duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                    if (br == null) br = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
                    if (!hasAudio) hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                } finally {
                    r.release()
                }
            }
        }
        return MediaInfo(duration, sr, ch, br, hasAudio, hasVideo, mime)
    }
}

/** Lectura mínima de cabecera WAV/RIFF (PCM). */
data class WavInfo(val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val dataOffset: Long, val dataSize: Long) {
    val durationMs: Long get() {
        val bps = sampleRate.toLong() * channels * (bitsPerSample / 8)
        return if (bps > 0) dataSize * 1000 / bps else 0
    }

    companion object {
        fun read(file: File): WavInfo? = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 44) return null
                val hdr = ByteArray(12)
                raf.readFully(hdr)
                if (String(hdr, 0, 4, Charsets.US_ASCII) != "RIFF" || String(hdr, 8, 4, Charsets.US_ASCII) != "WAVE") return null
                var sr = 0; var ch = 0; var bits = 0
                var pos = 12L
                while (pos + 8 <= raf.length()) {
                    raf.seek(pos)
                    val id = ByteArray(4); raf.readFully(id)
                    val size = Integer.reverseBytes(raf.readInt()).toLong() and 0xFFFFFFFFL
                    val sid = String(id, Charsets.US_ASCII)
                    if (sid == "fmt ") {
                        val fmt = ByteArray(16); raf.readFully(fmt)
                        ch = (fmt[2].toInt() and 0xFF) or ((fmt[3].toInt() and 0xFF) shl 8)
                        sr = (fmt[4].toInt() and 0xFF) or ((fmt[5].toInt() and 0xFF) shl 8) or
                            ((fmt[6].toInt() and 0xFF) shl 16) or ((fmt[7].toInt() and 0xFF) shl 24)
                        bits = (fmt[14].toInt() and 0xFF) or ((fmt[15].toInt() and 0xFF) shl 8)
                    } else if (sid == "data") {
                        val avail = raf.length() - (pos + 8)
                        val real = if (size == 0L || size > avail) avail else size
                        return WavInfo(sr, ch, bits, pos + 8, real)
                    }
                    pos += 8 + size + (size and 1)
                }
                null
            }
        }.getOrNull()
    }
}
