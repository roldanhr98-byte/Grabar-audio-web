package com.audiovault.browser.capture

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * Escritor WAV PCM 16-bit en streaming, pensado para no perder audio:
 * - La cabecera se escribe al principio y se actualiza periódicamente.
 * - Si el proceso muere, [repair] reconstruye la cabecera desde el tamaño real.
 */
class WavWriter(
    val file: File,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int = 16,
    append: Boolean = false,
) : Closeable {

    private val raf = RandomAccessFile(file, "rw")
    var dataBytes: Long = 0
        private set
    private var bytesSinceHeader = 0L
    private var closed = false

    val bytesPerSecond: Int get() = sampleRate * channels * bitsPerSample / 8
    val durationMs: Long get() = dataBytes * 1000 / bytesPerSecond

    init {
        if (append && raf.length() >= HEADER_SIZE) {
            dataBytes = raf.length() - HEADER_SIZE
            raf.seek(raf.length())
        } else {
            raf.setLength(0)
            writeHeader(raf, sampleRate, channels, bitsPerSample, 0)
            raf.seek(HEADER_SIZE.toLong())
        }
    }

    @Synchronized
    fun write(buf: ByteArray, off: Int = 0, len: Int = buf.size) {
        check(!closed) { "WavWriter cerrado" }
        // Mantener alineación de frame.
        val frame = channels * bitsPerSample / 8
        val aligned = len - (len % frame)
        if (aligned <= 0) return
        raf.write(buf, off, aligned)
        dataBytes += aligned
        bytesSinceHeader += aligned
        if (bytesSinceHeader >= bytesPerSecond * 2L) {
            updateHeader(sync = false)
        }
    }

    /** Actualiza tamaños de cabecera; con [sync] fuerza escritura física. */
    @Synchronized
    fun updateHeader(sync: Boolean) {
        if (closed) return
        val pos = raf.filePointer
        patchSizes(raf, dataBytes)
        raf.seek(pos)
        bytesSinceHeader = 0
        if (sync) runCatching { raf.fd.sync() }
    }

    /** Recorta los datos de audio a [newDataBytes] (p. ej. para quitar silencio final). */
    @Synchronized
    fun truncateData(newDataBytes: Long) {
        val frame = channels * bitsPerSample / 8
        val target = (newDataBytes - newDataBytes % frame).coerceIn(0, dataBytes)
        if (target == dataBytes) return
        raf.setLength(HEADER_SIZE + target)
        dataBytes = target
        raf.seek(HEADER_SIZE + target)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        patchSizes(raf, dataBytes)
        runCatching { raf.fd.sync() }
        raf.close()
        closed = true
    }

    companion object {
        const val HEADER_SIZE = 44

        fun header(sampleRate: Int, channels: Int, bits: Int, dataLen: Long): ByteArray {
            val byteRate = sampleRate * channels * bits / 8
            val blockAlign = channels * bits / 8
            val b = ByteArray(HEADER_SIZE)
            fun s(o: Int, str: String) = str.forEachIndexed { i, c -> b[o + i] = c.code.toByte() }
            fun i32(o: Int, v: Long) { for (k in 0..3) b[o + k] = ((v shr (8 * k)) and 0xFF).toByte() }
            fun i16(o: Int, v: Int) { b[o] = (v and 0xFF).toByte(); b[o + 1] = ((v shr 8) and 0xFF).toByte() }
            val riff = (36 + dataLen).coerceAtMost(0xFFFFFFFFL)
            s(0, "RIFF"); i32(4, riff); s(8, "WAVE")
            s(12, "fmt "); i32(16, 16); i16(20, 1); i16(22, channels)
            i32(24, sampleRate.toLong()); i32(28, byteRate.toLong()); i16(32, blockAlign); i16(34, bits)
            s(36, "data"); i32(40, dataLen.coerceAtMost(0xFFFFFFFFL))
            return b
        }

        fun writeHeader(raf: RandomAccessFile, sr: Int, ch: Int, bits: Int, dataLen: Long) {
            raf.seek(0)
            raf.write(header(sr, ch, bits, dataLen))
        }

        private fun patchSizes(raf: RandomAccessFile, dataLen: Long) {
            fun le(v: Long) = ByteArray(4) { k -> ((v shr (8 * k)) and 0xFF).toByte() }
            raf.seek(4); raf.write(le((36 + dataLen).coerceAtMost(0xFFFFFFFFL)))
            raf.seek(40); raf.write(le(dataLen.coerceAtMost(0xFFFFFFFFL)))
        }

        /**
         * Repara la cabecera de un WAV que no se cerró correctamente.
         * @return bytes de datos de audio válidos, o -1 si el archivo no es reparable.
         */
        fun repair(file: File, sampleRate: Int, channels: Int, bits: Int = 16): Long {
            if (!file.exists() || file.length() < HEADER_SIZE) return -1
            RandomAccessFile(file, "rw").use { raf ->
                val frame = channels * bits / 8
                var data = raf.length() - HEADER_SIZE
                data -= data % frame
                raf.setLength(HEADER_SIZE + data)
                writeHeader(raf, sampleRate, channels, bits, data)
                runCatching { raf.fd.sync() }
                return data
            }
        }
    }
}
