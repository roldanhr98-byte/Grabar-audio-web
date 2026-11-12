package com.audiovault.browser.detector

enum class MediaKind(val label: String) {
    AUDIO_FILE("Archivo de audio"),
    VIDEO_FILE("Vídeo (con audio)"),
    HLS("Lista HLS (.m3u8)"),
    DASH("Manifiesto DASH (.mpd)"),
    BLOB("Blob de la página"),
    MSE("Media Source Extensions"),
    FETCH("Respuesta de red (fetch/XHR)"),
    WEBAUDIO_ENCODED("Web Audio · archivo original"),
    WEBAUDIO_BUFFER("Web Audio · AudioBuffer"),
    WEBAUDIO_STREAM("Web Audio · tiempo real"),
    DATA_URI("Audio incrustado (data:)"),
    BLOB_UNKNOWN("URL blob: (origen desconocido)"),
    MEDIA_STREAM("MediaStream / WebRTC"),
    TTS("Voz del sistema (speechSynthesis)");

    /** Método de obtención mostrado en el panel. */
    val method: String
        get() = when (this) {
            AUDIO_FILE, VIDEO_FILE -> "Descarga directa del archivo original"
            HLS, DASH -> "Ensamblado de segmentos sin cifrar"
            BLOB, FETCH, WEBAUDIO_ENCODED, DATA_URI, BLOB_UNKNOWN -> "Extracción del original desde la página"
            MSE -> "Reconstrucción desde Media Source"
            WEBAUDIO_BUFFER -> "Exportación PCM del AudioBuffer"
            WEBAUDIO_STREAM, MEDIA_STREAM, TTS -> "Solo captura de reproducción"
        }

    val hasFile: Boolean
        get() = this != WEBAUDIO_STREAM && this != MEDIA_STREAM && this != TTS
}

enum class PlaybackState(val label: String) {
    UNKNOWN("Detectado"),
    PLAYING("Reproduciendo"),
    WAITING("Cargando"),
    PAUSED("En pausa"),
    ENDED("Finalizado"),
}

sealed class AcquireStatus {
    data object Idle : AcquireStatus()
    data class Working(val label: String, val progress: Float? = null) : AcquireStatus()
    data class Saved(val itemId: Long, val duplicate: Boolean, val label: String) : AcquireStatus()
    data class Failed(val message: String) : AcquireStatus()
    data class NotDownloadable(val reason: String) : AcquireStatus()
}

data class DetectedMedia(
    val id: String,
    val tabId: String,
    val pageUrl: String?,
    val pageTitle: String?,
    val kind: MediaKind,
    val url: String? = null,
    val mime: String? = null,
    val sizeBytes: Long? = null,
    val durationSec: Double? = null,
    val playback: PlaybackState = PlaybackState.UNKNOWN,
    val resId: String? = null,
    val frameId: String? = null,
    val isProtected: Boolean = false,
    val complete: Boolean = true,
    val nonLinear: Boolean = false,
    val isVideo: Boolean = false,
    val played: Boolean = false,
    val status: AcquireStatus = AcquireStatus.Idle,
    val captureNote: String? = null,
    val firstSeen: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val shortUrl: String?
        get() = url?.let { if (it.length > 90) it.take(60) + "…" + it.takeLast(25) else it }
}
