package com.audiovault.browser.download

import com.audiovault.browser.detector.MediaKind
import com.audiovault.browser.detector.MediaUrlClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamParsersTest {
    @Test fun hlsMaestraEligeAudio() {
        val m = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Español",DEFAULT=YES,URI="audio/es.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,CODECS="avc1.4d401f,mp4a.40.2",AUDIO="aud"
            video/low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,CODECS="avc1.4d401f,mp4a.40.2",AUDIO="aud"
            video/high.m3u8
        """.trimIndent()
        assertTrue(HlsParser.isMaster(m))
        val master = HlsParser.parseMaster(m, "https://cdn.example.com/x/master.m3u8")
        assertEquals(2, master.variants.size)
        val (uri, audioOnly) = HlsParser.chooseAudioPlaylist(master)!!
        assertEquals("https://cdn.example.com/x/audio/es.m3u8", uri)
        assertTrue(audioOnly)
    }

    @Test fun hlsVarianteSoloAudio() {
        val m = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=64000,CODECS=\"mp4a.40.5\"\na64.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS=\"mp4a.40.2\"\na128.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=900000,CODECS=\"avc1.42e01e,mp4a.40.2\"\nv.m3u8\n"
        val (uri, audio) = HlsParser.chooseAudioPlaylist(HlsParser.parseMaster(m, "https://h/p/m.m3u8"))!!
        assertEquals("https://h/p/a128.m3u8", uri); assertTrue(audio)
    }

    @Test fun hlsMediaYCifrado() {
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:6.0,
            seg1.m4s
            #EXTINF:4.5,
            seg2.m4s?token=abc
            #EXT-X-ENDLIST
        """.trimIndent()
        val p = HlsParser.parseMedia(media, "https://h/a/list.m3u8")
        assertEquals(2, p.segments.size)
        assertEquals("https://h/a/init.mp4", p.mapUri)
        assertEquals("https://h/a/seg2.m4s?token=abc", p.segments[1].uri)
        assertEquals(10.5, p.totalDurationSec, 0.001)
        assertTrue(p.ended); assertFalse(p.encrypted)
        val enc = HlsParser.parseMedia("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:5,\na.ts\n", "https://h/l.m3u8")
        assertTrue(enc.encrypted)
        val none = HlsParser.parseMedia("#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:5,\na.ts\n", "https://h/l.m3u8")
        assertFalse(none.encrypted); assertFalse(none.ended)
    }

    @Test fun hlsByteRange() {
        val p = HlsParser.parseMedia("#EXTM3U\n#EXTINF:5,\n#EXT-X-BYTERANGE:1000@0\nall.aac\n#EXTINF:5,\n#EXT-X-BYTERANGE:500\nall.aac\n#EXT-X-ENDLIST", "https://h/l.m3u8")
        assertEquals(0L until 1000L, p.segments[0].byteRange)
        assertEquals(1000L until 1500L, p.segments[1].byteRange)
    }

    @Test fun dashPlantillaConLineaTemporal() {
        val mpd = """<?xml version="1.0"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT10S">
              <Period><BaseURL>https://cdn.example.com/v/</BaseURL>
                <AdaptationSet contentType="video" mimeType="video/mp4"><Representation id="v1" bandwidth="900000"/></AdaptationSet>
                <AdaptationSet contentType="audio" mimeType="audio/mp4">
                  <SegmentTemplate timescale="1000" initialization="${'$'}RepresentationID${'$'}/init.mp4" media="${'$'}RepresentationID${'$'}/${'$'}Time${'$'}.m4s">
                    <SegmentTimeline><S t="0" d="4000" r="1"/><S d="2000"/></SegmentTimeline>
                  </SegmentTemplate>
                  <Representation id="a64" bandwidth="64000" codecs="mp4a.40.2"/>
                  <Representation id="a128" bandwidth="128000" codecs="mp4a.40.2"/>
                </AdaptationSet>
              </Period>
            </MPD>"""
        val p = DashParser.parse(mpd, "https://site/manifest.mpd")!!
        assertTrue(p.audioOnly); assertFalse(p.isProtected)
        assertEquals("https://cdn.example.com/v/a128/init.mp4", p.initUrl)
        assertEquals(listOf("a128/0.m4s", "a128/4000.m4s", "a128/8000.m4s").map { "https://cdn.example.com/v/$it" }, p.segmentUrls)
    }

    @Test fun dashNumeroYDuracion() {
        val mpd = """<MPD mediaPresentationDuration="PT0H0M9.5S"><Period>
            <AdaptationSet mimeType="audio/webm"><Representation id="o" bandwidth="1">
            <SegmentTemplate duration="4" timescale="1" startNumber="1" media="seg-${'$'}Number%03d${'$'}.webm" initialization="init.webm"/>
            </Representation></AdaptationSet></Period></MPD>"""
        val p = DashParser.parse(mpd, "https://h/d/m.mpd")!!
        assertEquals(listOf("seg-001.webm", "seg-002.webm", "seg-003.webm").map { "https://h/d/$it" }, p.segmentUrls)
        assertEquals("audio/webm", p.mime)
    }

    @Test fun dashProtegido() {
        val mpd = """<MPD><Period><AdaptationSet contentType="audio">
            <ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"/>
            <Representation id="a" bandwidth="1"><BaseURL>a.mp4</BaseURL></Representation></AdaptationSet></Period></MPD>"""
        assertTrue(DashParser.parse(mpd, "https://h/m.mpd")!!.isProtected)
    }

    @Test fun duracionIso() {
        assertEquals(3723.5, DashParser.parseDuration("PT1H2M3.5S")!!, 0.0001)
        assertNull(DashParser.parseDuration(""))
    }

    @Test fun clasificadorDeUrls() {
        assertEquals(MediaKind.AUDIO_FILE, MediaUrlClassifier.classify("https://x.com/a/b/voz.mp3?sig=1"))
        assertEquals(MediaKind.HLS, MediaUrlClassifier.classify("https://x.com/live/master.m3u8"))
        assertEquals(MediaKind.DASH, MediaUrlClassifier.classify("https://x.com/m.mpd"))
        assertEquals(MediaKind.AUDIO_FILE, MediaUrlClassifier.classify("https://x.com/api/tts", "audio/aac"))
        assertNull(MediaUrlClassifier.classify("https://x.com/page.html"))
    }
}
