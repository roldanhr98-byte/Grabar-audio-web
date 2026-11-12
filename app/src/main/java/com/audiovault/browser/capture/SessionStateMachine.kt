package com.audiovault.browser.capture

/**
 * Máquina de estados pura (sin Android) que decide cuándo empieza, se pausa,
 * se reanuda y termina cada lectura. Así cada reproducción produce un archivo
 * independiente y la lógica es comprobable con pruebas unitarias.
 *
 * Entradas: eventos de reproducción del detector JavaScript, nivel de audio de
 * cada bloque PCM capturado, órdenes manuales y ticks de reloj.
 * Salidas: [Command]s que el controlador ejecuta sobre el escritor WAV.
 */
class SessionStateMachine(var config: Config) {

    data class Config(
        val autoRecord: Boolean = true,
        val silenceThresholdDb: Float = -50f,
        val silenceTimeoutMs: Long = 6_000,
        val maxSilenceWhilePlayingMs: Long = 45_000,
        val minSegmentMs: Long = 1_500,
        val pauseResumeWindowMs: Long = 10 * 60_000,
        val endTailMs: Long = 700,
        val levelTriggerEnabled: Boolean = false,
        val levelTriggerMs: Long = 250,
        /** Tiempo con silencio digital absoluto mientras se reproduce para avisar de "no capturable". */
        val digitalSilenceWarnMs: Long = 4_000,
    )

    enum class Signal { PLAY, PLAYING, PAUSE, ENDED, WAITING, EMPTIED, ERROR }

    enum class EndReason(val label: String) {
        MEDIA_ENDED("Fin de la reproducción"),
        SILENCE("Silencio prolongado"),
        NEW_MEDIA("Comenzó otra reproducción"),
        PAUSE_TIMEOUT("Pausa demasiado larga"),
        MANUAL("Detenido manualmente"),
        MEDIA_ERROR("Error de reproducción en la página"),
        CAPTURE_STOPPED("Captura detenida"),
    }

    data class Context(
        val tabId: String? = null,
        val pageUrl: String? = null,
        val pageTitle: String? = null,
        val mediaUrl: String? = null,
        val mediaDurationSec: Double? = null,
    )

    sealed class Command {
        data class Start(val key: String, val context: Context, val manual: Boolean) : Command()
        data class Pause(val key: String) : Command()
        data class Resume(val key: String) : Command()
        data class Finalize(
            val key: String,
            val reason: EndReason,
            /** true si la página seguía reproduciendo (posible archivo incompleto). */
            val mediaStillActive: Boolean,
            val recordedMs: Long,
            val context: Context,
        ) : Command()
        data class Discard(val key: String, val reason: String) : Command()
        data class NotCapturableWarning(val key: String) : Command()
    }

    sealed class State {
        data object Idle : State()
        data class Recording(
            val key: String,
            val context: Context,
            val manual: Boolean,
            val recordedMs: Long,
            val lastSoundAt: Long,
            val endingAt: Long?,
            val digitalSilenceSince: Long?,
            val warned: Boolean,
            /** La página pausó: seguimos escribiendo la "cola" hasta este instante. */
            val pausingAt: Long? = null,
        ) : State()
        data class Paused(
            val key: String,
            val context: Context,
            val manual: Boolean,
            val recordedMs: Long,
            val pausedAt: Long,
        ) : State()
    }

    var state: State = State.Idle
        private set

    /** Medios que la página indica como reproduciéndose (o cargando) ahora mismo. */
    private val active = HashMap<String, Context>()
    private var aboveSince: Long? = null

    val isWriting: Boolean get() = state is State.Recording
    val activeMediaCount: Int get() = active.size

    fun currentKey(): String? = when (val s = state) {
        is State.Recording -> s.key
        is State.Paused -> s.key
        State.Idle -> null
    }

    // ---------------------------------------------------------------- eventos

