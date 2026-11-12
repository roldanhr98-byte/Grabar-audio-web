package com.audiovault.browser.capture

import android.content.Context
import android.util.Log
import com.audiovault.browser.data.db.AppDatabase
import com.audiovault.browser.data.db.AudioOrigin
import com.audiovault.browser.data.db.CaptureSessionEntity
import com.audiovault.browser.data.db.CaptureSource
import com.audiovault.browser.data.db.CaptureStatus
import com.audiovault.browser.data.db.SessionState
import com.audiovault.browser.data.settings.AppSettings
import com.audiovault.browser.data.settings.CaptureScope
import com.audiovault.browser.data.settings.OutputFormat
import com.audiovault.browser.data.settings.SettingsRepository
import com.audiovault.browser.detector.AcquireStatus
import com.audiovault.browser.detector.DetectionRepository
import com.audiovault.browser.library.ImportResult
import com.audiovault.browser.library.LibraryRepository
import com.audiovault.browser.library.SiteIdentifier
import com.audiovault.browser.library.SiteInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Estado del motor de captura (independiente de la sesión de grabación). */
sealed class EngineState {
    data object Off : EngineState()
    data object Starting : EngineState()
    data class Active(val source: CaptureSource, val scope: CaptureScope, val sampleRate: Int, val channels: Int) : EngineState()
    data class Error(val message: String) : EngineState()
}

/** Estado visible de la sesión de grabación actual. */
data class SessionUi(
    val state: Kind = Kind.IDLE,
    val siteLabel: String? = null,
    val pageUrl: String? = null,
    val startedAt: Long = 0,
    val recordedMs: Long = 0,
    val manual: Boolean = false,
    val source: CaptureSource? = null,
) {
    enum class Kind { IDLE, RECORDING, PAUSED }
}

/**
 * Controlador de la grabación automática por sesiones.
 *
 * Todo el trabajo con el escritor WAV ocurre en un único hilo ([exec]) para que los
 * eventos del detector y los bloques PCM se procesen en orden. Cada lectura se escribe
 * inmediatamente en la caché (filesDir/capture_cache) y, al finalizar, se valida y se
 * mueve a la biblioteca. Si la app muere, la sesión queda en la caché y se recupera.
 */
