package com.audiovault.browser.download

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cliente HTTP que reutiliza la sesión del usuario (cookies del WebView, User-Agent
 * y Referer) para descargar recursos que la página ya puede reproducir.
 * No usa proxies, certificados falsos ni APIs privadas.
 */
class HttpFetcher(
    private val cookieProvider: (String) -> String?,
    private val userAgent: () -> String?,
) {
    data class Outcome(val finalUrl: String, val mime: String?, val bytes: Long, val status: Int, val contentDisposition: String?)

    class HttpError(val status: Int, msg: String) : IOException(msg)

    private fun open(url: String, referer: String?, range: LongRange? = null): HttpURLConnection {
        var current = url
        repeat(8) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            userAgent()?.let { conn.setRequestProperty("User-Agent", it) }
            cookieProvider(current)?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Cookie", it) }
            referer?.takeIf { it.startsWith("http") }?.let { conn.setRequestProperty("Referer", it) }
            conn.setRequestProperty("Accept", "audio/*,video/*;q=0.9,application/*;q=0.8,*/*;q=0.5")
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (range != null) conn.setRequestProperty("Range", "bytes=${range.first}-${range.last}")
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw HttpError(code, "Redirección sin destino")
                current = URL(URL(current), loc).toString()
                conn.disconnect()
                return@repeat
            }
            return conn
        }
        throw IOException("Demasiadas redirecciones")
    }

    /** Descarga a [dest]. Lanza [HttpError] si el servidor responde con error. */
    fun download(
        url: String,
        dest: File,
        referer: String?,
        maxBytes: Long = 1_000_000_000L,
        range: LongRange? = null,
        append: Boolean = false,
        onProgress: (Long, Long?) -> Unit = { _, _ -> },
    ): Outcome {
        val conn = open(url, referer, range)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw HttpError(code, "HTTP $code")
            val total = conn.contentLengthLong.takeIf { it > 0 }
            if (total != null && total > maxBytes) throw IOException("Archivo demasiado grande ($total bytes)")
            var written = 0L
            conn.inputStream.use { input ->
                FileOutputStream(dest, append).use { out ->
                    written = copy(input, out, maxBytes) { onProgress(it, total) }
                }
            }
            return Outcome(conn.url.toString(), conn.contentType, written, code, conn.getHeaderField("Content-Disposition"))
        } finally {
            conn.disconnect()
        }
    }

    fun fetchText(url: String, referer: String?, maxBytes: Int = 8 * 1024 * 1024): Pair<String, String> {
        val conn = open(url, referer)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw HttpError(code, "HTTP $code")
            val bytes = conn.inputStream.use { it.readNBytesCompat(maxBytes) }
            return conn.url.toString() to String(bytes, Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    private fun copy(input: InputStream, out: FileOutputStream, max: Long, progress: (Long) -> Unit): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        var lastReport = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
            if (total > max) throw IOException("Se superó el tamaño máximo")
            if (total - lastReport > 256 * 1024) { progress(total); lastReport = total }
        }
        progress(total)
        return total
    }

    private fun InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < max) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
