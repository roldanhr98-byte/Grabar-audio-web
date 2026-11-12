package com.audiovault.browser.browser

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil
import com.audiovault.browser.data.db.AppDatabase
import com.audiovault.browser.data.db.DownloadEntity
import com.audiovault.browser.data.db.DownloadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Descargas que no son audio: se delegan en el DownloadManager de Android (carpeta Descargas). */
class GeneralDownloads(private val context: Context, private val db: AppDatabase) {
    private val dm get() = context.getSystemService(DownloadManager::class.java)

    suspend fun enqueue(url: String, userAgent: String?, contentDisposition: String?, mime: String?, cookie: String?, referer: String?): String =
        withContext(Dispatchers.IO) {
            val name = URLUtil.guessFileName(url, contentDisposition, mime)
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setDescription("AudioVault Browser")
                .setMimeType(mime)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "AudioVault/$name")
            cookie?.let { req.addRequestHeader("Cookie", it) }
            userAgent?.let { req.addRequestHeader("User-Agent", it) }
            referer?.let { req.addRequestHeader("Referer", it) }
            val sysId = dm.enqueue(req)
            db.downloads().insert(
                DownloadEntity(url = url, fileName = name, mimeType = mime, sizeBytes = null, state = DownloadState.QUEUED,
                    systemDownloadId = sysId, createdAt = System.currentTimeMillis())
            )
            name
        }

    /** Sincroniza el estado con DownloadManager. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        for (d in db.downloads().active()) {
            val id = d.systemDownloadId ?: continue
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (!c.moveToFirst()) {
                    db.downloads().update(d.copy(state = DownloadState.FAILED, error = "Cancelada"))
                    return@use
                }
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val size = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val local = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                val st = when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> DownloadState.DONE
                    DownloadManager.STATUS_FAILED -> DownloadState.FAILED
                    DownloadManager.STATUS_RUNNING -> DownloadState.RUNNING
                    else -> DownloadState.QUEUED
                }
                db.downloads().update(d.copy(state = st, sizeBytes = size.takeIf { it > 0 }, localPath = local))
            }
        }
    }

    fun openIntentFor(d: DownloadEntity): android.content.Intent? {
        val id = d.systemDownloadId ?: return null
        val uri = dm.getUriForDownloadedFile(id) ?: return null
        return android.content.Intent(android.content.Intent.ACTION_VIEW)
            .setDataAndType(uri, d.mimeType ?: "*/*")
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun remove(d: DownloadEntity) {
        d.systemDownloadId?.let { runCatching { dm.remove(it) } }
    }
}
