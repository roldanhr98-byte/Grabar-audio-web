package com.audiovault.browser.detector

import android.util.Base64
import android.util.Log
import com.audiovault.browser.capture.CaptureController
import com.audiovault.browser.capture.SessionStateMachine
import com.audiovault.browser.data.db.AudioOrigin
import com.audiovault.browser.data.db.CaptureStatus
import com.audiovault.browser.data.settings.AppSettings
import com.audiovault.browser.data.settings.SettingsRepository
import com.audiovault.browser.download.HttpFetcher
import com.audiovault.browser.download.StreamAssembler
import com.audiovault.browser.library.AudioFormatSniffer
import com.audiovault.browser.library.ImportResult
import com.audiovault.browser.library.LibraryRepository
import com.audiovault.browser.library.SiteIdentifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Acceso a las pestañas sin depender de la capa de UI. */
interface TabAccess {
    fun evaluateJs(tabId: String, script: String)
    fun cookiesFor(tabId: String?, url: String): String?
    fun userAgent(tabId: String?): String?
    fun pageInfo(tabId: String): Pair<String?, String?>
    fun onDetectorHello(tabId: String)
}

/**
 * Centro del detector: recibe mensajes del JavaScript inyectado y de la capa de red
 * del WebView, mantiene la lista de medios detectados, ejecuta descargas/extracciones
 * y envía señales de reproducción al grabador por sesiones.
 */
