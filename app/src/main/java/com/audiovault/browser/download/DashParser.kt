package com.audiovault.browser.download

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.ceil

/**
 * Analizador DASH (MPD) estático para la pista de audio de mayor calidad.
 * Admite BaseURL, SegmentBase, SegmentList y SegmentTemplate (con o sin SegmentTimeline).
 * Si hay ContentProtection (DRM) se marca como protegido y no se descarga.
 */
object DashParser {
    data class Plan(
        val initUrl: String?,
        val segmentUrls: List<String>,
        val mime: String,
        val codecs: String?,
        val isProtected: Boolean,
        val isLive: Boolean,
        val durationSec: Double?,
        val audioOnly: Boolean,
    )

    fun parse(xml: String, mpdUrl: String): Plan? {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        val doc = f.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))
        val mpd = doc.documentElement
        val isLive = mpd.getAttribute("type") == "dynamic"
        val totalSec = parseDuration(mpd.getAttribute("mediaPresentationDuration"))
        val period = children(mpd, "Period").firstOrNull() ?: return null
        val periodDur = parseDuration(period.getAttribute("duration")) ?: totalSec
        var base = resolveBase(mpdUrl, mpd)
        base = resolveBase(base, period)

        val sets = children(period, "AdaptationSet")
        val audioSets = sets.filter { isAudio(it) }
        val chosenSet = audioSets.firstOrNull() ?: sets.firstOrNull() ?: return null
        val audioOnly = audioSets.isNotEmpty()
        val reps = children(chosenSet, "Representation")
        val rep = reps.maxByOrNull { it.getAttribute("bandwidth").toLongOrNull() ?: 0 } ?: return null
        val prot = children(chosenSet, "ContentProtection").isNotEmpty() || children(rep, "ContentProtection").isNotEmpty()
        val mime = rep.getAttribute("mimeType").ifBlank { chosenSet.getAttribute("mimeType") }.ifBlank { "audio/mp4" }
        val codecs = rep.getAttribute("codecs").ifBlank { chosenSet.getAttribute("codecs") }.ifBlank { null }
        val setBase = resolveBase(base, chosenSet)
        val repBase = resolveBase(setBase, rep)
        val repId = rep.getAttribute("id")
        val bandwidth = rep.getAttribute("bandwidth")

        val template = children(rep, "SegmentTemplate").firstOrNull() ?: children(chosenSet, "SegmentTemplate").firstOrNull()
        val list = children(rep, "SegmentList").firstOrNull() ?: children(chosenSet, "SegmentList").firstOrNull()

        if (template != null) {
            val media = template.getAttribute("media")
            val init = template.getAttribute("initialization").ifBlank { null }
            val startNumber = template.getAttribute("startNumber").toLongOrNull() ?: 1
            val timescale = template.getAttribute("timescale").toLongOrNull() ?: 1
            val urls = ArrayList<String>()
            val timeline = children(template, "SegmentTimeline").firstOrNull()
            if (timeline != null) {
                var t = 0L
                var number = startNumber
                val endT = periodDur?.let { (it * timescale).toLong() }
                for (s in children(timeline, "S")) {
                    s.getAttribute("t").toLongOrNull()?.let { t = it }
                    val d = s.getAttribute("d").toLongOrNull() ?: continue
                    var r = s.getAttribute("r").toLongOrNull() ?: 0
                    if (r < 0) r = if (endT != null && d > 0) ceil((endT - t).toDouble() / d).toLong() - 1 else 0
                    for (k in 0..r) {
                        urls += URL(URL(repBase), fill(media, repId, number, t, bandwidth)).toString()
                        t += d; number++
                        if (urls.size > MAX_SEGMENTS) break
                    }
                }
            } else {
                val dur = template.getAttribute("duration").toLongOrNull() ?: return null
                val total = periodDur ?: return null
                val count = ceil(total * timescale / dur).toLong().coerceAtMost(MAX_SEGMENTS.toLong())
                for (k in 0 until count) {
                    val number = startNumber + k
                    urls += URL(URL(repBase), fill(media, repId, number, k * dur, bandwidth)).toString()
                }
            }
            val initUrl = init?.let { URL(URL(repBase), fill(it, repId, startNumber, 0, bandwidth)).toString() }
            return Plan(initUrl, urls, mime, codecs, prot, isLive, totalSec, audioOnly)
        }
        if (list != null) {
            val initUrl = children(list, "Initialization").firstOrNull()?.getAttribute("sourceURL")?.ifBlank { null }
                ?.let { URL(URL(repBase), it).toString() }
            val urls = children(list, "SegmentURL").mapNotNull { it.getAttribute("media").ifBlank { null } }
                .map { URL(URL(repBase), it).toString() }
            return Plan(initUrl, urls, mime, codecs, prot, isLive, totalSec, audioOnly)
        }
        // SegmentBase o solo BaseURL: un único archivo
        return Plan(null, listOf(repBase), mime, codecs, prot, isLive, totalSec, audioOnly)
    }

    private const val MAX_SEGMENTS = 20_000

    private fun isAudio(e: Element): Boolean {
        if (e.getAttribute("contentType") == "audio") return true
        if (e.getAttribute("mimeType").startsWith("audio")) return true
        return children(e, "Representation").any { it.getAttribute("mimeType").startsWith("audio") } ||
            children(e, "ContentComponent").any { it.getAttribute("contentType") == "audio" } &&
            children(e, "ContentComponent").none { it.getAttribute("contentType") == "video" }
    }

    fun fill(tpl: String, repId: String, number: Long, time: Long, bandwidth: String): String {
        val re = Regex("\\$(RepresentationID|Number|Time|Bandwidth)(%0(\\d+)d)?\\$")
        return re.replace(tpl) { m ->
            val v = when (m.groupValues[1]) {
                "RepresentationID" -> return@replace repId
                "Number" -> number
                "Time" -> time
                else -> bandwidth.toLongOrNull() ?: 0
            }
            val width = m.groupValues[3].toIntOrNull()
            if (width != null) v.toString().padStart(width, '0') else v.toString()
        }.replace("$$", "$")
    }

    private fun children(e: Element, name: String): List<Element> {
        val out = ArrayList<Element>()
        val nl = e.childNodes
        for (i in 0 until nl.length) {
            val n = nl.item(i)
            if (n is Element && (n.tagName == name || n.tagName.endsWith(":$name"))) out += n
        }
        return out
    }

    private fun resolveBase(base: String, e: Element): String {
        val b = children(e, "BaseURL").firstOrNull()?.textContent?.trim()?.ifBlank { null } ?: return base
        return runCatching { URL(URL(base), b).toString() }.getOrDefault(base)
    }

    /** ISO-8601 PT#H#M#S (también días). */
    fun parseDuration(s: String?): Double? {
        if (s.isNullOrBlank()) return null
        val m = Regex("P(?:(\\d+(?:\\.\\d+)?)D)?(?:T(?:(\\d+(?:\\.\\d+)?)H)?(?:(\\d+(?:\\.\\d+)?)M)?(?:(\\d+(?:\\.\\d+)?)S)?)?").matchEntire(s.trim()) ?: return null
        val (d, h, mi, se) = m.destructured
        return (d.toDoubleOrNull() ?: 0.0) * 86400 + (h.toDoubleOrNull() ?: 0.0) * 3600 +
            (mi.toDoubleOrNull() ?: 0.0) * 60 + (se.toDoubleOrNull() ?: 0.0)
    }
}
