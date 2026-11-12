package com.audiovault.browser.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.audiovault.browser.data.db.AudioItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Compartir, exportar a una ubicación elegida (SAF) y empaquetar en ZIP. Todo local. */
class ExportManager(private val context: Context) {

    fun uriFor(item: AudioItemEntity): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", File(item.filePath))

    fun shareIntent(items: List<AudioItemEntity>): Intent {
        val uris = ArrayList(items.map { uriFor(it) })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = items[0].mimeType.substringBefore(';')
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "audio/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }
        intent.clipData = ClipData.newRawUri(null, uris[0]).also { cd -> uris.drop(1).forEach { cd.addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(intent, "Compartir audio")
    }

    suspend fun exportTo(item: AudioItemEntity, dest: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(dest)?.use { out ->
                File(item.filePath).inputStream().use { it.copyTo(out) }
            } ?: return@runCatching false
            true
        }.getOrDefault(false)
    }

    /** ZIP con los audios (sin recomprimir) y un metadata.json con la información técnica. */
    suspend fun exportZip(items: List<AudioItemEntity>, dest: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val out = context.contentResolver.openOutputStream(dest) ?: error("No se pudo abrir el destino")
            val used = HashSet<String>()
            val meta = JSONArray()
            ZipOutputStream(out.buffered()).use { zip ->
                for (it in items) {
                    val f = File(it.filePath)
                    if (!f.exists()) continue
                    var name = "${it.folder}/${f.name}"
                    var k = 2
                    while (!used.add(name)) { name = "${it.folder}/${f.nameWithoutExtension}_$k.${f.extension}"; k++ }
                    // Audio ya comprimido: STORED evita trabajo inútil.
                    val entry = ZipEntry(name)
                    entry.method = ZipEntry.STORED
                    entry.size = f.length()
                    entry.compressedSize = f.length()
                    entry.crc = crc(f)
                    entry.time = it.createdAt
                    zip.putNextEntry(entry)
                    f.inputStream().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                    meta.put(JSONObject().apply {
                        put("archivo", name); put("nombre", it.displayName); put("sitio", it.siteLabel)
                        put("pagina", it.sourcePageUrl ?: JSONObject.NULL); put("fecha", it.createdAt)
                        put("duracion_ms", it.durationMs); put("tamano", it.sizeBytes); put("formato", it.format)
                        put("origen", it.origin.label); put("estado", it.captureStatus.label); put("sha256", it.sha256 ?: JSONObject.NULL)
                    })
                }
                zip.putNextEntry(ZipEntry("metadata.json"))
                zip.write(meta.toString(2).toByteArray())
                zip.closeEntry()
            }
            meta.length()
        }
    }

    private fun crc(f: File): Long {
        val c = CRC32()
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n <= 0) break; c.update(buf, 0, n) }
        }
        return c.value
    }
}