class MediaDetector(
    private val workDir: File,
    private val repo: DetectionRepository,
    private val library: LibraryRepository,
    private val settingsRepo: SettingsRepository,
    private val capture: CaptureController,
    private val scope: CoroutineScope,
) {
    @Volatile var tabs: TabAccess? = null
    @Volatile private var settings = AppSettings()

    private class Transfer(
        val xid: String,
        val entryId: String,
        val tabId: String,
        val file: File,
        var out: FileOutputStream? = null,
        var expected: Long = -1,
        var mime: String? = null,
        var kind: String? = null,
        var partial: Boolean = false,
        var nonLinear: Boolean = false,
        var url: String? = null,
        var nextSeq: Int = 0,
        var failed: String? = null,
    )

    private val transfers = ConcurrentHashMap<String, Transfer>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    init {
        workDir.mkdirs()
        scope.launch { settingsRepo.settings.collect { settings = it } }
    }

    // ------------------------------------------------------------ mensajes JS

    fun onJsMessage(tabId: String, json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        try {
            when (o.optString("t")) {
                "hello" -> tabs?.onDetectorHello(tabId)
                "media" -> onMedia(tabId, o)
                "resource" -> onResource(tabId, o)
                "net" -> onNet(tabId, o)
                "webaudio" -> onWebAudio(tabId, o)
                "tts" -> onTts(tabId, o)
                "eme" -> onEme(tabId, o)
                "xfer-begin" -> onXferBegin(o)
                "xfer-end" -> onXferEnd(o)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "mensaje JS no procesado: ${t.message}")
        }
    }

    fun onJsChunk(xid: String, seq: Int, b64: String) {
        val tr = transfers[xid] ?: return
        if (tr.failed != null) return
        try {
            if (seq != tr.nextSeq) { tr.failed = "Trozos desordenados"; return }
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val out = tr.out ?: FileOutputStream(tr.file).also { tr.out = it }
            out.write(bytes)
            tr.nextSeq++
            if (tr.expected > 0) {
                val p = (tr.file.length().toFloat() / tr.expected).coerceIn(0f, 1f)
                repo.setStatus(tr.entryId, AcquireStatus.Working("Transfiriendo desde la página", p))
            }
        } catch (t: Throwable) {
            tr.failed = t.message ?: "Error de escritura"
        }
    }

    private fun page(tabId: String, o: JSONObject): Pair<String?, String?> {
        val (url, title) = tabs?.pageInfo(tabId) ?: (null to null)
        return (url ?: o.optString("page").ifBlank { null }) to (title ?: o.optString("title").ifBlank { null })
    }

    private fun JSONObject.optStr(k: String): String? = if (has(k) && !isNull(k)) optString(k).ifBlank { null } else null
    private fun JSONObject.optDbl(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null

    private fun onMedia(tabId: String, o: JSONObject) {
        val ev = o.optString("ev")
        val frame = o.optString("frame")
        val el = o.optString("el")
        val src = o.optString("src")
        val srcKind = o.optString("srcKind")
        val res = o.optStr("res")
        val tag = o.optString("tag")
        val dur = o.optDbl("dur")
        val prot = o.optBoolean("prot")
        val isVideo = o.optBoolean("video") || (tag == "VIDEO" && srcKind != "mse")
        val (pageUrl, title) = page(tabId, o)

        val id: String? = when (srcKind) {
            "blob", "mse", "fetch", "webaudio-encoded" -> res?.let { "res:$it" }
            "http" -> "url:$src"
            "blob-unknown" -> "blobu:$frame:$src"
            "data" -> "data:$frame:$el"
            "stream" -> "stream:$frame:$el"
            else -> null
        }
        val playback = when (ev) {
            "play", "playing" -> PlaybackState.PLAYING
            "waiting" -> PlaybackState.WAITING
            "pause" -> PlaybackState.PAUSED
            "ended" -> PlaybackState.ENDED
            else -> null
        }
        if (id != null) {
            val kind = when (srcKind) {
                "mse" -> MediaKind.MSE
                "blob" -> MediaKind.BLOB
                "fetch" -> MediaKind.FETCH
                "blob-unknown" -> MediaKind.BLOB_UNKNOWN
                "data" -> MediaKind.DATA_URI
                "stream" -> MediaKind.MEDIA_STREAM
                else -> MediaUrlClassifier.classify(src) ?: if (tag == "AUDIO") MediaKind.AUDIO_FILE else MediaKind.VIDEO_FILE
            }
            val entry = repo.upsert(id, {
                DetectedMedia(
                    id = id, tabId = tabId, pageUrl = pageUrl, pageTitle = title, kind = kind,
                    url = src.takeIf { it.startsWith("http") || it.startsWith("blob:") }, resId = res, frameId = frame,
                    durationSec = dur?.takeIf { it > 0 }, playback = playback ?: PlaybackState.UNKNOWN,
                    isProtected = prot, isVideo = isVideo, played = playback == PlaybackState.PLAYING,
                    status = notDownloadableStatus(kind, prot),
                )
            }) { cur ->
                cur.copy(
                    playback = playback ?: cur.playback,
                    durationSec = dur?.takeIf { it > 0 } ?: cur.durationSec,
                    isProtected = cur.isProtected || prot,
                    isVideo = cur.isVideo || isVideo,
                    played = cur.played || playback == PlaybackState.PLAYING,
                    frameId = cur.frameId ?: frame,
                    pageUrl = cur.pageUrl ?: pageUrl,
                    status = if (prot && !cur.isProtected) AcquireStatus.NotDownloadable(PROTECTED_MSG) else cur.status,
                )
            }
            maybeAutoAcquire(entry, ev)
        }

        // Señal para el grabador por sesiones.
        val signal = when (ev) {
            "play" -> SessionStateMachine.Signal.PLAY
            "playing" -> SessionStateMachine.Signal.PLAYING
            "pause" -> SessionStateMachine.Signal.PAUSE
            "ended" -> SessionStateMachine.Signal.ENDED
            "waiting" -> SessionStateMachine.Signal.WAITING
            "emptied" -> SessionStateMachine.Signal.EMPTIED
            "error" -> SessionStateMachine.Signal.ERROR
            else -> null
        } ?: return
        val silent = o.optBoolean("muted") || (o.has("vol") && o.optDouble("vol", 1.0) <= 0.0)
        if (silent && (signal == SessionStateMachine.Signal.PLAY || signal == SessionStateMachine.Signal.PLAYING)) {
            // Un medio silenciado no produce audio; no inicia sesión (p. ej. vídeos de fondo).
            return
        }
        val key = "$tabId|$frame|$el|${src.hashCode()}"
        val ctx = SessionStateMachine.Context(
            tabId = tabId, pageUrl = pageUrl, pageTitle = title,
            mediaUrl = src.takeIf { it.startsWith("http") }, mediaDurationSec = dur?.takeIf { it > 0 },
        )
        capture.onMediaSignal(key, signal, ctx, detectionId = id)
    }

    private fun notDownloadableStatus(kind: MediaKind, prot: Boolean): AcquireStatus = when {
        prot -> AcquireStatus.NotDownloadable(PROTECTED_MSG)
        kind == MediaKind.MEDIA_STREAM -> AcquireStatus.NotDownloadable("Flujo en vivo (MediaStream/WebRTC): no existe archivo. Solo captura de reproducción.")
        kind == MediaKind.WEBAUDIO_STREAM -> AcquireStatus.NotDownloadable("Audio sintetizado en tiempo real con Web Audio: no existe archivo. Solo captura de reproducción.")
        kind == MediaKind.TTS -> AcquireStatus.NotDownloadable(TTS_MSG)
        else -> AcquireStatus.Idle
    }

    private fun onResource(tabId: String, o: JSONObject) {
        val res = o.optString("res")
        val kindStr = o.optString("kind")
        val id = "res:$res"
        if (o.optBoolean("evicted")) {
            repo.update(id) { if (it.status is AcquireStatus.Saved) it else it.copy(status = AcquireStatus.NotDownloadable("La página liberó estos datos de la memoria antes de guardarlos")) }
            return
        }
        val kind = when (kindStr) {
            "blob" -> MediaKind.BLOB
            "mse" -> MediaKind.MSE
            "fetch", "xhr" -> MediaKind.FETCH
            "webaudio-encoded" -> MediaKind.WEBAUDIO_ENCODED
            "webaudio-buffer" -> MediaKind.WEBAUDIO_BUFFER
            else -> return
        }
        val mime = o.optStr("mime")
        // MSE solo de vídeo sin pista de audio: se lista pero no es audio.
        val tracks = o.optJSONArray("tracks")
        val hasAudioTrack = tracks == null || (0 until tracks.length()).any { tracks.getJSONObject(it).optString("mime").startsWith("audio") }
        val isVideo = (mime?.startsWith("video") == true) && !hasAudioTrack
        val (pageUrl, title) = page(tabId, o)
        val prot = o.optBoolean("prot")
        val entry = repo.upsert(id, {
            DetectedMedia(
                id = id, tabId = tabId, pageUrl = pageUrl, pageTitle = title, kind = kind,
                url = o.optStr("url")?.takeIf { it.startsWith("http") }, mime = mime,
                sizeBytes = o.optLong("size", -1).takeIf { it > 0 }, resId = res, frameId = o.optString("frame"),
                complete = o.optBoolean("complete"), nonLinear = o.optBoolean("nonLinear"),
                durationSec = o.optDbl("dur"), isProtected = prot, isVideo = isVideo,
                status = notDownloadableStatus(kind, prot),
            )
        }) { cur ->
            cur.copy(
                mime = mime ?: cur.mime,
                sizeBytes = o.optLong("size", -1).takeIf { it > 0 } ?: cur.sizeBytes,
                complete = o.optBoolean("complete"),
                nonLinear = o.optBoolean("nonLinear"),
                isProtected = cur.isProtected || prot,
                status = if (prot) AcquireStatus.NotDownloadable(PROTECTED_MSG) else cur.status,
            )
        }
        // Un MSE que termina (endOfStream) tras haberse reproducido se puede guardar ya.
        if (kind == MediaKind.MSE && entry.complete && entry.played) maybeAutoAcquire(entry, "ended")
    }

    private fun onNet(tabId: String, o: JSONObject) {
        val url = o.optStr("url") ?: return
        val mime = o.optStr("mime")
        val kind = MediaUrlClassifier.classify(url, mime) ?: return
        onNetworkMedia(tabId, url, mime, o.optLong("size", -1).takeIf { it > 0 }, kind)
    }

    /** Recursos vistos por shouldInterceptRequest / DownloadListener / JS (fetch/XHR). */
    fun onNetworkMedia(tabId: String, url: String, mime: String?, size: Long?, kind: MediaKind) {
        val (pageUrl, title) = tabs?.pageInfo(tabId) ?: (null to null)
        val id = "url:$url"
        if (repo.get(id) == null) {
            val existing = repo.forTab(tabId)
            // Los segmentos de un flujo HLS/DASH ya detectado no se listan uno a uno.
            val ext = MediaUrlClassifier.extension(url)
            val hasStream = existing.any { it.kind == MediaKind.HLS || it.kind == MediaKind.DASH }
            if (hasStream && kind != MediaKind.HLS && kind != MediaKind.DASH && ext in SEGMENT_EXT) return
            if (existing.count { it.id.startsWith("url:") } >= MAX_NET_ENTRIES_PER_TAB) return
        }
        repo.upsert(id, {
            DetectedMedia(id = id, tabId = tabId, pageUrl = pageUrl, pageTitle = title, kind = kind, url = url,
                mime = mime, sizeBytes = size, isVideo = kind == MediaKind.VIDEO_FILE)
        }) { it.copy(mime = mime ?: it.mime, sizeBytes = size ?: it.sizeBytes) }
    }

    private fun onWebAudio(tabId: String, o: JSONObject) {
        val ev = o.optString("ev")
        val frame = o.optString("frame")
        val ctxId = o.optString("ctx")
        val (pageUrl, title) = page(tabId, o)
        val key = "$tabId|$frame|wa:$ctxId"
        val sctx = SessionStateMachine.Context(tabId = tabId, pageUrl = pageUrl, pageTitle = title)
        when (ev) {
            "running" -> capture.onWebAudioContext(key, true, sctx)
            "suspended", "closed" -> {
                capture.onWebAudioContext(key, false, sctx)
                capture.onMediaSignal(key, SessionStateMachine.Signal.PAUSE, sctx, null)
            }
            "start" -> {
                capture.onWebAudioContext(key, true, sctx)
                capture.onMediaSignal(key, SessionStateMachine.Signal.PLAY, sctx, null)
                val id = "wa:$tabId:$frame:$ctxId"
                repo.upsert(id, {
                    DetectedMedia(id = id, tabId = tabId, pageUrl = pageUrl, pageTitle = title, kind = MediaKind.WEBAUDIO_STREAM,
                        frameId = frame, playback = PlaybackState.PLAYING, played = true,
                        status = notDownloadableStatus(MediaKind.WEBAUDIO_STREAM, false))
                }) { it.copy(playback = PlaybackState.PLAYING, played = true) }
                if (settings.autoDownload) autoAcquireWebAudio(tabId, frame)
            }
            "end" -> {
                capture.onMediaSignal(key, SessionStateMachine.Signal.ENDED, sctx, null)
                repo.update("wa:$tabId:$frame:$ctxId") { it.copy(playback = PlaybackState.ENDED) }
            }
        }
    }

    private fun autoAcquireWebAudio(tabId: String, frame: String) {
        val now = System.currentTimeMillis()
        val candidates = repo.forTab(tabId).filter { it.frameId == frame && it.status == AcquireStatus.Idle }
        val encoded = candidates.filter { it.kind == MediaKind.WEBAUDIO_ENCODED && now - it.firstSeen < 120_000 }
            .maxByOrNull { it.firstSeen }
        val pick = encoded ?: candidates.filter { it.kind == MediaKind.WEBAUDIO_BUFFER }.maxByOrNull { it.firstSeen }
        pick?.let {
            repo.update(it.id) { e -> e.copy(played = true) }
            acquire(it.id)
        }
    }

    private fun onTts(tabId: String, o: JSONObject) {
        val ev = o.optString("ev")
        val (pageUrl, title) = page(tabId, o)
        val id = "tts:$tabId"
        val pb = when (ev) { "start" -> PlaybackState.PLAYING; "end" -> PlaybackState.ENDED; else -> PlaybackState.UNKNOWN }
        repo.upsert(id, {
            DetectedMedia(id = id, tabId = tabId, pageUrl = pageUrl, pageTitle = title, kind = MediaKind.TTS,
                playback = pb, status = AcquireStatus.NotDownloadable(TTS_MSG))
        }) { it.copy(playback = pb) }
        val key = "$tabId|tts"
        val sctx = SessionStateMachine.Context(tabId = tabId, pageUrl = pageUrl, pageTitle = title)
        when (ev) {
            "start" -> capture.onMediaSignal(key, SessionStateMachine.Signal.PLAY, sctx, id)
            "end" -> capture.onMediaSignal(key, SessionStateMachine.Signal.ENDED, sctx, id)
        }
    }

    private fun onEme(tabId: String, o: JSONObject) {
        val frame = o.optString("frame")
        repo.updateWhere({ it.tabId == tabId && (it.frameId == frame || it.frameId == null) && it.status !is AcquireStatus.Saved }) {
            it.copy(isProtected = true, status = AcquireStatus.NotDownloadable(PROTECTED_MSG))
        }
    }

    // ------------------------------------------------------------ adquisición

    private fun isAudioEntry(e: DetectedMedia): Boolean = when (e.kind) {
        MediaKind.AUDIO_FILE, MediaKind.HLS, MediaKind.DASH, MediaKind.FETCH, MediaKind.WEBAUDIO_ENCODED,
        MediaKind.WEBAUDIO_BUFFER, MediaKind.DATA_URI -> true
        MediaKind.BLOB, MediaKind.MSE, MediaKind.BLOB_UNKNOWN -> !e.isVideo && (e.mime == null || !e.mime.startsWith("video") || e.kind == MediaKind.MSE)
        else -> false
    }

    private fun maybeAutoAcquire(e: DetectedMedia, ev: String) {
        if (!settings.autoDownload || e.isProtected || e.status != AcquireStatus.Idle || !e.kind.hasFile) return
        if (!isAudioEntry(e) || e.isVideo) return
        val ready = when (e.kind) {
            // MSE: esperar a que termine la reproducción o el flujo, para tener todos los segmentos.
            MediaKind.MSE -> ev == "ended" && e.played
            else -> ev == "playing" || ev == "play" || ev == "ended"
        }
        if (ready) acquire(e.id)
    }

    /** Descarga/extrae un medio detectado y lo guarda en la biblioteca. */
    fun acquire(entryId: String) {
        val e = repo.get(entryId) ?: return
        if (e.isProtected) { repo.setStatus(entryId, AcquireStatus.NotDownloadable(PROTECTED_MSG)); return }
        if (!e.kind.hasFile) { repo.setStatus(entryId, notDownloadableStatus(e.kind, false)); return }
        if (!inFlight.add(entryId)) return
        repo.setStatus(entryId, AcquireStatus.Working("Preparando"))
        when (e.kind) {
            MediaKind.AUDIO_FILE, MediaKind.VIDEO_FILE -> scope.launch(Dispatchers.IO) { downloadDirect(e) }
            MediaKind.HLS, MediaKind.DASH -> scope.launch(Dispatchers.IO) { assemble(e) }
            MediaKind.BLOB, MediaKind.MSE, MediaKind.FETCH, MediaKind.WEBAUDIO_ENCODED, MediaKind.WEBAUDIO_BUFFER -> {
                val res = e.resId
                if (res == null) { finishFail(e.id, "Recurso sin identificador"); return }
                startTransfer(e, "window.__audiovault && window.__audiovault.exportResource(${JSONObject.quote(res)}, %XID%)")
            }
            MediaKind.BLOB_UNKNOWN, MediaKind.DATA_URI -> {
                val src = e.url ?: e.id.substringAfter(':').substringAfter(':')
                startTransfer(e, "window.__audiovault && window.__audiovault.exportUrl(${JSONObject.quote(src)}, %XID%, ${JSONObject.quote(e.frameId ?: "")})")
            }
            else -> finishFail(e.id, "No descargable")
        }
    }

    /** Descarga de URLs blob:/data: iniciadas por la página (p. ej. botón "Descargar"). */
    fun downloadPageUrl(tabId: String, url: String, mime: String?) {
        val id = "dl:$url"
        val (pageUrl, title) = tabs?.pageInfo(tabId) ?: (null to null)
        repo.upsert(id, {
            DetectedMedia(id = id, tabId = tabId, pageUrl = pageUrl, pageTitle = title,
                kind = if (url.startsWith("data:")) MediaKind.DATA_URI else MediaKind.BLOB_UNKNOWN,
                url = url.takeIf { it.startsWith("blob:") }, mime = mime, played = true)
        })
        acquire(id)
    }

    private fun startTransfer(e: DetectedMedia, scriptTemplate: String) {
        val xid = UUID.randomUUID().toString()
        val file = File(workDir, "xfer_$xid.bin")
        transfers[xid] = Transfer(xid, e.id, e.tabId, file)
        repo.setStatus(e.id, AcquireStatus.Working("Solicitando datos a la página"))
        val js = scriptTemplate.replace("%XID%", JSONObject.quote(xid))
        val t = tabs
        if (t == null) { transfers.remove(xid); finishFail(e.id, "Pestaña no disponible"); return }
        t.evaluateJs(e.tabId, js)
        // Tiempo de espera de seguridad
        scope.launch {
            kotlinx.coroutines.delay(120_000)
            val tr = transfers[xid]
            if (tr != null && tr.nextSeq == 0) {
                transfers.remove(xid)
                runCatching { tr.out?.close() }
                tr.file.delete()
                finishFail(e.id, "La página no respondió (¿pestaña cerrada o recargada?)")
            }
        }
    }

    private fun onXferBegin(o: JSONObject) {
        val tr = transfers[o.optString("xid")] ?: return
        tr.expected = o.optLong("size", -1)
        tr.mime = o.optStr("mime")
        val meta = o.optJSONObject("meta")
        tr.kind = meta?.optStr("kind")
        tr.partial = meta?.optBoolean("partial") ?: false
        tr.nonLinear = meta?.optBoolean("nonLinear") ?: false
        tr.url = meta?.optStr("url")
        repo.setStatus(tr.entryId, AcquireStatus.Working("Transfiriendo desde la página", 0f))
    }

    private fun onXferEnd(o: JSONObject) {
        val xid = o.optString("xid")
        val tr = transfers.remove(xid) ?: return
        runCatching { tr.out?.close() }
        if (!o.optBoolean("ok") || tr.failed != null) {
            tr.file.delete()
            finishFail(tr.entryId, o.optStr("error") ?: tr.failed ?: "Error en la transferencia")
            return
        }
        if (tr.expected > 0 && tr.file.length() != tr.expected) {
            tr.file.delete()
            finishFail(tr.entryId, "Transferencia incompleta (${tr.file.length()} de ${tr.expected} bytes)")
            return
        }
        val e = repo.get(tr.entryId)
        val origin = when (tr.kind) {
            "blob" -> AudioOrigin.BLOB_EXTRACTED
            "fetch", "xhr" -> AudioOrigin.FETCH_EXTRACTED
            "mse" -> AudioOrigin.MSE_RECONSTRUCTED
            "webaudio-encoded" -> AudioOrigin.WEBAUDIO_ORIGINAL
            "webaudio-buffer" -> AudioOrigin.WEBAUDIO_RENDERED
            else -> AudioOrigin.BLOB_EXTRACTED
        }
        val status = if (tr.partial || tr.nonLinear) CaptureStatus.INCOMPLETE else CaptureStatus.COMPLETE
        val note = buildList {
            if (tr.partial) add("Media Source no había terminado: puede faltar el final.")
            if (tr.nonLinear) add("La página eliminó o reordenó segmentos (búsquedas): la reconstrucción puede no ser exacta.")
        }.joinToString(" ").ifBlank { null }
        scope.launch(Dispatchers.IO) {
            importFile(tr.entryId, tr.file, tr.mime ?: e?.mime, e, tr.url ?: e?.url, origin, status, note)
        }
    }

    private suspend fun downloadDirect(e: DetectedMedia) {
        val url = e.url ?: return finishFail(e.id, "Sin URL")
        val http = HttpFetcher({ tabs?.cookiesFor(e.tabId, it) }, { tabs?.userAgent(e.tabId) })
        val tmp = File(workDir, "dl_${System.nanoTime()}.part")
        try {
            val out = http.download(url, tmp, e.pageUrl) { done, total ->
                repo.setStatus(e.id, AcquireStatus.Working("Descargando", total?.let { done.toFloat() / it }))
            }
            if (out.mime?.contains("text/html") == true) {
                tmp.delete()
                return fallbackOrFail(e, "El servidor devolvió una página HTML en lugar de audio (¿enlace caducado o requiere sesión?)")
            }
            importFile(e.id, tmp, out.mime ?: e.mime, e, url, AudioOrigin.DOWNLOADED_ORIGINAL, CaptureStatus.COMPLETE, null)
        } catch (t: Throwable) {
            tmp.delete()
            fallbackOrFail(e, "Descarga fallida: ${t.message}")
        }
    }

    /** Si la URL directa falla (p. ej. firmada y de un solo uso), intenta extraerla desde la página. */
    private fun fallbackOrFail(e: DetectedMedia, msg: String) {
        inFlight.remove(e.id)
        val alt = repo.forTab(e.tabId).firstOrNull { it.kind == MediaKind.FETCH && it.url == e.url && it.status == AcquireStatus.Idle }
        if (alt != null) {
            repo.setStatus(e.id, AcquireStatus.Failed("$msg · Se intenta la copia retenida por la página."))
            acquire(alt.id)
        } else finishFail(e.id, msg)
    }

    private suspend fun assemble(e: DetectedMedia) {
        val url = e.url ?: return finishFail(e.id, "Sin URL")
        val http = HttpFetcher({ tabs?.cookiesFor(e.tabId, it) }, { tabs?.userAgent(e.tabId) })
        try {
            val sa = StreamAssembler(http)
            val progress: (Float) -> Unit = { repo.setStatus(e.id, AcquireStatus.Working("Descargando segmentos", it)) }
            val r = if (e.kind == MediaKind.HLS) sa.hls(url, e.pageUrl, workDir, progress) else sa.dash(url, e.pageUrl, workDir, progress)
            val origin = if (e.kind == MediaKind.HLS) AudioOrigin.HLS_ASSEMBLED else AudioOrigin.DASH_ASSEMBLED
            val status = if (r.complete) CaptureStatus.COMPLETE else CaptureStatus.INCOMPLETE
            importFile(e.id, r.file, r.mime, e, url, origin, status, r.note, forcedExt = r.ext)
        } catch (na: StreamAssembler.NotAllowed) {
            inFlight.remove(e.id)
            repo.update(e.id) { it.copy(isProtected = true, status = AcquireStatus.NotDownloadable(na.message ?: PROTECTED_MSG)) }
        } catch (t: Throwable) {
            finishFail(e.id, "No se pudo ensamblar: ${t.message}")
        }
    }

    private suspend fun importFile(
        entryId: String, file: File, mime: String?, e: DetectedMedia?, mediaUrl: String?,
        origin: AudioOrigin, status: CaptureStatus, note: String?, forcedExt: String? = null,
    ) {
        try {
            val head = ByteArray(512)
            val n = file.inputStream().use { it.read(head) }
            if (n <= 0) return finishFail(entryId, "Archivo vacío").also { file.delete() }
            val sniff = AudioFormatSniffer.sniff(head.copyOf(n), mime)
            if (sniff == null && AudioFormatSniffer.looksLikeText(head.copyOf(n))) {
                file.delete()
                return finishFail(entryId, "El contenido recibido no es audio (texto/HTML)")
            }
            val ext = forcedExt ?: sniff?.ext ?: AudioFormatSniffer.extForMime(mime)
            if (ext == null) {
                file.delete()
                return finishFail(entryId, "Formato no reconocido (${mime ?: "sin MIME"})")
            }
            val finalMime = sniff?.mime ?: mime ?: AudioFormatSniffer.mimeForExt(ext)
            val site = SiteIdentifier.identify(e?.pageUrl)
            val result = library.import(
                temp = file, ext = ext, mime = finalMime, site = site, pageUrl = e?.pageUrl, pageTitle = e?.pageTitle,
                mediaUrl = mediaUrl, origin = origin, status = status, notes = note,
                minBytes = settings.minDownloadBytes.toLong().coerceAtMost(4096),
            )
            when (result) {
                is ImportResult.Saved -> repo.setStatus(entryId, AcquireStatus.Saved(result.item.id, false, result.item.fileName))
                is ImportResult.Duplicate -> repo.setStatus(entryId, AcquireStatus.Saved(result.existing.id, true, result.existing.fileName))
                is ImportResult.Rejected -> repo.setStatus(entryId, AcquireStatus.Failed(result.reason))
            }
            if (result is ImportResult.Saved) capture.notifyOriginalSaved(e?.tabId)
        } catch (t: Throwable) {
            file.delete()
            repo.setStatus(entryId, AcquireStatus.Failed("Error al guardar: ${t.message}"))
        } finally {
            inFlight.remove(entryId)
        }
    }

    private fun finishFail(entryId: String, msg: String) {
        inFlight.remove(entryId)
        repo.setStatus(entryId, AcquireStatus.Failed(msg))
    }

    fun onTabClosed(tabId: String) {
        repo.clearTab(tabId)
        capture.forgetTab(tabId)
    }

    companion object {
        private const val TAG = "MediaDetector"
        private val SEGMENT_EXT = setOf("ts", "m4s", "aac", "mp4", "m4a", "webm", "cmfa", "cmfv")
        private const val MAX_NET_ENTRIES_PER_TAB = 60
        const val PROTECTED_MSG = "Contenido protegido (DRM/EME o cifrado): AudioVault no lo descarga ni lo descifra."
        const val TTS_MSG = "La voz la genera el motor de síntesis de Android (speechSynthesis), fuera de la página: no hay archivo. " +
            "Solo puede capturarse con el alcance «otras apps» si el motor TTS lo permite."
    }
}
