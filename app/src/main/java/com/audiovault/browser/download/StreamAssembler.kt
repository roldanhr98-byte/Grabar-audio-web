package com.audiovault.browser.download

import java.io.File
import java.io.IOException

/**
 * Ensambla flujos HLS / DASH sin cifrar concatenando segmentos en su formato original
 * (TS, AAC empaquetado o MP4 fragmentado / WebM). No descifra contenido protegido.
 */
class StreamAssembler(private val http: HttpFetcher) {

    data class Assembled(val file: File, val ext: String, val mime: String, val audioOnly: Boolean, val complete: Boolean, val note: String?)

    class NotAllowed(msg: String) : IOException(msg)

    fun hls(url: String, referer: String?, workDir: File, onProgress: (Float) -> Unit): Assembled {
        var (finalUrl, text) = http.fetchText(url, referer)
        var audioOnly = true
        if (HlsParser.isMaster(text)) {
            val master = HlsParser.parseMaster(text, finalUrl)
            val (pick, onlyAudio) = HlsParser.chooseAudioPlaylist(master) ?: throw IOException("Lista maestra sin variantes")
            audioOnly = onlyAudio
            val r = http.fetchText(pick, referer)
            finalUrl = r.first; text = r.second
        }
        val media = HlsParser.parseMedia(text, finalUrl)
        if (media.encrypted) throw NotAllowed("El flujo HLS está cifrado (${media.encryptionMethod}); AudioVault no descifra contenido protegido. Usa la captura de reproducción si el sitio lo permite.")
        if (media.segments.isEmpty()) throw IOException("Lista HLS sin segmentos")
        val firstExt = com.audiovault.browser.detector.MediaUrlClassifier.extension(media.segments.first().uri)
        val ext = when {
            media.mapUri != null -> if (audioOnly) "m4a" else "mp4"
            firstExt == "aac" -> "aac"
            firstExt == "mp3" -> "mp3"
            else -> "ts"
        }
        val out = File(workDir, "hls_${System.currentTimeMillis()}.$ext")
        out.delete()
        if (media.mapUri != null) http.download(media.mapUri, out, referer, range = media.mapByteRange, append = true)
        media.segments.forEachIndexed { i, seg ->
            http.download(seg.uri, out, referer, range = seg.byteRange, append = true)
            onProgress((i + 1f) / media.segments.size)
        }
        val mime = when (ext) { "m4a" -> "audio/mp4"; "mp4" -> "video/mp4"; "aac" -> "audio/aac"; "mp3" -> "audio/mpeg"; else -> "video/mp2t" }
        val note = if (!media.ended) "Lista en directo: se guardó lo disponible en ese momento" else null
        return Assembled(out, ext, mime, audioOnly, media.ended, note)
    }

    fun dash(url: String, referer: String?, workDir: File, onProgress: (Float) -> Unit): Assembled {
        val (finalUrl, xml) = http.fetchText(url, referer)
        val plan = DashParser.parse(xml, finalUrl) ?: throw IOException("Manifiesto DASH no compatible")
        if (plan.isProtected) throw NotAllowed("El manifiesto DASH declara ContentProtection (DRM); no se descarga.")
        if (plan.segmentUrls.isEmpty()) throw IOException("Manifiesto sin segmentos")
        val webm = plan.mime.contains("webm")
        val ext = if (webm) "webm" else if (plan.audioOnly) "m4a" else "mp4"
        val out = File(workDir, "dash_${System.currentTimeMillis()}.$ext")
        out.delete()
        plan.initUrl?.let { http.download(it, out, referer, append = true) }
        plan.segmentUrls.forEachIndexed { i, s ->
            http.download(s, out, referer, append = true)
            onProgress((i + 1f) / plan.segmentUrls.size)
        }
        val mime = if (webm) (if (plan.audioOnly) "audio/webm" else "video/webm") else (if (plan.audioOnly) "audio/mp4" else "video/mp4")
        return Assembled(out, ext, mime, plan.audioOnly, !plan.isLive, if (plan.isLive) "Manifiesto en directo" else null)
    }
}