    fun onMediaEvent(key: String, signal: Signal, ctx: Context, now: Long): List<Command> {
        val out = ArrayList<Command>()
        when (signal) {
            Signal.PLAY, Signal.PLAYING, Signal.WAITING -> active[key] = ctx
            Signal.PAUSE, Signal.ENDED, Signal.EMPTIED, Signal.ERROR -> active.remove(key)
        }
        val s = state
        when (signal) {
            Signal.PLAY, Signal.PLAYING -> when (s) {
                State.Idle -> if (config.autoRecord) start(key, ctx, false, now, out)
                is State.Recording -> when {
                    s.key == key -> if (s.endingAt != null || s.pausingAt != null) {
                        state = s.copy(endingAt = null, pausingAt = null, lastSoundAt = now)
                    }
                    s.manual -> Unit // La sesión manual no se corta por eventos automáticos.
                    else -> {
                        finalize(s.key, EndReason.NEW_MEDIA, s.recordedMs, s.context, out, stillActive = active.containsKey(s.key))
                        if (config.autoRecord) start(key, ctx, false, now, out)
                    }
                }
                is State.Paused -> when {
                    s.key == key -> {
                        state = State.Recording(key, mergeCtx(s.context, ctx), s.manual, s.recordedMs, now, null, null, false)
                        out += Command.Resume(key)
                    }
                    s.manual -> Unit
                    else -> {
                        finalize(s.key, EndReason.NEW_MEDIA, s.recordedMs, s.context, out, stillActive = false)
                        if (config.autoRecord) start(key, ctx, false, now, out)
                    }
                }
            }
            Signal.WAITING -> if (s is State.Recording && s.key == key) state = s.copy(lastSoundAt = now)
            Signal.PAUSE -> if (s is State.Recording && s.key == key && !s.manual && s.endingAt == null) {
                // Chromium emite "pause" justo antes de "ended": seguimos grabando la cola
                // (latencia de salida) y solo después pasamos a pausa.
                if (s.pausingAt == null) state = s.copy(pausingAt = now + config.endTailMs)
            }
            Signal.ENDED, Signal.EMPTIED -> when {
                s is State.Recording && s.key == key && !s.manual ->
                    state = s.copy(endingAt = s.pausingAt ?: (now + config.endTailMs), pausingAt = null, context = mergeCtx(s.context, ctx))
                s is State.Paused && s.key == key && !s.manual ->
                    finalize(key, EndReason.MEDIA_ENDED, s.recordedMs, s.context, out, stillActive = false)
            }
            Signal.ERROR -> when {
                s is State.Recording && s.key == key && !s.manual ->
                    finalize(key, EndReason.MEDIA_ERROR, s.recordedMs, s.context, out, stillActive = true)
                s is State.Paused && s.key == key && !s.manual ->
                    finalize(key, EndReason.MEDIA_ERROR, s.recordedMs, s.context, out, stillActive = true)
            }
        }
        return out
    }

    /**
     * Se llama por cada bloque PCM ya analizado.
     * [levelKey]/[forceLevelTrigger] permiten iniciar por nivel cuando la página usa
     * Web Audio en streaming (sin eventos de elemento), aunque el disparo por nivel general esté apagado.
     */
    fun onAudio(
        level: AudioLevel,
        now: Long,
        levelCtx: Context = Context(),
        levelKey: String = LEVEL_KEY,
        forceLevelTrigger: Boolean = false,
    ): List<Command> {
        val out = ArrayList<Command>()
        val loud = level.rmsDb > config.silenceThresholdDb
        when (val s = state) {
            State.Idle -> {
                if (config.autoRecord && (config.levelTriggerEnabled || forceLevelTrigger) && loud) {
                    val since = aboveSince ?: now.also { aboveSince = it }
                    if (now - since >= config.levelTriggerMs) {
                        aboveSince = null
                        start(levelKey, levelCtx, false, now, out)
                    }
                } else if (!loud) aboveSince = null
            }
            is State.Recording -> {
                val recorded = s.recordedMs + level.durationMs
                val lastSound = if (loud) now else s.lastSoundAt
                val mediaActive = active.containsKey(s.key)
                var dsSince = s.digitalSilenceSince
                var warned = s.warned
                if (mediaActive && level.isDigitalSilence) {
                    if (dsSince == null) dsSince = now
                    if (!warned && now - dsSince >= config.digitalSilenceWarnMs) {
                        warned = true
                        out += Command.NotCapturableWarning(s.key)
                    }
                } else if (!level.isDigitalSilence) dsSince = null
                state = s.copy(recordedMs = recorded, lastSoundAt = lastSound, digitalSilenceSince = dsSince, warned = warned)
                checkEnd(now, out)
            }
            is State.Paused -> checkPauseTimeout(s, now, out)
        }
        return out
    }

