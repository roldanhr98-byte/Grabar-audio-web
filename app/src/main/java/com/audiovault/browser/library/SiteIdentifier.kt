package com.audiovault.browser.library

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Identificación de la página de origen para nombrar carpetas y lecturas. */
data class SiteInfo(
    val key: String,
    /** Etiqueta en mayúsculas para el título, p. ej. "CLAUDE". */
    val label: String,
    /** Nombre de carpeta dentro de AudioVault/. */
    val folder: String,
    /** Descripción del tipo de audio, p. ej. "Lectura de texto". */
    val activity: String,
    /** Prefijo del archivo: lectura, generacion, audio. */
    val filePrefix: String,
)

object SiteIdentifier {
    private val known = listOf(
        Triple(listOf("claude.ai", "claude.com", "anthropic.com"), "claude",
            SiteInfo("claude", "CLAUDE", "Claude", "Lectura de texto", "lectura")),
        Triple(listOf("chatgpt.com", "chat.openai.com", "openai.com"), "chatgpt",
            SiteInfo("chatgpt", "CHATGPT", "ChatGPT", "Lectura de respuesta", "lectura")),
        Triple(listOf("elevenlabs.io", "elevenlabs.com"), "elevenlabs",
            SiteInfo("elevenlabs", "ELEVENLABS", "ElevenLabs", "Generación de voz", "generacion")),
        Triple(listOf("gemini.google.com"), "gemini",
            SiteInfo("gemini", "GEMINI", "Gemini", "Lectura de respuesta", "lectura")),
        Triple(listOf("youtube.com", "youtu.be", "m.youtube.com"), "youtube",
            SiteInfo("youtube", "YOUTUBE", "YouTube", "Audio de vídeo", "audio")),
        Triple(listOf("translate.google.com"), "gtranslate",
            SiteInfo("gtranslate", "GOOGLE TRANSLATE", "GoogleTranslate", "Lectura de texto", "lectura")),
        Triple(listOf("notebooklm.google.com"), "notebooklm",
            SiteInfo("notebooklm", "NOTEBOOKLM", "NotebookLM", "Resumen de audio", "lectura")),
    )

    fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatching { java.net.URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull()
    }

    fun identify(pageUrl: String?): SiteInfo {
        val host = hostOf(pageUrl)
        if (host != null) {
            for ((domains, _, info) in known) {
                if (domains.any { host == it || host.endsWith(".$it") }) return info
            }
            val folder = sanitizeFolder(host)
            return SiteInfo(host, host.uppercase(Locale.ROOT), folder, "Audio web", "audio")
        }
        return SiteInfo("otros", "AUDIO", "Otros", "Audio", "audio")
    }

    fun forExternalCapture(): SiteInfo =
        SiteInfo("sistema", "AUDIO INTERNO", "AudioInterno", "Captura de audio interno", "captura")

    fun forMicrophone(): SiteInfo =
        SiteInfo("microfono", "MICRÓFONO", "Microfono", "Grabación por micrófono", "microfono")

    fun displayName(info: SiteInfo, timeMs: Long): String {
        val date = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(timeMs))
        return "${info.label} - ${info.activity} - $date"
    }

    fun sanitizeFolder(s: String): String =
        s.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60).ifBlank { "Otros" }

    fun sanitizeFileName(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(120).ifBlank { "audio" }

    /** Siguiente nombre libre: prefijo_001.ext, prefijo_002.ext ... */
    fun nextFileName(prefix: String, ext: String, existing: Collection<String>): String {
        val re = Regex("^" + Regex.escape(prefix) + "_(\\d+)\\.[A-Za-z0-9]+$")
        val max = existing.mapNotNull { re.find(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0
        return "%s_%03d.%s".format(prefix, max + 1, ext)
    }
}
