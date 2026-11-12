package com.audiovault.browser.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryLogicTest {
    @Test fun identificaSitios() {
        assertEquals("Claude", SiteIdentifier.identify("https://claude.ai/chat/abc").folder)
        assertEquals("ChatGPT", SiteIdentifier.identify("https://chatgpt.com/c/123").folder)
        assertEquals("ChatGPT", SiteIdentifier.identify("https://chat.openai.com/").folder)
        val el = SiteIdentifier.identify("https://elevenlabs.io/app/speech-synthesis")
        assertEquals("ElevenLabs", el.folder); assertEquals("generacion", el.filePrefix)
        assertEquals("example.org", SiteIdentifier.identify("https://www.example.org/x").folder)
    }

    @Test fun nombreVisible() {
        val n = SiteIdentifier.displayName(SiteIdentifier.identify("https://claude.ai"), 1_794_000_000_000L)
        assertTrue(n, n.startsWith("CLAUDE - Lectura de texto - "))
        assertTrue(Regex("\\d{2}/\\d{2}/\\d{4}$").containsMatchIn(n))
    }

    @Test fun numeracionSecuencial() {
        assertEquals("lectura_001.wav", SiteIdentifier.nextFileName("lectura", "wav", emptyList()))
        assertEquals("lectura_003.wav", SiteIdentifier.nextFileName("lectura", "wav", listOf("lectura_001.wav", "lectura_002.mp3", "otro.wav")))
        assertEquals("generacion_011.mp3", SiteIdentifier.nextFileName("generacion", "mp3", listOf("generacion_010.wav")))
    }

    private fun b(vararg x: Int) = ByteArray(x.size) { x[it].toByte() }
    private fun ascii(s: String, pad: Int = 32) = (s.toByteArray() + ByteArray(pad))

    @Test fun validacionDeFormatos() {
        assertNull(AudioFormatSniffer.sniff(ascii("<!DOCTYPE html><html>")))
        assertNull(AudioFormatSniffer.sniff(ascii("{\"error\":\"unauthorized\"}")))
        assertTrue(AudioFormatSniffer.looksLikeText(ascii("   <html>")))
        assertEquals("mp3", AudioFormatSniffer.sniff(ascii("ID3") + b(4, 0, 0))!!.ext)
        assertEquals("mp3", AudioFormatSniffer.sniff(b(0xFF, 0xFB, 0x90, 0x64, 0, 0, 0, 0))!!.ext)
        assertEquals("aac", AudioFormatSniffer.sniff(b(0xFF, 0xF1, 0x50, 0x80, 0, 0, 0, 0))!!.ext)
        assertEquals("wav", AudioFormatSniffer.sniff(ascii("RIFF", 0) + b(0, 0, 0, 0) + ascii("WAVE"))!!.ext)
        assertEquals("ogg", AudioFormatSniffer.sniff(ascii("OggS"))!!.ext)
        assertEquals("opus", AudioFormatSniffer.sniff(ascii("OggS", 0) + ByteArray(24) + ascii("OpusHead"))!!.ext)
        assertEquals("m4a", AudioFormatSniffer.sniff(b(0, 0, 0, 0x20) + ascii("ftypM4A "))!!.ext)
        assertEquals("mp4", AudioFormatSniffer.sniff(b(0, 0, 0, 0x20) + ascii("ftypisom"))!!.ext)
        assertEquals("m4a", AudioFormatSniffer.sniff(b(0, 0, 0, 0x20) + ascii("ftypiso6"), "audio/mp4")!!.ext)
        val webm = AudioFormatSniffer.sniff(b(0x1A, 0x45, 0xDF, 0xA3, 1, 2, 3, 4), "audio/webm;codecs=opus")!!
        assertEquals("webm", webm.ext); assertTrue(webm.isAudioOnly)
        assertNull(AudioFormatSniffer.sniff(ByteArray(0)))
    }

    @Test fun extensionPorMime() {
        assertEquals("mp3", AudioFormatSniffer.extForMime("audio/mpeg"))
        assertEquals("wav", AudioFormatSniffer.extForMime("audio/x-wav"))
        assertEquals("m4a", AudioFormatSniffer.extForMime("audio/mp4; codecs=mp4a.40.2"))
        assertNull(AudioFormatSniffer.extForMime("text/html"))
    }
}
