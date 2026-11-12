package com.audiovault.browser.capture

import com.audiovault.browser.library.WavInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.sin

class WavWriterTest {
    private fun tone(frames: Int, ch: Int, sr: Int, hz: Double): ByteArray {
        val b = ByteArray(frames * ch * 2)
        for (i in 0 until frames) {
            val v = (sin(2 * PI * hz * i / sr) * 12000).toInt()
            for (c in 0 until ch) { val o = (i * ch + c) * 2; b[o] = (v and 0xFF).toByte(); b[o + 1] = (v shr 8).toByte() }
        }
        return b
    }

    @Test fun escribeCabeceraValida() {
        val f = File.createTempFile("avtest", ".wav")
        WavWriter(f, 48000, 2).use { w -> w.write(tone(48000, 2, 48000, 440.0)) }
        val info = WavInfo.read(f)!!
        assertEquals(48000, info.sampleRate); assertEquals(2, info.channels); assertEquals(16, info.bitsPerSample)
        assertEquals(48000L * 4, info.dataSize); assertEquals(1000L, info.durationMs)
        f.delete()
    }

    @Test fun recuperaTrasCierreInesperado() {
        val f = File.createTempFile("avtest", ".wav.part")
        val w = WavWriter(f, 44100, 1)
        w.write(tone(44100 * 3, 1, 44100, 1000.0)) // 3 s; la cabecera se actualizó por el camino
        // Simula muerte del proceso: no se llama a close(); se añaden bytes sueltos
        RandomAccessFile(f, "rw").use { it.seek(it.length()); it.write(byteArrayOf(1, 2, 3)) }
        val data = WavWriter.repair(f, 44100, 1)
        assertEquals(44100L * 3 * 2 + 2, data) // alineado a frame (2 bytes)
        val info = WavInfo.read(f)!!
        assertEquals(data, info.dataSize)
        assertTrue(info.durationMs >= 3000)
        f.delete()
    }

    @Test fun recortaSilencioFinal() {
        val f = File.createTempFile("avtest", ".wav")
        val w = WavWriter(f, 8000, 1)
        w.write(tone(8000, 1, 8000, 400.0)); w.write(ByteArray(16000))
        w.truncateData(16000 + 1)
        w.close()
        assertEquals(16000L, WavInfo.read(f)!!.dataSize)
        f.delete()
    }

    @Test fun continuaEnModoAnexar() {
        val f = File.createTempFile("avtest", ".wav")
        WavWriter(f, 8000, 1).use { it.write(ByteArray(800)) }
        WavWriter(f, 8000, 1, append = true).use { it.write(ByteArray(800)) }
        assertEquals(1600L, WavInfo.read(f)!!.dataSize)
        f.delete()
    }

    @Test fun preRollConservaUltimosBytes() {
        val p = PreRollBuffer(8)
        p.push(byteArrayOf(1, 2, 3, 4, 5, 6), 6)
        p.push(byteArrayOf(7, 8, 9, 10), 4)
        assertArrayEquals(byteArrayOf(3, 4, 5, 6, 7, 8, 9, 10), p.drain(2))
        assertEquals(0, p.drain(2).size)
    }

    @Test fun analizadorDetectaFrecuenciaYNivel() {
        val pcm = tone(4800, 2, 48000, 1000.0)
        val l = LevelAnalyzer.analyze(pcm, pcm.size, 48000, 2)
        assertTrue("hz=${l.zeroCrossHz}", l.zeroCrossHz in 950f..1050f)
        assertTrue("db=${l.rmsDb}", l.rmsDb in -12f..-8f)
        assertEquals(100L, l.durationMs)
        val silence = LevelAnalyzer.analyze(ByteArray(960), 960, 48000, 1)
        assertTrue(silence.isDigitalSilence)
    }
}