    /** Revisión periódica (por si no llegan bloques de audio). */
    fun tick(now: Long): List<Command> {
        val out = ArrayList<Command>()
        when (val s = state) {
            is State.Recording -> checkEnd(now, out)
            is State.Paused -> checkPauseTimeout(s, now, out)
            State.Idle -> Unit
        }
        return out
    }

    fun manualStart(ctx: Context, now: Long): List<Command> {
        val out = ArrayList<Command>()
        when (val s = state) {
            is State.Recording -> finalize(s.key, EndReason.MANUAL, s.recordedMs, s.context, out, active.containsKey(s.key))
            is State.Paused -> finalize(s.key, EndReason.MANUAL, s.recordedMs, s.context, out, false)
            State.Idle -> Unit
        }
        start(MANUAL_KEY, ctx, true, now, out)
        return out
    }

    fun manualStop(): List<Command> {
        val out = ArrayList<Command>()
        when (val s = state) {
            is State.Recording -> finalize(s.key, EndReason.MANUAL, s.recordedMs, s.context, out, active.containsKey(s.key), force = true)
            is State.Paused -> finalize(s.key, EndReason.MANUAL, s.recordedMs, s.context, out, false, force = true)
            State.Idle -> Unit
        }
        return out
    }

    /** La captura se detiene (usuario, sistema o revocación del permiso). */
    fun captureStopped(): List<Command> {
        val out = ArrayList<Command>()
        when (val s = state) {
            is State.Recording -> finalize(s.key, EndReason.CAPTURE_STOPPED, s.recordedMs, s.context, out, active.containsKey(s.key))
            is State.Paused -> finalize(s.key, EndReason.CAPTURE_STOPPED, s.recordedMs, s.context, out, true)
            State.Idle -> Unit
        }
        active.clear()
        return out
    }

    fun forgetTab(tabId: String) {
        active.entries.removeAll { it.value.tabId == tabId }
    }

    // ---------------------------------------------------------------- interno

    private fun start(key: String, ctx: Context, manual: Boolean, now: Long, out: MutableList<Command>) {
        state = State.Recording(key, ctx, manual, 0, now, null, null, false)
        out += Command.Start(key, ctx, manual)
    }

    private fun checkEnd(now: Long, out: MutableList<Command>) {
        val s = state as? State.Recording ?: return
        if (s.endingAt != null && now >= s.endingAt) {
            finalize(s.key, EndReason.MEDIA_ENDED, s.recordedMs, s.context, out, stillActive = false)
            return
        }
        if (s.pausingAt != null && now >= s.pausingAt) {
            state = State.Paused(s.key, s.context, s.manual, s.recordedMs, now)
            out += Command.Pause(s.key)
            return
        }
        if (s.manual) return
        val mediaActive = active.containsKey(s.key)
        val limit = if (mediaActive) config.maxSilenceWhilePlayingMs else config.silenceTimeoutMs
        if (now - s.lastSoundAt >= limit) {
            finalize(s.key, EndReason.SILENCE, s.recordedMs, s.context, out, stillActive = mediaActive)
        }
    }

    private fun checkPauseTimeout(s: State.Paused, now: Long, out: MutableList<Command>) {
        if (!s.manual && now - s.pausedAt >= config.pauseResumeWindowMs) {
            finalize(s.key, EndReason.PAUSE_TIMEOUT, s.recordedMs, s.context, out, stillActive = false)
        }
    }

    private fun finalize(
        key: String, reason: EndReason, recordedMs: Long, ctx: Context,
        out: MutableList<Command>, stillActive: Boolean, force: Boolean = false,
    ) {
        state = State.Idle
        aboveSince = null
        if (!force && recordedMs < config.minSegmentMs) {
            out += Command.Discard(key, "Duración ${recordedMs} ms menor que el mínimo ${config.minSegmentMs} ms")
        } else {
            out += Command.Finalize(key, reason, stillActive, recordedMs, ctx)
        }
    }

    private fun mergeCtx(a: Context, b: Context) = Context(
        tabId = b.tabId ?: a.tabId,
        pageUrl = b.pageUrl ?: a.pageUrl,
        pageTitle = b.pageTitle ?: a.pageTitle,
        mediaUrl = b.mediaUrl ?: a.mediaUrl,
        mediaDurationSec = b.mediaDurationSec?.takeIf { it.isFinite() && it > 0 } ?: a.mediaDurationSec,
    )

    companion object {
        const val LEVEL_KEY = "level"
        const val MANUAL_KEY = "manual"
    }
}