class CaptureController(
    private val context: Context,
    private val db: AppDatabase,
    private val library: LibraryRepository,
    private val settingsRepo: SettingsRepository,
    private val detection: DetectionRepository,
    private val appScope: CoroutineScope,
) {
    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "AudioVault-Session")
    }

    private val _engine = MutableStateFlow<EngineState>(EngineState.Off)
    val engine: StateFlow<EngineState> = _engine.asStateFlow()
    private val _session = MutableStateFlow(SessionUi())
    val session: StateFlow<SessionUi> = _session.asStateFlow()
    private val _level = MutableStateFlow(-120f)
    val levelDb: StateFlow<Float> = _level.asStateFlow()
    private val _warning = MutableStateFlow<String?>(null)
    val warning: StateFlow<String?> = _warning.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Último análisis (para la prueba de diagnóstico). */
    @Volatile var lastLevel: AudioLevel? = null
        private set
    private val levelListeners = java.util.concurrent.CopyOnWriteArrayList<(AudioLevel) -> Unit>()
    fun addLevelListener(l: (AudioLevel) -> Unit) { levelListeners += l }
    fun removeLevelListener(l: (AudioLevel) -> Unit) { levelListeners -= l }

    /** Durante el diagnóstico no se crean sesiones. */
    @Volatile var suppressSessions = false

    @Volatile private var settings = AppSettings()
    private val machine = SessionStateMachine(SessionStateMachine.Config())

    // Estado del motor (solo en exec)
    private var source: CaptureSource? = null
    private var sampleRate = 48000
    private var channels = 2
    private var preRoll = PreRollBuffer(48000 * 4)

    // Sesión actual (solo en exec)
    private class Current(
        val entity: CaptureSessionEntity,
        val writer: WavWriter,
        val site: SiteInfo,
        val manual: Boolean,
        var lastSoundBytes: Long,
        var maxPeak: Int = 0,
        var lastDbUpdate: Long = 0,
        val detectionId: String?,
    )
    private var current: Current? = null
    private val runningWebAudio = HashMap<String, SessionStateMachine.Context>()
    private val detectionByKey = HashMap<String, String?>()
    private val originalSavedAt = HashMap<String, Long>()
    private val pendingActive = LinkedHashMap<String, SessionStateMachine.Context>()

    val cacheDir: File get() = File(context.filesDir, "capture_cache").apply { mkdirs() }

    init {
        appScope.launch {
            settingsRepo.settings.collect { s ->
                settings = s
                exec.execute { machine.config = toConfig(s) }
            }
        }
        exec.scheduleWithFixedDelay({ safe { handle(machine.tick(now())) } }, 500, 500, TimeUnit.MILLISECONDS)
    }

    private fun toConfig(s: AppSettings) = SessionStateMachine.Config(
        autoRecord = s.autoRecord,
        silenceThresholdDb = s.silenceThresholdDb,
        silenceTimeoutMs = (s.silenceTimeoutSec * 1000).toLong(),
        maxSilenceWhilePlayingMs = (s.maxSilenceWhilePlayingSec * 1000).toLong(),
        minSegmentMs = (s.minSegmentSec * 1000).toLong(),
        pauseResumeWindowMs = (s.pauseResumeWindowMin * 60_000).toLong(),
        endTailMs = s.endTailMs.toLong(),
        levelTriggerEnabled = s.levelTriggerEnabled,
    )

    private fun now() = System.currentTimeMillis()
    private inline fun safe(block: () -> Unit) {
        try { block() } catch (t: Throwable) { Log.e(TAG, "error en sesión", t) }
    }

    // ------------------------------------------------------- motor (servicio)

    fun engineStarting() { _engine.value = EngineState.Starting; _warning.value = null }

    fun engineFailed(msg: String) {
        _engine.value = EngineState.Error(msg)
        emit(msg)
    }

    fun engineStarted(src: CaptureSource, scope: CaptureScope, sr: Int, ch: Int) {
        exec.execute {
            source = src
            sampleRate = sr
            channels = ch
            val bytes = (settings.preRollMs.coerceIn(0, 5000) * sr / 1000) * ch * 2
            preRoll.resize(maxOf(bytes, ch * 2))
            _engine.value = EngineState.Active(src, scope, sr, ch)
            // Si algo ya se estaba reproduciendo al activar la captura, se empieza a grabar ya.
            pendingActive.entries.lastOrNull()?.let { (k, ctx) ->
                safe { handle(machine.onMediaEvent(k, SessionStateMachine.Signal.PLAYING, ctx, now())) }
            }
            pendingActive.clear()
        }
    }

    /** Bloque PCM desde el hilo de captura. */
    fun onPcm(buf: ByteArray, len: Int) {
        val copy = buf.copyOf(len)
        exec.execute { safe { processPcm(copy) } }
    }

    fun engineStopped(reason: String?) {
        exec.execute {
            safe { handle(machine.captureStopped()) }
            source = null
            runningWebAudio.clear()
            _engine.value = if (reason != null) EngineState.Error(reason) else EngineState.Off
            _level.value = -120f
        }
    }

    private fun processPcm(data: ByteArray) {
        val lvl = LevelAnalyzer.analyze(data, data.size, sampleRate, channels)
        lastLevel = lvl
        _level.value = lvl.rmsDb
        levelListeners.forEach { runCatching { it(lvl) } }
        if (suppressSessions) { preRoll.push(data, data.size); return }

        // Disparo por nivel para Web Audio en streaming (si hay un contexto activo).
        val wa = runningWebAudio.entries.firstOrNull()
        val cmds = if (wa != null) machine.onAudio(lvl, now(), wa.value, wa.key, forceLevelTrigger = true)
        else machine.onAudio(lvl, now(), levelContext())
        handle(cmds)
        val cur = current
        if (cur != null && machine.isWriting) {
            cur.writer.write(data, 0, data.size)
            if (lvl.peak > cur.maxPeak) cur.maxPeak = lvl.peak
            if (lvl.rmsDb > settings.silenceThresholdDb) cur.lastSoundBytes = cur.writer.dataBytes
            val t = now()
            _session.value = _session.value.copy(recordedMs = cur.writer.durationMs)
            if (t - cur.lastDbUpdate > 2000) {
                cur.lastDbUpdate = t
                val id = cur.entity.id
                val bytes = cur.writer.dataBytes
                appScope.launch(Dispatchers.IO) { db.sessions().progress(id, bytes, t, SessionState.RECORDING) }
            }
        } else {
            preRoll.push(data, data.size)
        }
    }

    /** Página visible (para atribuir capturas iniciadas por nivel). */
    @Volatile var currentPageProvider: (() -> SessionStateMachine.Context)? = null

    private fun levelContext(): SessionStateMachine.Context {
        if (source == CaptureSource.MICROPHONE) return SessionStateMachine.Context()
        // Con alcance «otras apps» el sonido puede no venir del navegador: solo se atribuye
        // a la pestaña si la captura está limitada a AudioVault.
        val eng = _engine.value as? EngineState.Active
        return if (eng?.scope == CaptureScope.APP_ONLY) currentPageProvider?.invoke() ?: SessionStateMachine.Context()
        else SessionStateMachine.Context()
    }

    // ------------------------------------------------------- señales del detector

    fun onMediaSignal(key: String, signal: SessionStateMachine.Signal, ctx: SessionStateMachine.Context, detectionId: String?) {
        exec.execute {
            safe {
                if (detectionId != null) detectionByKey[key] = detectionId
                if (source == null || suppressSessions) {
                    // Sin motor activo solo se recuerda qué se está reproduciendo, para
                    // empezar a grabar en cuanto el usuario active la captura.
                    when (signal) {
                        SessionStateMachine.Signal.PLAY, SessionStateMachine.Signal.PLAYING,
                        SessionStateMachine.Signal.WAITING -> { pendingActive.remove(key); pendingActive[key] = ctx }
                        else -> pendingActive.remove(key)
                    }
                    return@safe
                }
                handle(machine.onMediaEvent(key, signal, ctx, now()))
            }
        }
    }


    fun onWebAudioContext(key: String, running: Boolean, ctx: SessionStateMachine.Context) {
        exec.execute { if (running) runningWebAudio[key] = ctx else runningWebAudio.remove(key) }
    }

    fun forgetTab(tabId: String) {
        exec.execute {
            machine.forgetTab(tabId)
            runningWebAudio.entries.removeAll { it.value.tabId == tabId }
        }
    }

    fun notifyOriginalSaved(tabId: String?) {
        if (tabId != null) exec.execute { originalSavedAt[tabId] = now() }
    }

    fun manualStart(ctx: SessionStateMachine.Context) {
        exec.execute { safe { if (source != null) handle(machine.manualStart(ctx, now())) else emit("Activa primero la captura") } }
    }

    fun manualStop() {
        exec.execute { safe { handle(machine.manualStop()) } }
    }

    // ------------------------------------------------------------ órdenes

    private fun handle(cmds: List<SessionStateMachine.Command>) {
        for (c in cmds) when (c) {
            is SessionStateMachine.Command.Start -> startSegment(c)
            is SessionStateMachine.Command.Pause -> pauseSegment()
            is SessionStateMachine.Command.Resume -> resumeSegment()
            is SessionStateMachine.Command.Finalize -> finalizeSegment(c)
            is SessionStateMachine.Command.Discard -> discardSegment(c.reason)
            is SessionStateMachine.Command.NotCapturableWarning -> notCapturable(c.key)
        }
    }

    private fun startSegment(c: SessionStateMachine.Command.Start) {
        val src = source ?: return
        current?.let { discardSegment("Sesión anterior abierta") }
        val site = when {
            c.context.pageUrl != null -> SiteIdentifier.identify(c.context.pageUrl)
            src == CaptureSource.MICROPHONE -> SiteIdentifier.forMicrophone()
            else -> SiteIdentifier.forExternalCapture()
        }
        val id = UUID.randomUUID().toString()
        val file = File(cacheDir, "$id.wav.part")
        val writer = WavWriter(file, sampleRate, channels)
        // Pre-roll: audio inmediatamente anterior al inicio detectado.
        val pre = preRoll.drain(channels * 2)
        if (pre.isNotEmpty()) writer.write(pre)
        writer.updateHeader(sync = true)
        val t = now()
        val entity = CaptureSessionEntity(
            id = id, cachePath = file.absolutePath, siteKey = site.key, siteLabel = site.label,
            pageUrl = c.context.pageUrl, pageTitle = c.context.pageTitle, mediaUrl = c.context.mediaUrl,
            source = src, sampleRate = sampleRate, channels = channels, startedAt = t, updatedAt = t,
            bytesWritten = writer.dataBytes, state = SessionState.RECORDING,
        )
        current = Current(entity, writer, site, c.manual, writer.dataBytes, detectionId = detectionByKey[c.key])
        appScope.launch(Dispatchers.IO) { db.sessions().upsert(entity) }
        _session.value = SessionUi(SessionUi.Kind.RECORDING, site.label, c.context.pageUrl, t, writer.durationMs, c.manual, src)
        _warning.value = null
        emit("Grabando: ${site.label}${if (c.manual) " (manual)" else ""}")
    }

    private fun pauseSegment() {
        val cur = current ?: return
        cur.writer.updateHeader(sync = true)
        val id = cur.entity.id
        val bytes = cur.writer.dataBytes
        appScope.launch(Dispatchers.IO) { db.sessions().progress(id, bytes, now(), SessionState.PAUSED) }
        _session.value = _session.value.copy(state = SessionUi.Kind.PAUSED)
    }

    private fun resumeSegment() {
        val cur = current ?: return
        val id = cur.entity.id
        val bytes = cur.writer.dataBytes
        appScope.launch(Dispatchers.IO) { db.sessions().progress(id, bytes, now(), SessionState.RECORDING) }
        _session.value = _session.value.copy(state = SessionUi.Kind.RECORDING)
        emit("Reanudada la misma lectura")
    }

    private fun discardSegment(reason: String) {
        val cur = current ?: return
        current = null
        runCatching { cur.writer.close() }
        File(cur.entity.cachePath).delete()
        val id = cur.entity.id
        appScope.launch(Dispatchers.IO) { db.sessions().delete(id) }
        _session.value = SessionUi()
        Log.i(TAG, "Sesión descartada: $reason")
    }

    private fun notCapturable(key: String) {
        val msg = "Se recibe silencio digital absoluto mientras la página reproduce: este audio no es capturable " +
            "(política de captura de la app de origen, uso no permitido, DRM o alcance de captura)."
        _warning.value = msg
        detectionByKey[key]?.let { did ->
            detection.update(did) { it.copy(captureNote = "No capturable: silencio digital") }
        }
        emit("Audio no capturable con la configuración actual")
    }

    private fun finalizeSegment(c: SessionStateMachine.Command.Finalize) {
        val cur = current ?: return
        current = null
        _session.value = SessionUi()
        val writer = cur.writer
        if (settings.trimTrailingSilence && !cur.manual) {
            val keep = cur.lastSoundBytes + writer.bytesPerSecond / 2
            if (keep < writer.dataBytes) writer.truncateData(keep)
        }
        writer.close()
        val file = File(cur.entity.cachePath)
        val recordedMs = writer.durationMs

        if (cur.maxPeak == 0) {
            // Todo el archivo es silencio digital: no se guarda como audio válido.
            file.delete()
            appScope.launch(Dispatchers.IO) { db.sessions().delete(cur.entity.id) }
            cur.detectionId?.let { did -> detection.update(did) { it.copy(captureNote = "No capturable: solo se recibió silencio") } }
            _warning.value = "La última reproducción no se pudo capturar: solo se recibió silencio digital."
            emit("No se guardó: la captura solo contenía silencio")
            return
        }

        val expected = c.context.mediaDurationSec?.takeIf { it.isFinite() && it > 0 }
        val shortVsMedia = expected != null && recordedMs < expected * 1000 * 0.85
        val incomplete = (c.mediaStillActive && c.reason != SessionStateMachine.EndReason.MANUAL) ||
            c.reason == SessionStateMachine.EndReason.MEDIA_ERROR || shortVsMedia
        val status = if (incomplete) CaptureStatus.INCOMPLETE else CaptureStatus.COMPLETE
        val notes = buildList {
            add("Fin: ${c.reason.label}.")
            if (expected != null) add("Duración según la página: %.1f s; capturado: %.1f s.".format(expected, recordedMs / 1000.0))
            if (cur.manual) add("Sesión iniciada manualmente.")
            if (c.context.tabId != null && (originalSavedAt[c.context.tabId] ?: 0) >= cur.entity.startedAt - 60_000) {
                add("También se guardó el archivo original descargado de esta página.")
            }
        }.joinToString(" ")
        val entity = cur.entity
        val src = entity.source
        val fmt = settings.outputFormat
        val bitrate = settings.aacBitrate
        appScope.launch(Dispatchers.IO) {
            db.sessions().progress(entity.id, file.length(), now(), SessionState.RECORDING)
            saveToLibrary(entity, file, cur.site, status, notes, fmt, bitrate, src)
        }
        emit("Lectura finalizada (${c.reason.label}), guardando…")
    }

    /** Valida, convierte (si se pidió M4A) y mueve el archivo de la caché a la biblioteca. */
    suspend fun saveToLibrary(
        entity: CaptureSessionEntity, file: File, site: SiteInfo, status: CaptureStatus, notes: String?,
        fmt: OutputFormat, bitrate: Int, src: CaptureSource,
    ): Boolean {
        var toImport = file
        var ext = "wav"
        var mime = "audio/wav"
        if (fmt == OutputFormat.M4A) {
            val m4a = File(file.parentFile, entity.id + ".m4a")
            if (AacEncoder.encode(file, m4a, bitrate)) {
                toImport = m4a; ext = "m4a"; mime = "audio/mp4"
            }
        }
        val origin = if (src == CaptureSource.MICROPHONE) AudioOrigin.CAPTURED_MICROPHONE else AudioOrigin.CAPTURED_INTERNAL
        val result = runCatching {
            library.import(
                temp = toImport, ext = ext, mime = mime, site = site, pageUrl = entity.pageUrl, pageTitle = entity.pageTitle,
                mediaUrl = entity.mediaUrl, origin = origin, status = status, notes = notes, minBytes = 2048,
            )
        }.getOrElse { ImportResult.Rejected(it.message ?: "error") }
        return when (result) {
            is ImportResult.Saved -> {
                if (toImport != file) file.delete()
                db.sessions().upsert(entity.copy(state = SessionState.FINALIZED, libraryItemId = result.item.id, updatedAt = now(), endReason = notes))
                db.sessions().delete(entity.id)
                emit("Guardado: ${site.folder}/${result.item.fileName}")
                true
            }
            is ImportResult.Duplicate -> {
                db.sessions().delete(entity.id)
                file.delete()
                true
            }
            is ImportResult.Rejected -> {
                // Se conserva en caché para recuperación manual.
                if (toImport != file) toImport.delete()
                if (file.exists()) {
                    db.sessions().upsert(entity.copy(state = SessionState.INTERRUPTED, updatedAt = now(), endReason = "No se pudo guardar: ${result.reason}"))
                } else {
                    db.sessions().delete(entity.id)
                }
                emit("No se pudo guardar: ${result.reason}")
                false
            }
        }
    }

    /** Cierra de forma segura la sesión abierta (p. ej. el usuario cierra el navegador). */
    fun finalizeNow(reasonStopped: Boolean = true) {
        val latch = java.util.concurrent.CountDownLatch(1)
        exec.execute {
            safe { handle(if (reasonStopped) machine.captureStopped() else machine.manualStop()) }
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
    }

    /** Fuerza la escritura física de la cabecera WAV (al pasar a segundo plano). */
    fun syncNow() {
        exec.execute { current?.writer?.updateHeader(sync = true) }
    }

    private fun emit(msg: String) {
        _messages.tryEmit(msg)
    }

    fun markDetectionCaptured(detectionId: String?, itemId: Long, label: String) {
        if (detectionId != null) detection.setStatus(detectionId, AcquireStatus.Saved(itemId, false, label))
    }

    companion object {
        private const val TAG = "CaptureController"
    }
}
