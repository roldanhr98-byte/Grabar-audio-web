package com.audiovault.browser.download

import java.net.URL

/** Analizador mínimo de listas HLS (RFC 8216) para audio sin cifrar. */
object HlsParser {
    data class Variant(val uri: String, val bandwidth: Long, val codecs: String?, val audioGroup: String?)
    data class Rendition(val type: String, val groupId: String?, val uri: String?, val name: String?, val isDefault: Boolean)
    data class Master(val variants: List<Variant>, val renditions: List<Rendition>)
    data class Segment(val uri: String, val durationSec: Double, val byteRange: LongRange?)
    data class Media(
        val segments: List<Segment>,
        val mapUri: String?,
        val mapByteRange: LongRange?,
        val encryptionMethod: String?,
        val ended: Boolean,
    ) {
        val encrypted: Boolean get() = encryptionMethod != null && encryptionMethod != "NONE"
        val totalDurationSec: Double get() = segments.sumOf { it.durationSec }
    }

    fun isMaster(text: String) = text.contains("#EXT-X-STREAM-INF")

    fun resolve(base: String, uri: String): String = runCatching { URL(URL(base), uri).toString() }.getOrDefault(uri)

    /** Atributos tipo KEY=VALUE,KEY="VALUE, con comas" */
    fun attributes(line: String): Map<String, String> {
        val s = line.substringAfter(':', "")
        val out = HashMap<String, String>()
        var i = 0
        while (i < s.length) {
            val eq = s.indexOf('=', i)
            if (eq < 0) break
            val key = s.substring(i, eq).trim()
            var j = eq + 1
            val value: String
            if (j < s.length && s[j] == '"') {
                val end = s.indexOf('"', j + 1).let { if (it < 0) s.length else it }
                value = s.substring(j + 1, end)
                j = end + 1
            } else {
                val end = s.indexOf(',', j).let { if (it < 0) s.length else it }
                value = s.substring(j, end)
                j = end
            }
            out[key] = value
            i = if (j < s.length && s[j] == ',') j + 1 else j + 1
        }
        return out
    }

    fun parseMaster(text: String, baseUrl: String): Master {
        val variants = ArrayList<Variant>()
        val renditions = ArrayList<Rendition>()
        val lines = text.lines().map { it.trim() }
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                val a = attributes(l)
                var j = i + 1
                while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) j++
                if (j < lines.size) {
                    variants += Variant(resolve(baseUrl, lines[j]), a["BANDWIDTH"]?.toLongOrNull() ?: 0, a["CODECS"], a["AUDIO"])
                }
                i = j
            } else if (l.startsWith("#EXT-X-MEDIA:")) {
                val a = attributes(l)
                renditions += Rendition(a["TYPE"] ?: "", a["GROUP-ID"], a["URI"]?.let { resolve(baseUrl, it) }, a["NAME"], a["DEFAULT"] == "YES")
            }
            i++
        }
        return Master(variants, renditions)
    }

    private fun parseByteRange(v: String, prevEnd: Long): LongRange? {
        val len = v.substringBefore('@').toLongOrNull() ?: return null
        val off = if (v.contains('@')) v.substringAfter('@').toLongOrNull() ?: prevEnd else prevEnd
        return off until off + len
    }

    fun parseMedia(text: String, baseUrl: String): Media {
        val segs = ArrayList<Segment>()
        var dur = 0.0
        var range: LongRange? = null
        var lastEnd = 0L
        var map: String? = null
        var mapRange: LongRange? = null
        var enc: String? = null
        var ended = false
        for (raw in text.lines()) {
            val l = raw.trim()
            when {
                l.isEmpty() -> Unit
                l.startsWith("#EXTINF:") -> dur = l.removePrefix("#EXTINF:").substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                l.startsWith("#EXT-X-BYTERANGE:") -> range = parseByteRange(l.removePrefix("#EXT-X-BYTERANGE:"), lastEnd)
                l.startsWith("#EXT-X-KEY:") -> {
                    val m = attributes(l)["METHOD"]
                    if (m != null && m != "NONE") enc = m
                }
                l.startsWith("#EXT-X-MAP:") -> {
                    val a = attributes(l)
                    map = a["URI"]?.let { resolve(baseUrl, it) }
                    mapRange = a["BYTERANGE"]?.let { parseByteRange(it, 0) }
                }
                l.startsWith("#EXT-X-ENDLIST") -> ended = true
                l.startsWith("#") -> Unit
                else -> {
                    segs += Segment(resolve(baseUrl, l), dur, range)
                    range?.let { lastEnd = it.last + 1 }
                    range = null
                    dur = 0.0
                }
            }
        }
        return Media(segs, map, mapRange, enc, ended)
    }

    /** Elige la lista de solo audio si existe; si no, la variante de mayor calidad. */
    fun chooseAudioPlaylist(master: Master): Pair<String, Boolean>? {
        val audio = master.renditions.filter { it.type == "AUDIO" && it.uri != null }
        val pick = audio.firstOrNull { it.isDefault } ?: audio.firstOrNull()
        if (pick != null) return pick.uri!! to true
        val audioOnly = master.variants.filter { v ->
            val c = v.codecs?.lowercase() ?: return@filter false
            c.split(',').all { it.trim().startsWith("mp4a") || it.trim().startsWith("opus") || it.trim().startsWith("ac-3") || it.trim().startsWith("ec-3") || it.trim() == "mp3" }
        }
        audioOnly.maxByOrNull { it.bandwidth }?.let { return it.uri to true }
        return master.variants.maxByOrNull { it.bandwidth }?.let { it.uri to false }
    }
}
