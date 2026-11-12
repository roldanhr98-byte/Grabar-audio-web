package com.audiovault.browser.cache

import android.content.Context
import com.audiovault.browser.capture.CaptureController
import com.audiovault.browser.data.db.AppDatabase
import com.audiovault.browser.data.db.CaptureSessionEntity
import com.audiovault.browser.data.db.CaptureStatus
import com.audiovault.browser.data.db.SessionState
import com.audiovault.browser.data.settings.OutputFormat
import com.audiovault.browser.data.settings.SettingsRepository
import com.audiovault.browser.capture.WavWriter
import com.audiovault.browser.library.SiteIdentifier
import com.audiovault.browser.library.SiteInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Caché de capturas: conserva grabaciones incompletas, recupera sesiones interrumpidas,
 * informa del espacio usado y permite vaciarla. Nunca toca archivos de la biblioteca.
 */
class CacheManager(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val capture: CaptureController,
) {
    val sessions: Flow<List<CaptureSessionEntity>> = db.sessions().observeAll()

    private val captureDir get() = capture.cacheDir
    private val workDir get() = File(context.filesDir, "work").apply { mkdirs() }

    data class Usage(val captureBytes: Long, val workBytes: Long, val webCacheBytes: Long) {
        val total get() = captureBytes + workBytes + webCacheBytes
    }

    suspend fun usage(): Usage = withContext(Dispatchers.IO) {
        Usage(dirSize(captureDir), dirSize(workDir), dirSize(context.cacheDir))
    }

    private fun dirSize(f: File): Long = if (!f.exists()) 0 else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * Se ejecuta al iniciar la app: toda sesión que quedó abierta (RECORDING/PAUSED) pertenece
     * a un proceso anterior que terminó de forma inesperada. Se repara su cabecera WAV y se marca
     * como INTERRUPTED. También se adoptan archivos .part huérfanos sin registro.
     * @return número de sesiones recuperables.
     */
    suspend fun recoverAfterRestart(): Int = withContext(Dispatchers.IO) {
        val dao = db.sessions()
        val open = dao.openSessions()
        for (s in open) {
            val f = File(s.cachePath)
            val data = WavWriter.repair(f, s.sampleRate, s.channels)
            if (data <= 0) {
                f.delete(); dao.delete(s.id)
            } else {
                dao.upsert(s.copy(state = SessionState.INTERRUPTED, bytesWritten = data, updatedAt = System.currentTimeMillis(),
                    endReason = "La app se cerró durante la grabación"))
            }
        }
        val known = dao.allPaths().toSet()
        captureDir.listFiles()?.filter { it.name.endsWith(".wav.part") && it.absolutePath !in known }?.forEach { f ->
            val info = com.audiovault.browser.library.WavInfo.read(f)
            val sr = info?.sampleRate?.takeIf { it > 0 } ?: 48000
            val ch = info?.channels?.takeIf { it > 0 } ?: 2
            val data = WavWriter.repair(f, sr, ch)
            if (data > 0) {
                dao.upsert(CaptureSessionEntity(
                    id = f.name.removeSuffix(".wav.part"), cachePath = f.absolutePath, siteKey = "otros", siteLabel = "AUDIO",
                    pageUrl = null, pageTitle = null, mediaUrl = null,
                    source = com.audiovault.browser.data.db.CaptureSource.INTERNAL, sampleRate = sr, channels = ch,
                    startedAt = f.lastModified(), updatedAt = f.lastModified(), bytesWritten = data,
                    state = SessionState.INTERRUPTED, endReason = "Archivo huérfano recuperado de la caché",
                ))
            } else f.delete()
        }
        // Limpieza de temporales de descargas a medio terminar (no son audios de la biblioteca).
        workDir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 6 * 3600_000L }?.forEach { it.delete() }
        val interrupted = dao.interrupted()
        if (settings.current().autoRecoverInterrupted) {
            var recovered = 0
            for (s in interrupted) if (recover(s)) recovered++
            interrupted.size - recovered
        } else interrupted.size
    }

    /** Mueve una sesión interrumpida a la biblioteca marcada como recuperada/incompleta. */
    suspend fun recover(s: CaptureSessionEntity): Boolean = withContext(Dispatchers.IO) {
        val f = File(s.cachePath)
        if (!f.exists()) { db.sessions().delete(s.id); return@withContext false }
        WavWriter.repair(f, s.sampleRate, s.channels)
        val site: SiteInfo = if (s.pageUrl != null) SiteIdentifier.identify(s.pageUrl) else SiteIdentifier.forExternalCapture()
        capture.saveToLibrary(
            entity = s, file = f, site = site, status = CaptureStatus.RECOVERED,
            notes = "Recuperado de la caché. ${s.endReason ?: ""}".trim(),
            fmt = OutputFormat.WAV, bitrate = 0, src = s.source,
        )
    }

    suspend fun deleteSession(s: CaptureSessionEntity) = withContext(Dispatchers.IO) {
        File(s.cachePath).delete()
        db.sessions().delete(s.id)
    }

    /** Vacía la caché (temporales y sesiones no abiertas). Nunca borra la biblioteca. */
    suspend fun clearCache(includeInterrupted: Boolean) = withContext(Dispatchers.IO) {
        workDir.listFiles()?.forEach { it.deleteRecursively() }
        // La caché HTTP del WebView se vacía con WebView.clearCache (desde Ajustes), no aquí.
        context.cacheDir.listFiles()?.filter { !it.name.contains("WebView", ignoreCase = true) }?.forEach { it.deleteRecursively() }
        if (includeInterrupted) {
            for (s in db.sessions().interrupted()) deleteSession(s)
        }
    }
}
