package com.audiovault.browser.library

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.audiovault.browser.data.db.AppDatabase
import com.audiovault.browser.data.db.AudioItemEntity
import com.audiovault.browser.data.db.AudioOrigin
import com.audiovault.browser.data.db.CaptureStatus
import com.audiovault.browser.data.settings.SettingsRepository
import com.audiovault.browser.data.settings.StorageLocation
import com.audiovault.browser.player.WaveformCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** Resultado de importar un archivo a la biblioteca. */
sealed class ImportResult {
    data class Saved(val item: AudioItemEntity) : ImportResult()
    data class Duplicate(val existing: AudioItemEntity) : ImportResult()
    data class Rejected(val reason: String) : ImportResult()
}

/**
 * MI BIBLIOTECA: almacenamiento local de audios + metadatos en Room.
 * Los archivos nunca se borran automáticamente; solo por acción explícita del usuario.
 */
class LibraryRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) {
    private val mutex = Mutex()
    private val dao get() = db.audioItems()

    val all: Flow<List<AudioItemEntity>> = dao.observeAll()
    fun recent(limit: Int) = dao.observeRecent(limit)
    val totalSize: Flow<Long> = dao.observeTotalSize()
    fun observe(id: Long) = dao.observe(id)

    suspend fun rootDir(): File {
        val s = settings.current()
        val base = when (s.storageLocation) {
            StorageLocation.APP_EXTERNAL -> context.getExternalFilesDir(null) ?: context.filesDir
            StorageLocation.APP_INTERNAL -> context.filesDir
        }
        return File(base, "AudioVault").apply { mkdirs() }
    }

    /**
     * Mueve [temp] a AudioVault/<Sitio>/<prefijo>_NNN.<ext>, valida el contenido,
     * evita duplicados (SHA-256) y registra los metadatos.
     */
    suspend fun import(
        temp: File,
        ext: String,
        mime: String,
        site: SiteInfo,
        pageUrl: String?,
        pageTitle: String?,
        mediaUrl: String?,
        origin: AudioOrigin,
        status: CaptureStatus,
        displayName: String? = null,
        notes: String? = null,
        minBytes: Long = 1024,
    ): ImportResult = withContext(Dispatchers.IO) {
        if (!temp.exists() || temp.length() < minBytes) {
            temp.delete()
            return@withContext ImportResult.Rejected("Archivo vacío o demasiado pequeño (${temp.length()} bytes)")
        }
        val head = ByteArray(1024)
        val n = FileInputStream(temp).use { it.read(head) }
        if (n <= 0 || AudioFormatSniffer.looksLikeText(head.copyOf(n))) {
            temp.delete()
            return@withContext ImportResult.Rejected("El contenido no es audio (HTML/texto)")
        }
        val sha = sha256(temp)
        mutex.withLock {
            dao.findBySha(sha)?.let { existing ->
                if (File(existing.filePath).exists()) {
                    temp.delete()
                    return@withContext ImportResult.Duplicate(existing)
                }
            }
            val info = MediaInfoReader.read(temp)
            if (!info.hasAudio && ext != "wav") {
                // No descartamos: algunos contenedores fragmentados no exponen pistas sin init.
                Log.w(TAG, "No se detectó pista de audio en ${temp.name}")
            }
            val folderDir = File(rootDir(), site.folder).apply { mkdirs() }
            val existingNames = (folderDir.list()?.toList() ?: emptyList()) + dao.fileNamesInFolder(site.folder)
            val fileName = SiteIdentifier.nextFileName(site.filePrefix, ext, existingNames)
            val dest = File(folderDir, fileName)
            if (!temp.renameTo(dest)) {
                temp.copyTo(dest, overwrite = false)
                temp.delete()
            }
            val now = System.currentTimeMillis()
            val item = AudioItemEntity(
                displayName = displayName ?: SiteIdentifier.displayName(site, now),
                filePath = dest.absolutePath,
                fileName = fileName,
                folder = site.folder,
                siteKey = site.key,
                siteLabel = site.label,
                sourcePageUrl = pageUrl,
                sourcePageTitle = pageTitle,
                mediaUrl = mediaUrl?.take(2000),
                createdAt = now,
                durationMs = info.durationMs,
                sizeBytes = dest.length(),
                format = ext,
                mimeType = mime,
                origin = origin,
                captureStatus = status,
                sampleRate = info.sampleRate,
                channels = info.channels,
                bitrate = info.bitrate,
                sha256 = sha,
                notes = notes,
            )
            val id = dao.insert(item)
            val saved = item.copy(id = id)
            if (settings.current().publishToMusic) runCatching { publishToMusic(saved) }
            ImportResult.Saved(saved)
        }
    }

    suspend fun rename(item: AudioItemEntity, newName: String, renameFile: Boolean): AudioItemEntity = withContext(Dispatchers.IO) {
        var updated = item.copy(displayName = newName.trim().ifBlank { item.displayName })
        if (renameFile) {
            val src = File(item.filePath)
            val safe = SiteIdentifier.sanitizeFileName(newName)
            var dest = File(src.parentFile, "$safe.${item.format}")
            var i = 2
            while (dest.exists() && dest != src) { dest = File(src.parentFile, "$safe ($i).${item.format}"); i++ }
            if (src.renameTo(dest)) updated = updated.copy(filePath = dest.absolutePath, fileName = dest.name)
        }
        dao.update(updated)
        updated
    }

    suspend fun updateNotes(item: AudioItemEntity, notes: String) = withContext(Dispatchers.IO) {
        dao.update(item.copy(notes = notes))
    }

    /** Eliminación explícita solicitada por el usuario. */
    suspend fun delete(item: AudioItemEntity) = withContext(Dispatchers.IO) {
        File(item.filePath).delete()
        WaveformCache.fileFor(context, item.id).delete()
        dao.delete(item)
    }

    suspend fun get(id: Long) = dao.get(id)
    suspend fun getMany(ids: List<Long>) = dao.getMany(ids)

    private fun publishToMusic(item: AudioItemEntity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, item.fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, item.mimeType.substringBefore(';'))
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/AudioVault/" + item.folder)
            put(MediaStore.Audio.Media.TITLE, item.displayName)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return
        resolver.openOutputStream(uri)?.use { out -> File(item.filePath).inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    companion object {
        private const val TAG = "LibraryRepository"

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val r = input.read(buf)
                    if (r <= 0) break
                    md.update(buf, 0, r)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
