package com.audiovault.browser.detector

/** Clasifica URLs observadas por el WebView (shouldInterceptRequest, descargas, src de elementos). */
object MediaUrlClassifier {
    private val audioExt = setOf("mp3", "wav", "wave", "m4a", "aac", "ogg", "oga", "opus", "weba", "flac", "amr", "aiff", "aif", "mka")
    private val videoExt = setOf("mp4", "webm", "mkv", "mov", "m4v", "3gp", "ts")

    fun extension(url: String): String? {
        val path = runCatching { java.net.URI(url).path }.getOrNull() ?: url.substringBefore('?').substringBefore('#')
        val last = path.substringAfterLast('/')
        if (!last.contains('.')) return null
        return last.substringAfterLast('.').lowercase().takeIf { it.length in 2..5 }
    }

    fun classify(url: String, mime: String? = null): MediaKind? {
        val m = mime?.lowercase()?.substringBefore(';')?.trim()
        if (m != null) {
            when {
                m.contains("mpegurl") -> return MediaKind.HLS
                m.contains("dash+xml") -> return MediaKind.DASH
                m.startsWith("audio/") -> return MediaKind.AUDIO_FILE
                m.startsWith("video/") -> return MediaKind.VIDEO_FILE
            }
        }
        if (!url.startsWith("http")) return null
        val ext = extension(url) ?: return null
        return when {
            ext == "m3u8" -> MediaKind.HLS
            ext == "mpd" -> MediaKind.DASH
            ext in audioExt -> MediaKind.AUDIO_FILE
            ext in videoExt -> MediaKind.VIDEO_FILE
            else -> null
        }
    }

    fun isAudioKind(k: MediaKind) = k == MediaKind.AUDIO_FILE
}
