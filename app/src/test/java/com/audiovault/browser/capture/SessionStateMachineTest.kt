package com.audiovault.browser.capture

import com.audiovault.browser.capture.SessionStateMachine.Command
import com.audiovault.browser.capture.SessionStateMachine.EndReason
import com.audiovault.browser.capture.SessionStateMachine.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateMachineTest {
    private val loud = AudioLevel(rmsDb = -20f, peak = 8000, zeroCrossHz = 220f, durationMs = 100)
    private val quiet = AudioLevel(rmsDb = -80f, peak = 3, zeroCrossHz = 0f, durationMs = 100)
    private val digital0 = AudioLevel(rmsDb = -120f, peak = 0, zeroCrossHz = 0f, durationMs = 100)
    private val ctx = SessionStateMachine.Context(tabId = "t1", pageUrl = "https://claude.ai/chat/1", mediaDurationSec = 5.0)

    private class Sim(val m: SessionStateMachine) {
        var t = 0L
        val cmds = ArrayList<Command>()
        fun ev(key: String, s: Signal, c: SessionStateMachine.Context) { cmds += m.onMediaEvent(key, s, c, t) }
        fun audio(level: AudioLevel, ms: Long) {
            var left = ms
            while (left > 0) { t += 100; left -= 100; cmds += m.onAudio(level, t) }
        }
        inline fun <reified T : Command> of() = cmds.filterIsInstance<T>()
    }

    private fun sim(cfg: SessionStateMachine.Config = SessionStateMachine.Config()) = Sim(SessionStateMachine(cfg))

    @Test fun lecturaCompleta_unSoloArchivo() {
        val s = sim()
        s.ev("a", Signal.PLAY, ctx); s.ev("a", Signal.PLAYING, ctx)
        s.audio(loud, 5000)
        // Chromium emite pause justo antes de ended
        s.ev("a", Signal.PAUSE, ctx); s.ev("a", Signal.ENDED, ctx)
        s.audio(quiet, 1000)
        assertEquals(1, s.of<Command.Start>().size)
        assertEquals(0, s.of<Command.Pause>().size) // la pausa final no genera pausa
        val f = s.of<Command.Finalize>().single()
        assertEquals(EndReason.MEDIA_ENDED, f.reason)
        assertTrue(f.recordedMs >= 5000) // incluye la cola
    }

    @Test fun pausaYReanudacion_mismoArchivo() {
        val s = sim()
        s.ev("a", Signal.PLAY, ctx); s.audio(loud, 2000)
        s.ev("a", Signal.PAUSE, ctx); s.audio(quiet, 3000)
        s.ev("a", Signal.PLAY, ctx); s.audio(loud, 2000)
        s.ev("a", Signal.ENDED, ctx); s.audio(quiet, 1000)
        assertEquals(1, s.of<Command.Start>().size)
        assertEquals(1, s.of<Command.Pause>().size)
        assertEquals(1, s.of<Command.Resume>().size)
        assertEquals(1, s.of<Command.Finalize>().size)
        // No se escribe durante la pausa: ~2 s + cola + 2 s + cola
        val rec = s.of<Command.Finalize>().single().recordedMs
        assertTrue("grabado=$rec", rec in 4500..6000)
    }

    @Test fun dosLecturas_dosArchivos() {
        val s = sim()
        s.ev("a", Signal.PLAY, ctx); s.audio(loud, 3000)
        s.ev("b", Signal.PLAY, ctx.copy(mediaUrl = "x")); s.audio(loud, 3000)
        s.ev("b", Signal.ENDED, ctx); s.audio(quiet, 1000)
        assertEquals(2, s.of<Command.Start>().size)
        val fins = s.of<Command.Finalize>()
        assertEquals(2, fins.size)
        assertEquals(EndReason.NEW_MEDIA, fins[0].reason)
        assertEquals(EndReason.MEDIA_ENDED, fins[1].reason)
    }

    @Test fun silencioNatural_noCorta() {
        val s = sim()
        s.ev("a", Signal.PLAYING, ctx); s.audio(loud, 2000)
        s.audio(quiet, 15_000) // 15 s de silencio con la página reproduciendo
        s.audio(loud, 2000)
        assertTrue(s.of<Command.Finalize>().isEmpty())
    }

    @Test fun silencioProlongadoSinReproduccion_finaliza() {
        val s = sim()
        s.ev("a", Signal.PLAYING, ctx); s.audio(loud, 2000)
        s.ev("a", Signal.PAUSE, ctx)
        s.audio(quiet, 1000)
        // Ahora en pausa: el silencio no finaliza, la ventana de pausa sí.
        assertTrue(s.of<Command.Finalize>().isEmpty())
        s.t += 11 * 60_000; s.cmds += s.m.tick(s.t)
        assertEquals(EndReason.PAUSE_TIMEOUT, s.of<Command.Finalize>().single().reason)
    }

    @Test fun silencioMaximoConReproduccion_finalizaComoIncompleto() {
        val s = sim(SessionStateMachine.Config(maxSilenceWhilePlayingMs = 5_000))
        s.ev("a", Signal.PLAYING, ctx); s.audio(loud, 2000); s.audio(quiet, 6000)
        val f = s.of<Command.Finalize>().single()
        assertEquals(EndReason.SILENCE, f.reason)
        assertTrue(f.mediaStillActive)
    }

    @Test fun demasiadoCorto_seDescarta() {
        val s = sim()
        s.ev("a", Signal.PLAY, ctx); s.audio(loud, 300)
        s.ev("a", Signal.ENDED, ctx); s.audio(quiet, 1000)
        assertEquals(1, s.of<Command.Discard>().size)
        assertTrue(s.of<Command.Finalize>().isEmpty())
    }

    @Test fun sesionManual_ignoraEventos() {
        val s = sim()
        s.cmds += s.m.manualStart(SessionStateMachine.Context(), 0)
        s.audio(loud, 1000)
        s.ev("a", Signal.PLAY, ctx); s.ev("a", Signal.PAUSE, ctx); s.ev("a", Signal.ENDED, ctx)
        s.audio(quiet, 20_000)
        assertTrue(s.of<Command.Finalize>().isEmpty())
        s.cmds += s.m.manualStop()
        assertEquals(EndReason.MANUAL, s.of<Command.Finalize>().single().reason)
    }

    @Test fun silencioDigital_avisaNoCapturable() {
        val s = sim()
        s.ev("a", Signal.PLAYING, ctx); s.audio(digital0, 5000)
        assertEquals(1, s.of<Command.NotCapturableWarning>().size)
    }

    @Test fun autoGrabacionDesactivada_noInicia() {
        val s = sim(SessionStateMachine.Config(autoRecord = false))
        s.ev("a", Signal.PLAY, ctx); s.audio(loud, 2000)
        assertTrue(s.of<Command.Start>().isEmpty())
    }

    @Test fun disparoPorNivel() {
        val s = sim(SessionStateMachine.Config(levelTriggerEnabled = true, silenceTimeoutMs = 3000))
        s.audio(quiet, 1000)
        s.audio(loud, 3000)
        assertEquals(1, s.of<Command.Start>().size)
        s.audio(quiet, 4000)
        assertEquals(EndReason.SILENCE, s.of<Command.Finalize>().single().reason)
    }

    @Test fun capturaDetenida_finalizaSesionAbierta() {
        val s = sim()
        s.ev("a", Signal.PLAYING, ctx); s.audio(loud, 3000)
        s.cmds += s.m.captureStopped()
        val f = s.of<Command.Finalize>().single()
        assertEquals(EndReason.CAPTURE_STOPPED, f.reason)
        assertTrue(f.mediaStillActive)
    }

    @Test fun esperaDeBuffer_noCorta() {
        val s = sim(SessionStateMachine.Config(silenceTimeoutMs = 2000))
        s.ev("a", Signal.PLAYING, ctx); s.audio(loud, 1000)
        s.ev("a", Signal.WAITING, ctx); s.audio(quiet, 8000) // audio generado progresivamente
        s.ev("a", Signal.PLAYING, ctx); s.audio(loud, 1000)
        assertTrue(s.of<Command.Finalize>().isEmpty())
        assertEquals(1, s.of<Command.Start>().size)
    }
}
