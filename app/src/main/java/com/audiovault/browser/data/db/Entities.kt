package com.audiovault.browser.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Cómo se obtuvo un audio de la biblioteca. */
enum class AudioOrigin(val label: String) {
    DOWNLOADED_ORIGINAL("Original descargado"),
    BLOB_EXTRACTED("Original (Blob de la página)"),
    FETCH_EXTRACTED("Original (respuesta de red)"),
    MSE_RECONSTRUCTED("Reconstruido (Media Source)"),
    WEBAUDIO_ORIGINAL("Original (Web Audio decodeAudioData)"),
    WEBAUDIO_RENDERED("PCM de Web Audio (AudioBuffer)"),
    HLS_ASSEMBLED("Ensamblado HLS"),
    DASH_ASSEMBLED("Ensamblado DASH"),
    CAPTURED_INTERNAL("Capturado (audio interno)"),
    CAPTURED_MICROPHONE("Grabado (micrófono)");

    val isCapture: Boolean get() = this == CAPTURED_INTERNAL || this == CAPTURED_MICROPHONE
}

/** Estado de integridad del archivo guardado. */
enum class CaptureStatus(val label: String) {
    COMPLETE("Completo"),
    INCOMPLETE("Incompleto"),
    RECOVERED("Recuperado tras interrupción"),
}

@Entity(
    tableName = "audio_items",
    indices = [Index("sha256"), Index("siteKey"), Index("createdAt")]
)
data class AudioItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val filePath: String,
    val fileName: String,
    val folder: String,
    val siteKey: String,
    val siteLabel: String,
    val sourcePageUrl: String?,
    val sourcePageTitle: String?,
    val mediaUrl: String?,
    val createdAt: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val format: String,
    val mimeType: String,
    val origin: AudioOrigin,
    val captureStatus: CaptureStatus,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val bitrate: Int? = null,
    val sha256: String? = null,
    val notes: String? = null,
)

@Entity(tableName = "history", indices = [Index("visitedAt")])
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val visitedAt: Long,
)

@Entity(tableName = "bookmarks", indices = [Index(value = ["url"], unique = true)])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val createdAt: Long,
)

enum class DownloadState { QUEUED, RUNNING, DONE, FAILED }

@Entity(tableName = "downloads", indices = [Index("createdAt")])
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val fileName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val state: DownloadState,
    val systemDownloadId: Long? = null,
    val localPath: String? = null,
    val audioItemId: Long? = null,
    val error: String? = null,
    val createdAt: Long,
)

enum class SessionState { RECORDING, PAUSED, FINALIZED, INTERRUPTED, RECOVERED, DISCARDED }

enum class CaptureSource { INTERNAL, MICROPHONE }

/** Sesión de captura en caché. Se crea en cuanto empieza a escribirse audio. */
@Entity(tableName = "capture_sessions", indices = [Index("state")])
data class CaptureSessionEntity(
    @PrimaryKey val id: String,
    val cachePath: String,
    val siteKey: String,
    val siteLabel: String,
    val pageUrl: String?,
    val pageTitle: String?,
    val mediaUrl: String?,
    val source: CaptureSource,
    val sampleRate: Int,
    val channels: Int,
    val startedAt: Long,
    val updatedAt: Long,
    val bytesWritten: Long,
    val state: SessionState,
    val endReason: String? = null,
    val libraryItemId: Long? = null,
)
